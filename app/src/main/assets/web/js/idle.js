/* ============================================================
   IDLE — dims the floating controls after a spell without touches.

   Cross-cutting on purpose: it drives three buttons whose styles live in two
   different stylesheets and listens on the document, so it belongs to neither
   script.js (alarms + settings wiring) nor sleep.js (sleep-timer domain).

   The buttons stay tappable while dimmed. This file only ever sets a class —
   whether that class dims, hides or disables anything is idle.css's business.
   ============================================================ */

const IDLE_MS = 15000;

let idleTimer = 0;

function wake() {
  document.body.classList.remove('idle');
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => document.body.classList.add('idle'), IDLE_MS);
}

// Capture phase: a tap on a control that stops propagation still counts as use.
// pointerdown rather than click so the controls come back on touch-down instead
// of waiting for the finger to lift.
document.addEventListener('pointerdown', wake, true);

// Nothing suppresses the timer while the settings panel is open — idle.css
// ignores the class in that case. Keeping the rule in one place means the timer
// can't be left armed-but-forgotten, and closing the panel needs no re-arming.
wake();
