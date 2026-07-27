/* ============================================================
   Spotify now-playing mode — fed by native media-session data
   (window.LenovoDock.onNowPlaying / onPlaybackGone / onLyrics).

   Owns: mode switching (play -> Spotify, ~30s grace -> Clock),
   pausing the wallpaper video, album art (one setter for thumb +
   blurred bg), song/artist, progress bar, and the scrolling
   lyrics list.
   ============================================================ */
window.LenovoDock = Object.assign(window.LenovoDock || {}, (function () {
  'use strict';

  const GRACE_MS = 30000;      // keep Spotify mode this long after playback stops
  const LYRIC_OFFSET_MS = 100; // show lyrics this far ahead of actual playback position
  const LYRIC_TICK_MS = 100;   // how often the active line is re-checked
  const NEAR = 3;              // lines either side of the active one that stay visible
  const SEEK_LINES = 8;        // a jump longer than this snaps instead of scrolling
  const GAP_MIN_MS = 7000;     // silence this long between two sung lines is an interlude
  const GAP_LEAD_MS = 3000;    // ...but let the preceding line hold this long first
  const GAP_MIN_SHOW_MS = 2500; // an interlude shorter than this isn't worth showing
  const GLYPH_PLAY = '▶'; // U+25B6 — old enough that Roboto covers it, so it stays text

  let np = null;          // latest snapshot
  let recvAt = 0;         // performance.now() when it arrived
  let spotifyActive = false;
  let graceTimer = null;
  let lastArt = null;
  let lastTrackKey = null;

  let lyrics = [];
  let lyricsIdx = -1;
  let lineEls = [];       // one element per lyric, index-aligned with `lyrics`
  let lineCenters = [];   // each line's centre inside the track, measured after layout
  let windowCenter = 0;
  let track = null;       // the single element that scrolls

  const $ = (id) => document.getElementById(id);
  const els = {};
  function cacheEls() {
    ['spotify-mode', 'spotify-bg', 'album-art-img', 'song-name', 'artist-names',
     'playlist-name', 'progress-elapsed', 'progress-total', 'progress-fill', 'bg-video',
     'lyrics-window', 'ctrl-prev', 'ctrl-playpause', 'ctrl-next']
      .forEach((id) => { els[id] = $(id); });
  }

  // ---- API called from native ----
  function onNowPlaying(data) {
    np = data;
    recvAt = performance.now();
    // The new track's lyrics arrive one network fetch later, so drop the old
    // ones now — otherwise the previous song's words track the new song's clock.
    const key = `${data.title || ''}|${data.artist || ''}`;
    if (data.title && key !== lastTrackKey) {
      lastTrackKey = key;
      buildLyrics([]);
    }
    renderMeta(data);
    if (data.playing) {
      cancelExit();
      if (!spotifyActive) enterSpotify();
    } else if (spotifyActive) {
      scheduleExit();
    }
    tick();
    lyricTick();
  }

  function onPlaybackGone() {
    if (np) np.playing = false;
    renderPlayState(false);
    if (spotifyActive) scheduleExit();
  }

  // Play is a glyph, pause is drawn by CSS: every pause codepoint either falls
  // through to the colour-emoji font (U+23F8) or sits at a weight that swamps the
  // ⏮ ⏭ beside it (U+275A). Two boxes match their weight exactly.
  function renderPlayState(playing) {
    const btn = els['ctrl-playpause'];
    btn.classList.toggle('is-playing', playing);
    btn.textContent = playing ? '' : GLYPH_PLAY;
  }

  // The glyph is deliberately NOT flipped on tap. Spotify pushes a new playback
  // state within ~100ms, so letting that drive the icon means it can never
  // disagree with what is actually happening — an optimistic flip would lie
  // whenever a command is rejected (ad playing, session torn down).
  function wireControls() {
    if (typeof AndroidMedia === 'undefined') return; // page opened outside the native shell
    els['ctrl-prev'].addEventListener('click', () => AndroidMedia.skipToPrevious());
    els['ctrl-playpause'].addEventListener('click', () => AndroidMedia.togglePlayPause());
    els['ctrl-next'].addEventListener('click', () => AndroidMedia.skipToNext());
  }

  // ---- lyrics ----
  // Every line is rendered once into a tall track; advancing, jumping several
  // lines at once and seeking are all the same operation — move the track so the
  // active line sits at the window's centre. Line spacing comes from normal flow,
  // so a lyric that wraps to two lines pushes its neighbours instead of overlapping.
  function onLyrics(data) {
    const lines = Array.isArray(data) ? data : [];
    if (!lines.length) { showLyricsMessage('No lyrics found'); return; }
    buildLyrics(lines);
    // Lyrics can land mid-song (cold start, slow fetch): open on the right line,
    // and without a scroll animation, since there is nothing to scroll from.
    setActive(firstIndexAt(lyricPos()), false);
  }

  // Interludes become first-class items on the timeline rather than a special
  // display state, so activating and scrolling to one needs no extra code path.
  // lrclib gives start times only, so a line's end is the next line's start.
  function withGaps(lines) {
    const items = [];
    const gap = (t, endT) => { if (endT - t >= GAP_MIN_SHOW_MS) items.push({ t, endT, gap: true }); };

    if (lines.length) gap(0, lines[0].t); // intro
    lines.forEach((l, i) => {
      const nextT = i + 1 < lines.length ? lines[i + 1].t : null;
      // An empty timed line is the LRC convention for an instrumental break.
      if (!l.text) { if (nextT !== null) gap(l.t, nextT); return; }
      items.push({ t: l.t, text: l.text });
      if (nextT !== null && nextT - l.t >= GAP_MIN_MS) gap(l.t + GAP_LEAD_MS, nextT);
    });
    return items;
  }

  function buildLyrics(lines) {
    lyrics = withGaps(lines); // timeline of what gets shown: sung lines and interludes
    lyricsIdx = -1;
    els['lyrics-window'].innerHTML = '';
    track = document.createElement('div');
    track.className = 'lyrics-track';
    lineEls = lyrics.map((item) => {
      const el = document.createElement('div');
      el.className = item.gap ? 'lyric-line lyric-gap' : 'lyric-line';
      if (item.gap) {
        for (let i = 0; i < 3; i++) {
          const dot = document.createElement('i');
          dot.style.setProperty('--i', i);
          el.appendChild(dot);
        }
      } else {
        el.textContent = item.text;
      }
      track.appendChild(el);
      return el;
    });
    els['lyrics-window'].appendChild(track);
    measure();
  }

  // Sits in the window rather than the track: it must stay centred, and the
  // track's position is only meaningful when there are lines to scroll through.
  function showLyricsMessage(text) {
    buildLyrics([]);
    const el = document.createElement('div');
    el.className = 'lyrics-message';
    el.textContent = text;
    els['lyrics-window'].appendChild(el);
  }

  // Reading offsetTop forces layout, so this runs only when geometry can actually
  // have changed: a new track, entering the mode, a rotation, or a late webfont.
  function measure() {
    if (!track) return;
    windowCenter = els['lyrics-window'].clientHeight / 2;
    lineCenters = lineEls.map((el) => el.offsetTop + el.offsetHeight / 2);
  }

  function firstIndexAt(pos) {
    let idx = -1;
    for (let i = 0; i < lyrics.length && lyrics[i].t <= pos; i++) idx = i;
    return idx;
  }

  function setActive(idx, animate) {
    lyricsIdx = idx;
    for (let i = 0; i < lineEls.length; i++) {
      const d = Math.abs(i - idx);
      lineEls[i].style.setProperty('--d', Math.min(d, NEAR));
      lineEls[i].classList.toggle('is-active', i === idx);
      lineEls[i].classList.toggle('is-far', d > NEAR);
    }
    scrollToLine(Math.max(0, idx), animate);
  }

  function scrollToLine(idx, animate) {
    if (!lineCenters.length) return;
    const y = windowCenter - lineCenters[Math.min(idx, lineCenters.length - 1)];
    if (!animate) track.classList.add('no-anim');
    track.style.transform = `translateY(${y}px)`;
    if (!animate) {
      void track.offsetWidth; // land the jump before transitions are allowed back on
      track.classList.remove('no-anim');
    }
  }

  function updateLyrics(pos) {
    if (!lyrics.length) return;
    const idx = firstIndexAt(pos);
    if (idx === lyricsIdx) return;
    // A scroll across half the song reads as a glitch; a seek should just arrive.
    setActive(idx, Math.abs(idx - lyricsIdx) <= SEEK_LINES);
  }

  // Drives the interlude dots: --p runs 0 -> 1 across the gap, and CSS lights
  // the three dots in turn off it, so the countdown to the next line is visible.
  function paintGap(pos) {
    const item = lyrics[lyricsIdx];
    if (!item || !item.gap) return;
    const span = item.endT - item.t;
    const p = span > 0 ? Math.min(1, Math.max(0, (pos - item.t) / span)) : 1;
    lineEls[lyricsIdx].style.setProperty('--p', p.toFixed(3));
  }

  // ---- mode switching ----
  function enterSpotify() {
    spotifyActive = true;
    document.body.classList.add('mode-spotify');
    if (els['bg-video']) els['bg-video'].pause();
    // #spotify-mode is display:none until now, so anything measured before this
    // point measured zero. Re-measure and re-seat the active line.
    measure();
    scrollToLine(Math.max(0, lyricsIdx), false);
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
    renderPlayState(d.playing);
  }

  function interpolatedPos() {
    if (!np) return 0;
    const drift = np.playing ? (performance.now() - recvAt) * (np.speed || 1) : 0;
    const pos = np.positionMs + drift;
    // Clamp only against a duration we actually have. Spotify reports 0 while
    // metadata settles, and min(0, pos) pinned the position at zero — which
    // freezes the lyrics wherever they had reached and never recovers.
    return np.durationMs > 0 ? Math.min(np.durationMs, pos) : pos;
  }

  const lyricPos = () => interpolatedPos() + LYRIC_OFFSET_MS;

  function tick() {
    if (!spotifyActive || !np) return;
    const pos = interpolatedPos();
    els['progress-elapsed'].textContent = fmt(pos);
    els['progress-fill'].style.width =
      (np.durationMs > 0 ? (pos / np.durationMs) * 100 : 0) + '%';
  }

  // Split from tick(): the progress bar wants a 1s beat to match its 1s linear
  // fill, but at 1s a line can land a full second late — or two lines can pass
  // inside one tick, skipping a line outright.
  function lyricTick() {
    if (!spotifyActive || !np) return;
    const pos = lyricPos();
    updateLyrics(pos);
    paintGap(pos);
  }

  function fmt(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  // Line positions are read from layout, so anything that moves them invalidates
  // the cache: a rotation, and the webfont arriving after the first render.
  function remeasure() {
    if (!lineEls.length) return;
    measure();
    scrollToLine(Math.max(0, lyricsIdx), false);
  }

  cacheEls();
  wireControls();
  window.addEventListener('resize', remeasure);
  if (document.fonts) document.fonts.ready.then(remeasure);
  setInterval(tick, 1000);              // CSS bridges each beat with a 1s linear fill
  setInterval(lyricTick, LYRIC_TICK_MS);

  return { onNowPlaying, onPlaybackGone, onLyrics };
})());
