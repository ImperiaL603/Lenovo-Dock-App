/* ============================================================
   Wallpaper picker — swaps the clock-mode background video,
   remembers the choice, and optionally rotates it once a day.

   The available wallpapers are whatever .mp4 files live in the app's
   on-device wallpapers folder. The native bridge (AndroidMedia) lists
   them and gives us the file:// base URL to load them from, so the
   folder is the single source of truth. To add a wallpaper: adb push a
   new .mp4 into that folder — no code change needed.
   ============================================================ */
(function () {
  'use strict';

  // There is no bundled wallpaper. Everything comes from the on-device folder, so
  // "none" is a real state the whole file has to cope with: a fresh install has an
  // empty folder until the owner adds something with Add +.
  const STORAGE_KEY = 'wallpaper';
  const CYCLE_KEY = 'wallpaper-cycle';
  // Only has to notice midnight, and the dock is awake for most of them.
  const CHECK_MS = 60000;

  const hasBridge = typeof AndroidMedia !== 'undefined';
  // Empty outside the native shell (a desktop browser), where there is no folder to
  // read and therefore nothing to play.
  const BASE_URL = hasBridge ? AndroidMedia.wallpapersBaseUrl() : '';

  const video = document.getElementById('bg-video');
  const grid = document.getElementById('wallpaper-grid');
  const cycleRow = document.getElementById('wallpaper-cycle-row');
  const section = document.querySelector('.wallpaper-section');
  const addBtn = document.getElementById('wallpaper-add');
  const removeBtn = document.getElementById('wallpaper-remove');
  const deleteBtn = document.getElementById('wallpaper-delete');
  const statusEl = document.getElementById('wallpaper-status');

  // In select mode a chip tap marks for deletion instead of applying the wallpaper.
  let picking = false;

  function listWallpapers() {
    if (!hasBridge) return [];
    try {
      return JSON.parse(AndroidMedia.listWallpapers() || '[]');
    } catch (e) {
      return [];
    }
  }

  function displayName(file) {
    return file
      .replace(/\.1920x1080(?=\.[a-z0-9]+$)/i, '')
      .replace(/\.(mp4|webm|m4v)$/i, '')
      .split('-')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' ');
  }

  // '' rather than a default filename — there is no longer one to fall back to.
  const getSaved = () => localStorage.getItem(STORAGE_KEY) || '';

  function applyWallpaper(file) {
    localStorage.setItem(STORAGE_KEY, file);
    video.src = `${BASE_URL}${file}`;
    video.load();
    video.play();
    markSelected(file);
  }

  /** The only writer of .selected, so the chips can't disagree with the video. */
  function markSelected(file) {
    grid.querySelectorAll('.wallpaper-chip')
      .forEach((c) => c.classList.toggle('selected', c.dataset.file === file));
  }

  /** Rebuilds from scratch — the list changes now that wallpapers can be added and
   *  deleted, so appending would duplicate every chip on the second call. */
  function renderPicker(list) {
    grid.innerHTML = '';
    if (!list.length) {
      // Same primitive the alarms panel uses for "No alarms set", so an empty
      // library reads like every other empty list in the app.
      const empty = document.createElement('div');
      empty.className = 'placeholder-row';
      empty.textContent = 'No wallpapers yet — use Add + to choose a video.';
      grid.appendChild(empty);
      return;
    }
    list.forEach((file) => {
      const chip = document.createElement('button');
      chip.className = 'wallpaper-chip';
      chip.dataset.file = file;
      chip.textContent = displayName(file);
      chip.addEventListener('click', () => {
        if (picking) {
          chip.classList.toggle('marked');
          return;
        }
        // Picking one by hand means wanting that one to stay, so it stops the
        // rotation rather than being replaced again at midnight.
        setCycle(false);
        applyWallpaper(file);
      });
      grid.appendChild(chip);
    });
  }

  /* ---------- Library: add and remove ---------- */

  const setStatus = (text) => { statusEl.textContent = text || ''; };

  /** Empties the element rather than pointing it at a placeholder, which is what
   *  lets the page's gradient show through. Reached when the folder is empty. */
  function clearWallpaper() {
    localStorage.removeItem(STORAGE_KEY);
    video.removeAttribute('src');
    video.load();
    markSelected('');
  }

  /** Re-reads the folder after anything changes it. If the wallpaper on screen was
   *  the one just deleted, it has to be replaced or the clock keeps playing a file
   *  that no longer exists — falling to the next one along, or to nothing. */
  function refresh() {
    files = listWallpapers();
    renderPicker(files);
    const saved = getSaved();
    if (files.includes(saved)) markSelected(saved);
    else if (files.length) applyWallpaper(files[0]);
    else clearWallpaper();
  }

  function endPicking() {
    picking = false;
    section.classList.remove('picking');
    removeBtn.classList.remove('selected');
  }

  addBtn.addEventListener('click', () => {
    if (!hasBridge) return;
    setStatus('Choose a video…');
    AndroidMedia.addWallpaper();
  });

  removeBtn.addEventListener('click', () => {
    if (picking) {
      endPicking();
      setStatus('');
      return;
    }
    picking = true;
    section.classList.add('picking');
    removeBtn.classList.add('selected');
    setStatus('Tap wallpapers to mark them, then press Delete.');
  });

  deleteBtn.addEventListener('click', () => {
    const marked = Array.from(grid.querySelectorAll('.wallpaper-chip.marked'))
      .map((c) => c.dataset.file);
    if (!marked.length) {
      setStatus('Nothing marked yet.');
      return;
    }
    const removed = hasBridge ? AndroidMedia.deleteWallpapers(JSON.stringify(marked)) : 0;
    endPicking();
    refresh();
    setStatus(`Deleted ${removed} wallpaper${removed === 1 ? '' : 's'}.`);
  });

  // Native pushes the result of the file picker here, because a bridge call cannot
  // wait on an Activity result. A null name means cancelled, or the copy failed.
  // Merged rather than assigned — script.js and spotify.js share this object and
  // this file loads first.
  window.LenovoDock = Object.assign(window.LenovoDock || {}, {
    onWallpaperAdded(name) {
      if (!name) {
        setStatus('Nothing added.');
        return;
      }
      refresh();
      setStatus(`Added "${displayName(name)}". It now lives inside the app — `
        + 'you can safely delete the original from your gallery or files.');
    },
  });

  /* ---------- Daily cycle ----------
     One stored record rather than four keys: the shuffled order, the position in
     it, the on/off flag and the day the current pick belongs to are only meaningful
     together, and a half-written set of them would strand the rotation. */

  function loadCycle() {
    try {
      const c = JSON.parse(localStorage.getItem(CYCLE_KEY) || '{}');
      return {
        on: c.on === true,
        order: Array.isArray(c.order) ? c.order : [],
        pos: Number(c.pos) || 0,
        day: c.day || '',
      };
    } catch (e) {
      return { on: false, order: [], pos: 0, day: '' };
    }
  }

  const saveCycle = (c) => localStorage.setItem(CYCLE_KEY, JSON.stringify(c));

  // Local-time and calendar-based, which is what "once a day" means to someone
  // looking at the dock — not 24h after whenever the toggle happened to be flipped.
  const today = () => new Date().toDateString();

  /**
   * Fisher-Yates on a copy. Every wallpaper gets its turn before any repeats, which
   * a plain random pick can't promise across 29 files. The one currently showing is
   * rotated off the front so that turning the toggle on visibly does something.
   */
  function shuffle(list, avoidFirst) {
    const a = list.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    if (a.length > 1 && a[0] === avoidFirst) a.push(a.shift());
    return a;
  }

  /**
   * Advances at most one step per calendar day, and reports whether it changed the
   * wallpaper. Driven from three places — load, a slow tick, and the page becoming
   * visible — because a dock left running crosses midnight on the tick while one that
   * was suspended or restarted catches up on the other two. All three land on exactly
   * one step, because the stored day stamp gates it rather than elapsed time.
   */
  function advanceCycle() {
    const c = loadCycle();
    if (!c.on || c.day === today() || !files.length) return false;

    let { order, pos } = c;
    // Reshuffle when the folder has changed under us or the run is spent, so an .mp4
    // pushed by adb joins the rotation and a deleted one drops out of it.
    const stale = order.length !== files.length || order.some((f) => !files.includes(f));
    if (stale || pos + 1 >= order.length) {
      order = shuffle(files, getSaved());
      pos = 0;
    } else {
      pos += 1;
    }
    saveCycle({ on: true, order, pos, day: today() });
    applyWallpaper(order[pos]);
    return true;
  }

  function markCycleChips(on) {
    cycleRow.querySelectorAll('.option-chip')
      .forEach((chip) => chip.classList.toggle('selected', (chip.dataset.cycle === 'on') === on));
  }

  function setCycle(on) {
    const c = loadCycle();
    markCycleChips(on);
    if (c.on === on) return; // tapping the chip that is already lit changes nothing
    // Clearing the day stamp is what makes advanceCycle treat this as due: switching
    // on rotates straight away, since an unchanged screen would look like it hadn't.
    saveCycle({ ...c, on, day: on ? '' : c.day });
    if (on) advanceCycle();
  }

  cycleRow.addEventListener('click', (e) => {
    const v = e.target.dataset.cycle;
    if (v) setCycle(v === 'on');
  });

  // let, not const: the folder is now editable from the settings panel.
  let files = listWallpapers();
  renderPicker(files);
  markCycleChips(loadCycle().on);

  // Cycle first: if a day has passed it is about to pick a different wallpaper, and
  // restoring the saved one beforehand would decode a 1080p video for nothing.
  if (!advanceCycle()) {
    // Nothing is loaded at first paint any more, so unlike before there is always a
    // src to set — unless the folder is empty, which is now a legitimate state.
    const saved = getSaved();
    if (files.includes(saved)) applyWallpaper(saved);
    else if (files.length) applyWallpaper(files[0]);
    else clearWallpaper();
  }

  setInterval(advanceCycle, CHECK_MS);
  // The interval doesn't run while the device is asleep, so a dock that spent the
  // night suspended would otherwise wait up to a minute after waking.
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) advanceCycle();
  });
})();
