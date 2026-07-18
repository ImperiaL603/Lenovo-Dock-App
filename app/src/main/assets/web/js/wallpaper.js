/* ============================================================
   Wallpaper picker — swaps the clock-mode background video and
   remembers the choice in localStorage.

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

  const hasBridge = typeof AndroidMedia !== 'undefined';
  // On-device file:// base; falls back to the in-APK asset path when the
  // page is opened outside the native shell (e.g. a desktop browser).
  const BASE_URL = hasBridge ? AndroidMedia.wallpapersBaseUrl() : 'assets/video/';

  const video = document.getElementById('bg-video');

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
      .replace(/\.1920x1080\.mp4$/i, '')
      .replace(/\.mp4$/i, '')
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
  }

  function renderPicker(container, files) {
    const current = getSaved();
    files.forEach((file) => {
      const chip = document.createElement('button');
      chip.className = 'wallpaper-chip';
      chip.textContent = displayName(file);
      chip.classList.toggle('selected', file === current);
      chip.addEventListener('click', () => {
        applyWallpaper(file);
        container.querySelectorAll('.wallpaper-chip')
          .forEach((c) => c.classList.toggle('selected', c === chip));
      });
      container.appendChild(chip);
    });
  }

  const files = listWallpapers();

  // Restore the saved choice on boot. The in-APK default is already the
  // first-paint src, so only reload for a different, still-present file;
  // if the saved file was removed from the folder, fall back to default.
  const saved = getSaved();
  const target = files.includes(saved) ? saved : DEFAULT;
  if (target !== DEFAULT) applyWallpaper(target);

  renderPicker(document.getElementById('wallpaper-grid'), files);
})();
