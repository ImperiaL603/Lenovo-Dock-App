/* ============================================================
   TEMPORARY — step 2a.
   Receives now-playing snapshots injected by the native side and
   dumps them (with interpolated position) into #np-debug so we can
   confirm the media-session pipeline works. Replaced by the real
   Spotify-mode UI in step 2b.
   ============================================================ */
window.LenovoDock = (function () {
  'use strict';

  let state = null;
  let recvAt = 0; // performance.now() when the last snapshot arrived

  const el = () => document.getElementById('np-debug');

  function onNowPlaying(np) {
    state = np;
    recvAt = performance.now();
    render();
  }

  function onPlaybackGone() {
    state = null;
    render();
  }

  function interpolatedPos() {
    if (!state) return 0;
    const drift = state.playing ? (performance.now() - recvAt) * (state.speed || 1) : 0;
    return Math.min(state.durationMs, state.positionMs + drift);
  }

  function fmt(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  function render() {
    const node = el();
    if (!node) return;
    if (!state) {
      node.textContent = 'np: (no active Spotify playback)';
      return;
    }
    node.textContent =
      `${state.playing ? '▶' : '⏸'} ${state.title} — ${state.artist}\n` +
      `album: ${state.album}\n` +
      `${fmt(interpolatedPos())} / ${fmt(state.durationMs)}  speed:${state.speed}\n` +
      `hasArt:${state.hasArt}  artBytes:${state.art ? state.art.length : 0}`;
  }

  setInterval(render, 250); // smooth position ticking

  return { onNowPlaying, onPlaybackGone };
})();
