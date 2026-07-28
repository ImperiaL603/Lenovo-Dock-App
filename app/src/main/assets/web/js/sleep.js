/* ============================================================
   Sleep timer UI — pauses Spotify a set interval after being armed.

   The countdown itself is an AlarmManager alarm in SleepReceiver, not a timer
   here: it has to survive this page being backgrounded, throttled or killed,
   which is exactly the state the dock is in once you have fallen asleep.

   So native owns the armed state and this file mirrors it. Every path reads the
   deadline back from the bridge rather than assuming, which is what makes the
   toggle correct after a restart, or after the alarm fired while suspended.

   Only the chosen interval is stored on this side — it is a preference, not state.
   ============================================================ */
(function () {
  'use strict';

  const STORAGE_KEY = 'sleep-minutes';
  const DEFAULT_MIN = 30;

  const btn = document.getElementById('sleep-btn');
  const row = document.getElementById('sleep-row');
  const customInput = document.getElementById('sleep-custom-min');
  const customBtn = document.getElementById('sleep-custom-set');

  const bridge = () => (typeof AndroidMedia !== 'undefined' ? AndroidMedia : null);

  let minutes = parseInt(localStorage.getItem(STORAGE_KEY), 10) || DEFAULT_MIN;
  let deadline = 0; // epoch ms, 0 = disarmed; a mirror of native's value
  let uiTimer = null;

  function render() {
    btn.classList.toggle('armed', deadline > 0);
    btn.setAttribute('aria-label', deadline > 0
      ? `Sleep timer on, ${minutes} minutes`
      : `Sleep timer off, set to ${minutes} minutes`);
    // A custom interval matches no chip, which is what deselects them all.
    row.querySelectorAll('.option-chip').forEach((chip) => {
      chip.classList.toggle('selected', Number(chip.dataset.minutes) === minutes);
    });
  }

  // Cosmetic only — flips the button when the deadline passes. The pause itself
  // happens natively whether or not this page is alive to see it.
  function scheduleUiFlip() {
    if (uiTimer !== null) clearTimeout(uiTimer);
    uiTimer = deadline > 0
      ? setTimeout(sync, Math.max(0, deadline - Date.now()) + 500)
      : null;
  }

  function sync() {
    const b = bridge();
    deadline = b ? Number(b.sleepDeadline()) : 0;
    render();
    scheduleUiFlip();
  }

  function arm() {
    const b = bridge();
    if (!b) return;
    deadline = Number(b.armSleep(minutes));
    render();
    scheduleUiFlip();
  }

  function disarm() {
    const b = bridge();
    if (b) b.cancelSleep();
    deadline = 0;
    render();
    scheduleUiFlip();
  }

  function setMinutes(next) {
    if (!Number.isFinite(next) || next < 1) return;
    minutes = Math.round(next);
    localStorage.setItem(STORAGE_KEY, minutes);
    // Re-arm from now, so changing the interval mid-countdown means what it says.
    if (deadline > 0) arm(); else render();
  }

  btn.addEventListener('click', () => (deadline > 0 ? disarm() : arm()));

  row.addEventListener('click', (e) => {
    const m = e.target.dataset.minutes;
    if (!m) return;
    customInput.value = '';
    setMinutes(Number(m));
  });

  customBtn.addEventListener('click', () => setMinutes(parseInt(customInput.value, 10)));

  // Catches an alarm that fired while this page was suspended.
  document.addEventListener('visibilitychange', () => { if (!document.hidden) sync(); });

  sync();
})();
