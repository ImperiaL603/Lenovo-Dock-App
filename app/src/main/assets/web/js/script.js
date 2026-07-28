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

// ---------- Alarms / Timers panel ----------
const clockMode = document.getElementById('clock-mode');
const addBtn = document.getElementById('add-btn');
const alarmsListEl = document.getElementById('alarms-list');
const timersListEl = document.getElementById('timers-list');

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']; // index 0..6 -> Calendar.SUNDAY(1)..SATURDAY(7)

let alarms = [];
let timers = [];
let newAlarmDays = new Set();

addBtn.addEventListener('click', () => {
  clockMode.classList.toggle('panel-open');
});

function bridgeAvailable() {
  return typeof window.AndroidAlarms !== 'undefined';
}

function fmtAlarmTime(hour, minute) {
  const ampm = hour >= 12 ? 'PM' : 'AM';
  let h = hour % 12; if (h === 0) h = 12;
  return `${h}:${minute.toString().padStart(2, '0')} ${ampm}`;
}

function fmtCountdown(endEpochMillis) {
  const remainingMs = Math.max(0, endEpochMillis - Date.now());
  const totalSec = Math.ceil(remainingMs / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  return h > 0
    ? `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
    : `${m}:${s.toString().padStart(2, '0')}`;
}

function renderAlarms() {
  if (!alarms.length) {
    alarmsListEl.innerHTML = '<div class="placeholder-row">No alarms set</div>';
    return;
  }
  alarmsListEl.innerHTML = alarms.map(a => `
    <div class="alarm-row" data-id="${a.id}">
      <div class="alarm-info">
        <span class="alarm-time">${fmtAlarmTime(a.hour, a.minute)}</span>
        ${a.label ? `<span class="alarm-label">${a.label}</span>` : ''}
        ${a.days.length ? `<span class="alarm-days">${a.days.map(d => DAY_LABELS[d % 7]).join(' ')}</span>` : '<span class="alarm-days">Once</span>'}
      </div>
      <button class="row-delete-btn" data-delete-alarm="${a.id}" aria-label="Delete alarm">&times;</button>
    </div>
  `).join('');
}

function renderTimers() {
  if (!timers.length) {
    timersListEl.innerHTML = '<div class="placeholder-row">No timers running</div>';
    return;
  }
  timersListEl.innerHTML = timers.map(t => {
    const pct = Math.min(100, 100 - ((t.endEpochMillis - Date.now()) / (t.durationSeconds * 1000)) * 100);
    return `
    <div class="timer-row" data-id="${t.id}">
      <div class="timer-info">
        ${t.label ? `<span class="timer-label">${t.label}</span>` : ''}
        <span class="timer-countdown">${fmtCountdown(t.endEpochMillis)}</span>
      </div>
      <div class="timer-progress-track"><div class="timer-progress-fill" style="width:${pct}%"></div></div>
      <button class="row-delete-btn" data-cancel-timer="${t.id}" aria-label="Cancel timer">&times;</button>
    </div>`;
  }).join('');
}

alarmsListEl.addEventListener('click', (e) => {
  const id = e.target.getAttribute('data-delete-alarm');
  if (id && bridgeAvailable()) {
    window.AndroidAlarms.deleteAlarm(id);
    alarms = alarms.filter(a => a.id !== id);
    renderAlarms();
  }
});

timersListEl.addEventListener('click', (e) => {
  const id = e.target.getAttribute('data-cancel-timer');
  if (id && bridgeAvailable()) {
    window.AndroidAlarms.cancelTimer(id);
    timers = timers.filter(t => t.id !== id);
    renderTimers();
  }
});

// ---------- New alarm/timer creation forms ----------
const newAlarmForm = document.getElementById('new-alarm-form');
const newTimerForm = document.getElementById('new-timer-form');
const toggleAlarmFormBtn = document.getElementById('toggle-alarm-form');
const toggleTimerFormBtn = document.getElementById('toggle-timer-form');

toggleAlarmFormBtn.addEventListener('click', () => newAlarmForm.classList.toggle('open'));
toggleTimerFormBtn.addEventListener('click', () => newTimerForm.classList.toggle('open'));

document.querySelectorAll('.day-chip').forEach(chip => {
  chip.addEventListener('click', () => {
    const day = parseInt(chip.getAttribute('data-day'), 10);
    if (newAlarmDays.has(day)) { newAlarmDays.delete(day); chip.classList.remove('selected'); }
    else { newAlarmDays.add(day); chip.classList.add('selected'); }
  });
});

document.getElementById('save-alarm-btn').addEventListener('click', () => {
  const timeInput = document.getElementById('new-alarm-time').value; // "HH:MM"
  const label = document.getElementById('new-alarm-label').value.trim();
  if (!timeInput || !bridgeAvailable()) return;
  const [hour, minute] = timeInput.split(':').map(Number);
  const json = JSON.stringify({ hour, minute, days: Array.from(newAlarmDays), label });
  const created = JSON.parse(window.AndroidAlarms.addAlarm(json));
  alarms.push(created);
  renderAlarms();
  newAlarmForm.classList.remove('open');
  document.getElementById('new-alarm-label').value = '';
  newAlarmDays.clear();
  document.querySelectorAll('.day-chip.selected').forEach(c => c.classList.remove('selected'));
});

document.getElementById('save-timer-btn').addEventListener('click', () => {
  const minutesInput = parseInt(document.getElementById('new-timer-minutes').value, 10);
  const label = document.getElementById('new-timer-label').value.trim();
  if (!minutesInput || minutesInput <= 0 || !bridgeAvailable()) return;
  const json = JSON.stringify({ durationSeconds: minutesInput * 60, label });
  const created = JSON.parse(window.AndroidAlarms.addTimer(json));
  timers.push(created);
  renderTimers();
  newTimerForm.classList.remove('open');
  document.getElementById('new-timer-label').value = '';
  document.getElementById('new-timer-minutes').value = '';
});

// ---------- Native bridge hooks (merge, don't overwrite — spotify.js also uses window.LenovoDock) ----------
window.LenovoDock = Object.assign(window.LenovoDock || {}, {
  onAlarmsChanged(list) { alarms = list; renderAlarms(); },
  onTimerTick(list) { timers = list; renderTimers(); },
  onAlbumColour(rgb) { albumRgb = rgb; applyColourSource(); }
});

// Initial load from native (in case onPageFinished's push races with script load)
if (bridgeAvailable()) {
  try { alarms = JSON.parse(window.AndroidAlarms.listAlarms()); renderAlarms(); } catch (e) {}
  try { timers = JSON.parse(window.AndroidAlarms.listTimers()); renderTimers(); } catch (e) {}
}

// ---------- Settings panel toggle ----------
const settingsBtn = document.getElementById('settings-btn');
settingsBtn.addEventListener('click', () => {
  document.body.classList.toggle('settings-open');
});

// Section collapse. Each .settings-section-label toggles the element right after
// it, so adding a section needs no JS. aria-expanded is the state the chevron
// rotates off, so the attribute and the visible arrow can't drift apart.
document.querySelectorAll('.settings-section-label').forEach((label) => {
  label.addEventListener('click', () => {
    const open = label.nextElementSibling.classList.toggle('open');
    label.setAttribute('aria-expanded', String(open));
  });
});

// ---------- Appearance sizes ----------
// Scales whole modes: --clock-scale and --spotify-scale are read by transforms
// on .clock-block, .spotify-layout, .progress-row and .spotify-controls.
const SCALES = { small: 0.9, medium: 1, large: 1.1 };
const DEFAULT_SIZE = 'medium';

function applySize(mode, size) {
  document.documentElement.style.setProperty(`--${mode}-scale`, SCALES[size]);
  localStorage.setItem(`size-${mode}`, size);
  document.querySelectorAll(`.option-row[data-scale="${mode}"] .option-chip`)
    .forEach((chip) => chip.classList.toggle('selected', chip.dataset.size === size));
}

document.querySelectorAll('.option-row[data-scale]').forEach((row) => {
  const mode = row.dataset.scale;
  const saved = localStorage.getItem(`size-${mode}`);
  applySize(mode, SCALES[saved] ? saved : DEFAULT_SIZE);
  row.addEventListener('click', (e) => {
    const size = e.target.dataset.size;
    if (size) applySize(mode, size);
  });
});

// ---------- Theme ----------
// The id is the whole state: themes.css keys off :root[data-theme], so setting
// the attribute repaints everything that derives from --fg-rgb / --accent-rgb.
const themeRow = document.getElementById('theme-row');
const DEFAULT_THEME = 'mono';

function applyTheme(id) {
  document.documentElement.setAttribute('data-theme', id);
  localStorage.setItem('theme', id);
  themeRow.querySelectorAll('.option-chip')
    .forEach((chip) => chip.classList.toggle('selected', chip.dataset.theme === id));
}

const savedTheme = localStorage.getItem('theme');
applyTheme(themeRow.querySelector(`[data-theme="${savedTheme}"]`) ? savedTheme : DEFAULT_THEME);

themeRow.addEventListener('click', (e) => {
  const id = e.target.dataset.theme;
  if (id) applyTheme(id);
});

// ---------- Album colours ----------
// Inline custom properties outrank themes.css's :root[data-theme] rules, so the
// presence of an inline override IS "the album is driving". Clearing it hands the
// dock back to the selected theme, which is why there's no second piece of state
// tracking which source is active and no way for the two to disagree.
const albumRow = document.getElementById('albumcolour-row');
const ACCENT_SHARE = 0.4; // themes.css blends 60% light text + 40% accent for --fg-rgb

let albumOn = localStorage.getItem('album-colours') === 'on';
let albumRgb = null;      // "r g b" from native, or null when there's no art

function applyColourSource() {
  const root = document.documentElement;
  if (albumOn && albumRgb) {
    root.style.setProperty('--accent-rgb', albumRgb);
    root.style.setProperty('--fg-rgb', towardsWhite(albumRgb));
  } else {
    root.style.removeProperty('--accent-rgb');
    root.style.removeProperty('--fg-rgb');
  }
}

// A colour straight off a sleeve is far too saturated to read as body type across a
// room, so --fg-rgb gets the same treatment the ported themes give theirs.
function towardsWhite(rgb) {
  return rgb.split(' ')
    .map((c) => Math.round(255 * (1 - ACCENT_SHARE) + Number(c) * ACCENT_SHARE))
    .join(' ');
}

function applyAlbumColours(on) {
  albumOn = on;
  localStorage.setItem('album-colours', on ? 'on' : 'off');
  albumRow.querySelectorAll('.option-chip')
    .forEach((chip) => chip.classList.toggle('selected', (chip.dataset.album === 'on') === on));
  applyColourSource();
}

applyAlbumColours(albumOn);

albumRow.addEventListener('click', (e) => {
  const v = e.target.dataset.album;
  if (v) applyAlbumColours(v === 'on');
});

// ---------- Display: auto-dim ----------
// Native owns the sensor, the curve and the window brightness; the page owns only
// the preference and pushes it down. Both fields travel in one call, so there is no
// moment where the toggle has been applied and the floor hasn't.
const autodimRow = document.getElementById('autodim-row');
const dimfloorRow = document.getElementById('dimfloor-row');
const DEFAULT_DIM_FLOOR = '15';

let dimOn = localStorage.getItem('autodim') === 'on';
let dimFloor = localStorage.getItem('autodim-floor');
if (!dimfloorRow.querySelector(`[data-floor="${dimFloor}"]`)) dimFloor = DEFAULT_DIM_FLOOR;

// The only writer of dimOn/dimFloor, so chips, localStorage and native can't drift.
function applyAutoDim(on, floor) {
  dimOn = on;
  dimFloor = floor;
  localStorage.setItem('autodim', on ? 'on' : 'off');
  localStorage.setItem('autodim-floor', floor);
  autodimRow.querySelectorAll('.option-chip')
    .forEach((chip) => chip.classList.toggle('selected', (chip.dataset.autodim === 'on') === on));
  dimfloorRow.querySelectorAll('.option-chip')
    .forEach((chip) => chip.classList.toggle('selected', chip.dataset.floor === floor));
  if (typeof AndroidDisplay !== 'undefined') AndroidDisplay.setAutoDim(on, parseInt(floor, 10));
}

applyAutoDim(dimOn, dimFloor);

autodimRow.addEventListener('click', (e) => {
  const v = e.target.dataset.autodim;
  if (v) applyAutoDim(v === 'on', dimFloor);
});

dimfloorRow.addEventListener('click', (e) => {
  const f = e.target.dataset.floor;
  if (f) applyAutoDim(dimOn, f);
});