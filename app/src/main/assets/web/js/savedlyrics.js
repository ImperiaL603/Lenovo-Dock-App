/* ============================================================
   Saved lyrics — the Settings list that manages them.

   Native owns what is saved: each song is a file, and this page only ever asks
   (AndroidMedia.listSavedLyrics) rather than keeping a copy that could drift.
   The "Save Lyrics" button itself lives in nowplaying.js, next to the lyrics it saves.

   Deleting follows the wallpaper library's flow: Remove puts the list into select
   mode, tapping rows marks them, Delete removes the marked ones. Delete all sits at
   the top and asks twice, because it is the one control here that cannot be undone
   in a single tap.
   ============================================================ */
(function () {
  'use strict';

  const hasBridge = typeof AndroidMedia !== 'undefined';

  const section = document.querySelector('.saved-lyrics-section');
  const listEl = document.getElementById('saved-list');
  const removeBtn = document.getElementById('saved-remove');
  const deleteBtn = document.getElementById('saved-delete');
  const deleteAllBtn = document.getElementById('saved-delete-all');
  const statusEl = document.getElementById('saved-status');

  const CONFIRM_MS = 4000;       // how long "Delete all" waits for its second tap
  const DELETE_ALL_LABEL = 'Delete all';

  let picking = false;
  let confirmTimer = null;

  const setStatus = (text) => { statusEl.textContent = text || ''; };

  function fetchList() {
    if (!hasBridge) return [];
    try {
      return JSON.parse(AndroidMedia.listSavedLyrics() || '[]');
    } catch (e) {
      return [];
    }
  }

  // The Save Lyrics button reads its state from native too, so after anything here
  // changes the files it has to be told to look again.
  function refreshSaveButton() {
    if (window.LenovoDock && LenovoDock.refreshSave) LenovoDock.refreshSave();
  }

  /** Rebuilds from scratch: the list changes under it, so appending would duplicate. */
  function render() {
    const items = fetchList();
    listEl.innerHTML = '';
    if (!items.length) {
      if (picking) endPicking();
      // Same primitive the alarms panel uses for "No alarms set".
      const empty = document.createElement('div');
      empty.className = 'placeholder-row';
      empty.textContent = 'No saved lyrics yet — tap Save Lyrics while a song is playing.';
      listEl.appendChild(empty);
      return;
    }
    items.forEach((item) => {
      const row = document.createElement('button');
      row.className = 'saved-row';
      row.dataset.id = item.id;

      const title = document.createElement('span');
      title.className = 'saved-row-title';
      title.textContent = item.title || 'Untitled';
      row.appendChild(title);

      const artist = document.createElement('span');
      artist.className = 'saved-row-artist';
      artist.textContent = item.artist || '';
      row.appendChild(artist);

      // Lyrics with no timings scroll by progress instead of highlighting a line, so
      // it is worth knowing which kind a saved song is.
      if (!item.synced) {
        const tag = document.createElement('span');
        tag.className = 'saved-row-tag';
        tag.textContent = 'Plain';
        row.appendChild(tag);
      }

      row.addEventListener('click', () => {
        if (picking) row.classList.toggle('marked');
      });
      listEl.appendChild(row);
    });
  }

  function endPicking() {
    picking = false;
    section.classList.remove('picking');
    removeBtn.classList.remove('selected');
  }

  function disarmDeleteAll() {
    clearTimeout(confirmTimer);
    confirmTimer = null;
    deleteAllBtn.classList.remove('danger');
    deleteAllBtn.textContent = DELETE_ALL_LABEL;
  }

  removeBtn.addEventListener('click', () => {
    disarmDeleteAll();
    if (picking) {
      endPicking();
      setStatus('');
      return;
    }
    if (!listEl.querySelector('.saved-row')) {
      setStatus('Nothing saved yet.');
      return;
    }
    picking = true;
    section.classList.add('picking');
    removeBtn.classList.add('selected');
    setStatus('Tap songs to mark them, then press Delete.');
  });

  deleteBtn.addEventListener('click', () => {
    const marked = Array.from(listEl.querySelectorAll('.saved-row.marked'))
      .map((r) => r.dataset.id);
    if (!marked.length) {
      setStatus('Nothing marked yet.');
      return;
    }
    const removed = hasBridge ? AndroidMedia.deleteSavedLyrics(JSON.stringify(marked)) : 0;
    endPicking();
    render();
    refreshSaveButton();
    setStatus(`Deleted ${removed} song${removed === 1 ? '' : 's'}.`);
  });

  deleteAllBtn.addEventListener('click', () => {
    if (!listEl.querySelector('.saved-row')) {
      setStatus('Nothing saved yet.');
      return;
    }
    // First tap arms, second tap deletes. Letting it lapse puts the chip back, so a
    // stray tap never leaves it primed for the next one minutes later.
    if (confirmTimer === null) {
      endPicking();
      deleteAllBtn.classList.add('danger');
      deleteAllBtn.textContent = 'Tap again to delete all';
      setStatus('');
      confirmTimer = setTimeout(disarmDeleteAll, CONFIRM_MS);
      return;
    }
    disarmDeleteAll();
    const removed = hasBridge ? AndroidMedia.deleteAllSavedLyrics() : 0;
    render();
    refreshSaveButton();
    setStatus(`Deleted ${removed} song${removed === 1 ? '' : 's'}.`);
  });

  // nowplaying.js calls this after a successful save, so a song saved with Settings
  // closed is already in the list when it is opened. Merged rather than assigned —
  // the other scripts share this object.
  window.LenovoDock = Object.assign(window.LenovoDock || {}, {
    onSavedLyricsChanged: render,
  });

  render();
})();
