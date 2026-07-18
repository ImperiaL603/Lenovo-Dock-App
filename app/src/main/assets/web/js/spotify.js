/* ============================================================
   Spotify now-playing mode — fed by native media-session data
   (window.LenovoDock.onNowPlaying / onPlaybackGone).

   Owns: mode switching (play -> Spotify, ~30s grace -> Clock),
   pausing the wallpaper video, album art (one setter for thumb +
   blurred bg), song/artist, and the progress bar. Transport controls
   and lyrics are wired in later steps.
   ============================================================ */
window.LenovoDock = (function () {
  'use strict';

  const GRACE_MS = 30000; // keep Spotify mode this long after playback stops

  let np = null;          // latest snapshot
  let recvAt = 0;         // performance.now() when it arrived
  let spotifyActive = false;
  let graceTimer = null;
  let lastArt = null;

  const $ = (id) => document.getElementById(id);
  const els = {};
  function cacheEls() {
    ['spotify-mode', 'spotify-bg', 'album-art-img', 'song-name', 'artist-names',
     'playlist-name', 'progress-elapsed', 'progress-total', 'progress-fill', 'bg-video']
      .forEach((id) => { els[id] = $(id); });
  }

  // ---- API called from native ----
  function onNowPlaying(data) {
    np = data;
    recvAt = performance.now();
    renderMeta(data);
    if (data.playing) {
      cancelExit();
      if (!spotifyActive) enterSpotify();
    } else if (spotifyActive) {
      scheduleExit();
    }
    tick();
  }

  function onPlaybackGone() {
    if (np) np.playing = false;
    if (spotifyActive) scheduleExit();
  }

  // ---- mode switching ----
  function enterSpotify() {
    spotifyActive = true;
    document.body.classList.add('mode-spotify');
    if (els['bg-video']) els['bg-video'].pause();
  }

  function exitToClock() {
    spotifyActive = false;
    document.body.classList.remove('mode-spotify');
    if (els['bg-video']) els['bg-video'].play().catch(() => {});
  }

  function scheduleExit() {
    cancelExit();
    graceTimer = setTimeout(exitToClock, GRACE_MS);
  }

  function cancelExit() {
    if (graceTimer) { clearTimeout(graceTimer); graceTimer = null; }
  }

  // ---- rendering ----
  function setArt(url) {
    if (url === lastArt) return;
    lastArt = url;
    const mode = els['spotify-mode'];
    if (!url) { mode.classList.remove('has-art'); return; }
    // Reveal art (and hide the placeholder) only once it has actually loaded,
    // so a cold fetch shows the placeholder instead of a blank box.
    const img = els['album-art-img'];
    img.onload = () => mode.classList.add('has-art');
    img.src = url;
    els['spotify-bg'].src = url;
  }

  function renderMeta(d) {
    setArt(d.art);
    els['song-name'].textContent = d.title || '';
    els['artist-names'].textContent = d.artist || '';
    els['playlist-name'].textContent = d.playlistName || '';
    els['playlist-name'].classList.toggle('visible', !!d.playlistName);
    els['progress-total'].textContent = fmt(d.durationMs);
  }

  function interpolatedPos() {
    if (!np) return 0;
    const drift = np.playing ? (performance.now() - recvAt) * (np.speed || 1) : 0;
    return Math.min(np.durationMs, np.positionMs + drift);
  }

  function tick() {
    if (!spotifyActive || !np) return;
    const pos = interpolatedPos();
    els['progress-elapsed'].textContent = fmt(pos);
    els['progress-fill'].style.width =
      (np.durationMs > 0 ? (pos / np.durationMs) * 100 : 0) + '%';
  }

  function fmt(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  cacheEls();
  setInterval(tick, 1000); // local ticks; CSS bridges each with a 1s linear fill

  return { onNowPlaying, onPlaybackGone };
})();
