/* ============================================================
   Clock dashboard — clock, alarm/timer panel, settings panel.
   ============================================================ */

// ---------- Clock ----------
const dayEl = document.getElementById('clock-day');
const dateEl = document.getElementById('clock-date');
const hmEl = document.getElementById('clock-hm');
const ampmEl = document.getElementById('clock-ampm');

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
                'July', 'August', 'September', 'October', 'November', 'December'];

function updateClock() {
  const now = new Date();

  dayEl.textContent = DAYS[now.getDay()];
  dateEl.textContent = `${now.getDate()} ${MONTHS[now.getMonth()]} ${now.getFullYear()}`;

  let hours = now.getHours();
  const minutes = now.getMinutes().toString().padStart(2, '0');
  const ampm = hours >= 12 ? 'PM' : 'AM';
  hours = hours % 12;
  if (hours === 0) hours = 12;

  hmEl.textContent = `${hours}:${minutes}`;
  ampmEl.textContent = ampm;
}

updateClock();
setInterval(updateClock, 1000);

// ---------- Alarms / Timers panel toggle ----------
const clockMode = document.getElementById('clock-mode');
const addBtn = document.getElementById('add-btn');

addBtn.addEventListener('click', () => {
  clockMode.classList.toggle('panel-open');
  // TODO: a second tap (button rotated into an "x") should open a form to
  // create a new alarm/timer.
});

// ---------- Settings panel toggle ----------
const settingsBtn = document.getElementById('settings-btn');

settingsBtn.addEventListener('click', () => {
  document.body.classList.toggle('settings-open');
});
