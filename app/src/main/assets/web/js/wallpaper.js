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

  // Matches the first-paint src hardcoded on #bg-video in index.html (an
  // in-APK copy), so the clock always has a wallpaper even before the
  // device folder is read.
  const DEFAULT = 'endless-summer-horizon.1920x1080.mp4';
  const STORAGE_KEY = 'wallpaper';
  const CYCLE_KEY = 'wallpaper-cycle';
  // Only has to notice midnight, and the dock is awake for most of them.
  const CHECK_MS = 60000;

  const hasBridge = typeof AndroidMedia !== 'undefined';
  // On-device file:// base; falls back to the in-APK asset path when the
  // page is opened outside the native shell (e.g. a desktop browser).
  const BASE_URL = hasBridge ? AndroidMedia.wallpapersBaseUrl() : 'assets/video/';

  // Where the in-APK copy of DEFAULT lives, which is not the device folder. Needed
  // when the applied wallpaper is deleted and there is nothing on device to fall
  // back to.
  const APK_BASE = 'assets/video/';

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
    if (!hasBridge) return [DEFAULT];
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

  const getSaved = () => localStorage.getItem(STORAGE_KEY) || DEFAULT;

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

  /** DEFAULT ships inside the APK as well as possibly living on device, so this
   *  prefers the device copy and falls back to the bundled one. Without that, a
   *  dock whose folder never contained the default would end up with a dead src. */
  function applyDefault() {
    localStorage.setItem(STORAGE_KEY, DEFAULT);
    video.src = files.includes(DEFAULT) ? `${BASE_URL}${DEFAULT}` : `${APK_BASE}${DEFAULT}`;
    video.load();
    video.play();
    markSelected(DEFAULT);
  }

  /** Re-reads the folder after anything changes it. If the wallpaper on screen was
   *  the one just deleted, it has to be replaced or the clock keeps playing a file
   *  that no longer exists. */
  function refresh() {
    files = listWallpapers();
    renderPicker(files);
    const saved = getSaved();
    if (files.includes(saved)) markSelected(saved); else applyDefault();
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
    // The in-APK default is already the first-paint src, so only reload for a
    // different, still-present file; if the saved one was removed from the folder,
    // fall back to the default.
    const saved = getSaved();
    const target = files.includes(saved) ? saved : DEFAULT;
    if (target !== DEFAULT) applyWallpaper(target); else markSelected(DEFAULT);
  }

  setInterval(advanceCycle, CHECK_MS);
  // The interval doesn't run while the device is asleep, so a dock that spent the
  // night suspended would otherwise wait up to a minute after waking.
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) advanceCycle();
  });
})();
