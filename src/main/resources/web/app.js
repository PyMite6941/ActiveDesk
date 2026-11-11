'use strict';
// ActiveDesk web client. All page text from the network is inserted with textContent (never innerHTML),
// and the session token lives in sessionStorage only (gone when the tab/app is closed).

const $ = (id) => document.getElementById(id);
let token = sessionStorage.getItem('ad_token') || '';
let state = null;
let currentTab = 'screen';

// ------------------------------------------------------------------ api

async function api(path, opts = {}) {
  const headers = Object.assign({}, opts.headers || {});
  if (token) headers['Authorization'] = 'Bearer ' + token;
  let body = opts.body;
  if (opts.json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.json); }
  const res = await fetch(path, { method: opts.method || (body ? 'POST' : 'GET'), headers, body, cache: 'no-store' });
  if (res.status === 401) { showLogin(); throw new Error('login required'); }
  if (!res.ok) {
    let msg = res.status + '';
    try { msg = (await res.json()).error || msg; } catch (e) { /* not json */ }
    throw new Error(msg);
  }
  return res;
}
const getJson = async (p) => (await api(p)).json();
const postJson = async (p, data) => (await api(p, { json: data })).json();

function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.add('hidden'), 3500);
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function button(text, onClick, cls) {
  const b = el('button', cls || '', text);
  b.type = 'button';
  b.addEventListener('click', onClick);
  return b;
}

const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const fmtSize = (n) => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB'
  : n < 1073741824 ? (n / 1048576).toFixed(1) + ' MB' : (n / 1073741824).toFixed(2) + ' GB';

// ------------------------------------------------------------------ login

function showLogin() {
  token = '';
  sessionStorage.removeItem('ad_token');
  $('app').classList.add('hidden');
  $('login').classList.remove('hidden');
  $('pin').focus();
}

$('loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('loginError').textContent = '';
  try {
    const res = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: $('pin').value }) });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'failed');
    token = data.token;
    sessionStorage.setItem('ad_token', token);
    $('pin').value = '';
    start();
  } catch (err) {
    $('loginError').textContent = err.message;
  }
});

$('logout').addEventListener('click', async () => {
  try { await api('/api/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
  showLogin();
});

// ------------------------------------------------------------------ tabs + state

document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));

function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('hidden', t.id !== 'tab-' + tab));
  if (tab === 'screen') screenLoop();
  if (tab === 'files') loadFiles();
  if (tab === 'tasks') loadTasks();
  if (tab === 'devices') renderDevices();
  if (tab === 'power') renderPower();
  if (tab === 'chat') loadChat(true);
}

async function refreshState() {
  try {
    state = await getJson('/api/state');
    $('deviceName').textContent = state.device;
    document.title = 'ActiveDesk - ' + state.device;
    $('rdpBtn').classList.toggle('hidden', !state.rdp);
    if (document.activeElement !== $('awake') && state.keepAwake) $('awake').value = state.keepAwake;
    fillTargets();
    if (currentTab === 'devices') renderDevices();
    if (currentTab === 'power') renderPower();
    renderNoScreen();
  } catch (e) { /* handled in api() */ }
}

function start() {
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
  refreshState().then(() => switchTab(currentTab));
  clearInterval(start.timer);
  start.timer = setInterval(() => {
    if (!token) return;
    refreshState();
    if (currentTab === 'chat') loadChat(false);
    if (currentTab === 'files') loadFiles();
    if (currentTab === 'tasks') loadTasks();
  }, 3000);
}

// ------------------------------------------------------------------ screen

let screenBusy = false;
let lastUrl = null;

// Keep the phone/laptop you are controlling FROM awake while it shows the remote screen.
// Browsers only allow this in a secure context (HTTPS, e.g. via `tailscale serve`), so it is best effort.
let localLock = null;
async function holdLocalAwake(on) {
  try {
    if (on && !localLock && 'wakeLock' in navigator && window.isSecureContext) {
      localLock = await navigator.wakeLock.request('screen');
      localLock.addEventListener('release', () => { localLock = null; });
    } else if (!on && localLock) {
      await localLock.release();
      localLock = null;
    }
  } catch (e) { localLock = null; /* denied (battery saver etc.) - not fatal */ }
}

async function screenLoop() {
  if (screenBusy) return;
  screenBusy = true;
  holdLocalAwake(true);
  const msg = $('screenMsg');
  while (token && currentTab === 'screen' && !document.hidden) {
    if (state && !state.screen) {
      renderNoScreen();
      await sleep(3000);
      continue;
    }
    const [w, q] = $('quality').value.split(':');
    try {
      // Where this browser window is on its screen. If it is showing the computer it runs on, the server
      // blanks that area, otherwise the picture would contain itself over and over (infinity mirror).
      const self = [window.screenX, window.screenY, window.outerWidth, window.outerHeight].map(Math.round).join(',');
      const res = await api('/api/screen?w=' + w + '&q=' + q + '&self=' + self);
      const url = URL.createObjectURL(await res.blob());
      await new Promise((ok) => { $('screen').onload = ok; $('screen').onerror = ok; $('screen').src = url; });
      if (lastUrl) URL.revokeObjectURL(lastUrl);
      lastUrl = url;
      msg.classList.add('hidden');
    } catch (e) {
      msg.textContent = 'Screen unavailable: ' + e.message;
      msg.classList.remove('hidden');
      await sleep(2000);
    }
  }
  screenBusy = false;
  holdLocalAwake(false);
}
// No screen (headless Pi, server, locked-down session): the shell card below is the way in.
function renderNoScreen() {
  const none = !!state && !state.screen;
  $('noScreen').classList.toggle('hidden', !none);
  $('screenArea').classList.toggle('hidden', none);
  if (none) $('noScreenWhy').textContent = 'Reason: ' + (state.screenReason || 'no display');
  renderShell(none);
}

// Shell access for every device. Linux: Tailscale SSH (no passwords or keys). Windows and macOS can't be
// Tailscale SSH servers, so they use the OS's own SSH server (OpenSSH Server / Remote Login) over Tailscale.
let shellOpened = false;
function renderShell(noScreen) {
  if (!state) return;
  const os = state.os || '';
  const win = /win/i.test(os), mac = /mac|darwin/i.test(os);
  const user = state.user || 'you';
  const ip = state.selfIp || state.selfName || state.device;
  const cmd = win || mac ? 'ssh ' + user + '@' + ip : 'tailscale ssh ' + user + '@' + (state.selfName || ip);
  $('sshCmd').textContent = cmd;
  $('sshState').textContent = state.ssh ? '- an SSH server is running on this device' : '';
  const steps = win ? [
    ['Once, on this device, in PowerShell opened with "Run as administrator": ',
      'Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0; Set-Service sshd -StartupType Automatic; Start-Service sshd'],
    ['From another computer with Tailscale: paste the command above. Sign in with your Windows password (for a Microsoft account, its password, not the PIN).'],
    ['From a phone: any free SSH app (e.g. Termius) with host ', ip, ' and user ', user, '.'],
  ] : mac ? [
    ['Once, on this Mac: System Settings \u2192 General \u2192 Sharing \u2192 turn on Remote Login.'],
    ['From another computer with Tailscale: paste the command above and sign in with this Mac\u2019s password.'],
    ['From a phone: any free SSH app (e.g. Termius) with host ', ip, ' and user ', user, '.'],
  ] : [
    ['Once, on this device: ', 'sudo tailscale set --ssh'],
    ['From another computer with Tailscale: paste the command above. No password or keys: Tailscale checks it is you.'],
    ['From a phone: any free SSH app (e.g. Termius) with host ', ip, ' and user ', user, '.'],
  ];
  const ol = $('sshSteps');
  ol.replaceChildren(...steps.map((parts) => {
    const li = document.createElement('li');
    // odd-indexed parts are commands / values (shown as code), the rest is plain text; never HTML
    parts.forEach((p, i) => {
      if (i % 2) { const c = document.createElement('code'); c.textContent = p; li.append(c); }
      else li.append(document.createTextNode(p));
    });
    return li;
  }));
  if (noScreen && !shellOpened) { $('shellCard').open = true; shellOpened = true; }
}
$('sshCopy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('sshCmd').textContent); toast('Copied'); }
  catch (e) { toast('Select the command and copy it'); }
});

document.addEventListener('visibilitychange', () => { if (!document.hidden && currentTab === 'screen') screenLoop(); });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Input events are sent strictly in order through one promise chain.
let inputChain = Promise.resolve();
function send(ev) {
  inputChain = inputChain.then(() => api('/api/input', { json: ev })).catch((e) => toast(e.message));
  return inputChain;
}

function pos(e) {
  const r = $('screen').getBoundingClientRect();
  return { x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
           y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) };
}
const BUTTONS = ['left', 'middle', 'right'];

// --- mouse (computer) and touch (phone / tablet) through Pointer Events
const wrap = $('screenWrap');
const pointers = new Map();
let touch = null;          // single-finger gesture state
let scrollY = null;        // two-finger scroll state
let lastMove = 0;
let rightNext = false;

wrap.addEventListener('contextmenu', (e) => e.preventDefault());

wrap.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  wrap.focus({ preventScroll: true });
  if (e.pointerType === 'mouse') {
    wrap.setPointerCapture(e.pointerId);
    send(Object.assign({ kind: 'down', button: BUTTONS[e.button] || 'left' }, pos(e)));
    return;
  }
  pointers.set(e.pointerId, e);
  if (pointers.size === 2) {               // second finger: switch to scroll, cancel tap/drag
    if (touch && touch.dragging) send(Object.assign({ kind: 'up', button: 'left' }, pos(e)));
    clearTimeout(touch && touch.hold);
    touch = null;
    scrollY = avgY();
    return;
  }
  const p = pos(e);
  touch = { start: p, x0: e.clientX, y0: e.clientY, t0: Date.now(), dragging: false, held: false };
  touch.hold = setTimeout(() => {          // long press = right click
    if (touch && !touch.dragging) {
      touch.held = true;
      send(Object.assign({ kind: 'click', button: 'right' }, touch.start));
      if (navigator.vibrate) navigator.vibrate(30);
    }
  }, 550);
});

wrap.addEventListener('pointermove', (e) => {
  if (e.pointerType === 'mouse') {
    const now = Date.now();
    if (now - lastMove < 30) return;       // ~33 moves per second is plenty
    lastMove = now;
    send(Object.assign({ kind: 'move' }, pos(e)));
    return;
  }
  if (!pointers.has(e.pointerId)) return;
  pointers.set(e.pointerId, e);
  if (pointers.size >= 2 && scrollY !== null) {
    const y = avgY();
    const notches = Math.trunc((scrollY - y) / 25);
    if (notches !== 0) { send({ kind: 'scroll', amount: notches }); scrollY = y; }
    return;
  }
  if (!touch || touch.held) return;
  const moved = Math.hypot(e.clientX - touch.x0, e.clientY - touch.y0);
  if (!touch.dragging && moved > 12) {     // finger drag = mouse drag
    clearTimeout(touch.hold);
    touch.dragging = true;
    send(Object.assign({ kind: 'down', button: 'left' }, touch.start));
  }
  if (touch.dragging) {
    const now = Date.now();
    if (now - lastMove < 30) return;
    lastMove = now;
    send(Object.assign({ kind: 'move' }, pos(e)));
  }
});

function endPointer(e) {
  if (e.pointerType === 'mouse') {
    send(Object.assign({ kind: 'up', button: BUTTONS[e.button] || 'left' }, pos(e)));
    return;
  }
  pointers.delete(e.pointerId);
  if (pointers.size < 2) scrollY = null;
  if (!touch) return;
  clearTimeout(touch.hold);
  if (touch.dragging) send(Object.assign({ kind: 'up', button: 'left' }, pos(e)));
  else if (!touch.held && e.type === 'pointerup') {
    const quick = Date.now() - touch.t0 < 450;
    if (quick) {
      const btn = rightNext ? 'right' : 'left';
      send(Object.assign({ kind: 'click', button: btn }, touch.start));
      if (rightNext) { rightNext = false; $('rightBtn').classList.remove('on'); }
    }
  }
  touch = null;
}
wrap.addEventListener('pointerup', endPointer);
wrap.addEventListener('pointercancel', endPointer);

function avgY() {
  let s = 0;
  pointers.forEach((p) => { s += p.clientY; });
  return s / Math.max(1, pointers.size);
}

wrap.addEventListener('wheel', (e) => {
  e.preventDefault();
  const n = Math.sign(e.deltaY) * Math.max(1, Math.round(Math.abs(e.deltaY) / 100));
  send({ kind: 'scroll', amount: n });
}, { passive: false });

wrap.addEventListener('dblclick', (e) => e.preventDefault());

// --- physical keyboard (computer): forward while the picture has focus
const SPECIAL = { Enter: 'enter', Backspace: 'backspace', Tab: 'tab', Escape: 'esc', Delete: 'delete',
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Home: 'home', End: 'end',
  PageUp: 'pageup', PageDown: 'pagedown', Insert: 'insert', ' ': 'space' };

function chordFor(e) {
  let key = SPECIAL[e.key];
  if (!key && /^F([1-9]|1[0-2])$/.test(e.key)) key = e.key.toLowerCase();
  if (!key && e.key.length === 1 && /[a-z0-9]/i.test(e.key)) key = e.key.toLowerCase();
  if (!key) return null;
  const mods = [];
  if (e.ctrlKey) mods.push('ctrl');
  if (e.altKey) mods.push('alt');
  if (e.metaKey) mods.push('meta');
  if (e.shiftKey && (SPECIAL[e.key] || /^F/.test(e.key))) mods.push('shift');
  return mods.concat([key]).join('+');
}

wrap.addEventListener('keydown', (e) => {
  if (['Shift', 'Control', 'Alt', 'Meta', 'CapsLock'].includes(e.key)) return;
  e.preventDefault();
  const hasMod = e.ctrlKey || e.altKey || e.metaKey;
  if (!hasMod && e.key.length === 1) { send({ kind: 'type', text: e.key }); return; }
  const chord = chordFor(e);
  if (chord) send({ kind: 'key', key: chord });
});

// --- on-screen keyboard (phone): a hidden textarea with a sentinel so Backspace is visible even when empty
const kbd = $('kbd');
const SENTINEL = '  ';
function resetKbd() { kbd.value = SENTINEL; kbd.setSelectionRange(SENTINEL.length, SENTINEL.length); }
$('kbdBtn').addEventListener('click', () => { resetKbd(); kbd.focus(); });
let composing = false;
kbd.addEventListener('compositionstart', () => { composing = true; });
kbd.addEventListener('compositionend', () => { composing = false; flushKbd(); });
kbd.addEventListener('input', () => { if (!composing) flushKbd(); });
function flushKbd() {
  const v = kbd.value;
  if (v.length < SENTINEL.length) {
    for (let i = v.length; i < SENTINEL.length; i++) send({ kind: 'key', key: 'backspace' });
  } else if (v.startsWith(SENTINEL)) {
    const text = v.slice(SENTINEL.length).replace(/\r/g, '');
    if (text) send({ kind: 'type', text });
  } else {
    send({ kind: 'type', text: v.trim() });
  }
  resetKbd();
}
kbd.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); send({ kind: 'key', key: 'enter' }); return; }
  if (SPECIAL[e.key] && e.key !== 'Backspace' && e.key !== ' ') { e.preventDefault(); send({ kind: 'key', key: SPECIAL[e.key] }); }
});

// --- toolbar
document.querySelectorAll('[data-key]').forEach((b) => b.addEventListener('click', () => send({ kind: 'key', key: b.dataset.key })));
$('keyMenu').addEventListener('change', (e) => {
  if (e.target.value) send({ kind: 'key', key: e.target.value });
  e.target.value = '';
});
$('rightBtn').addEventListener('click', () => { rightNext = !rightNext; $('rightBtn').classList.toggle('on', rightNext); });
$('wakeDisplayBtn').addEventListener('click', () => send({ kind: 'wakeDisplay' }).then(() => toast('Sent wake nudge')));
$('awake').addEventListener('change', async (e) => {
  try {
    await postJson('/api/keepawake', { mode: e.target.value });
    toast({ off: 'Screen will sleep normally', session: 'Screen stays on while someone is connected', always: 'Screen stays on until you change this' }[e.target.value]);
  } catch (err) {
    toast('Could not change: ' + err.message);
    refreshState();
  }
});
$('fullBtn').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else if (wrap.requestFullscreen) wrap.requestFullscreen().catch(() => toast('Full screen not supported here'));
});
$('rdpBtn').addEventListener('click', (e) => { e.preventDefault(); downloadRdp(''); });

async function downloadRdp(nodeId) {
  try {
    const res = await api('/api/rdp' + (nodeId ? '?nodeId=' + encodeURIComponent(nodeId) : ''));
    saveBlob(await res.blob(), 'activedesk.rdp');
    toast('Open the .rdp file with Windows App / Remote Desktop to use the sign-in screen');
  } catch (e) { toast(e.message); }
}

function saveBlob(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
}

// ------------------------------------------------------------------ chat

let room = 'general';
let lastSeq = 0;

async function loadChat(reset) {
  if (reset) { lastSeq = 0; $('messages').textContent = ''; }
  try {
    const d = await getJson('/api/chat?room=' + encodeURIComponent(room) + '&since=' + lastSeq);
    const sel = $('room');
    const rooms = d.rooms.includes(room) ? d.rooms : d.rooms.concat([room]);
    if (sel.options.length !== rooms.length) {
      sel.textContent = '';
      rooms.forEach((r) => { const o = el('option', '', '#' + r); o.value = r; sel.appendChild(o); });
    }
    sel.value = room;
    const box = $('messages');
    const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
    d.messages.forEach((m) => {
      lastSeq = Math.max(lastSeq, m.seq);
      const p = el('p', 'msg' + (m.system ? ' system' : ''));
      p.appendChild(el('span', 'time', fmtTime(m.ts)));
      if (!m.system) p.appendChild(el('span', 'who', m.from + (m.via ? ' (via ' + m.via + ')' : '') + ': '));
      p.appendChild(document.createTextNode(m.text));
      box.appendChild(p);
    });
    if (atBottom || reset) box.scrollTop = box.scrollHeight;
  } catch (e) { /* retry next tick */ }
}

$('room').addEventListener('change', (e) => { room = e.target.value; loadChat(true); });
$('joinRoom').addEventListener('click', () => {
  const r = $('newRoom').value.trim().toLowerCase().replace(/^#/, '');
  if (!/^[a-z0-9_-]{1,32}$/.test(r)) { toast('Room names: a-z 0-9 - _'); return; }
  room = r;
  $('newRoom').value = '';
  loadChat(true);
});
$('chatForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = $('chatText').value.trim();
  if (!text) return;
  try {
    await postJson('/api/chat', { room, text });
    $('chatText').value = '';
    loadChat(false);
  } catch (err) { toast(err.message); }
});

// ------------------------------------------------------------------ files

function fillTargets() {
  const sel = $('fileTarget');
  const prev = sel.value;
  sel.textContent = '';
  const me = el('option', '', 'Keep on ' + state.device);
  me.value = 'self';
  sel.appendChild(me);
  state.peers.forEach((p) => { const o = el('option', '', 'Send to ' + p.name); o.value = p.nodeId; sel.appendChild(o); });
  if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
}

$('uploadBtn').addEventListener('click', () => {
  const f = $('fileInput').files[0];
  if (!f) { toast('Pick a file first'); return; }
  const to = $('fileTarget').value || 'self';
  const xhr = new XMLHttpRequest();   // XHR gives upload progress; fetch does not
  xhr.open('POST', '/api/files/upload?to=' + encodeURIComponent(to) + '&name=' + encodeURIComponent(f.name));
  xhr.setRequestHeader('Authorization', 'Bearer ' + token);
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  const prog = $('uploadProgress');
  prog.classList.remove('hidden');
  xhr.upload.onprogress = (e) => { if (e.lengthComputable) prog.value = (e.loaded / e.total) * 100; };
  xhr.onload = () => {
    prog.classList.add('hidden');
    if (xhr.status === 401) { showLogin(); return; }
    let msg = 'Uploaded';
    try { const d = JSON.parse(xhr.responseText); msg = d.error ? 'Error: ' + d.error : 'Saved as ' + d.savedAs + (to !== 'self' ? ', sending...' : ''); } catch (e) { /* */ }
    $('uploadMsg').textContent = msg;
    loadFiles();
  };
  xhr.onerror = () => { prog.classList.add('hidden'); $('uploadMsg').textContent = 'Upload failed'; };
  xhr.send(f);
});

async function loadFiles() {
  try {
    const d = await getJson('/api/files');
    const list = $('fileList');
    list.textContent = '';
    if (!d.files.length) list.appendChild(el('p', 'muted', 'No files yet. Files sent to this device appear here.'));
    d.files.forEach((f) => {
      const it = el('div', 'item');
      const g = el('div', 'grow');
      g.appendChild(el('div', '', f.name));
      g.appendChild(el('div', 'sub', fmtSize(f.size) + ' - ' + new Date(f.modified).toLocaleString()));
      it.appendChild(g);
      it.appendChild(button('Download', async () => {
        try { saveBlob(await (await api('/api/files/get?name=' + encodeURIComponent(f.name))).blob(), f.name); } catch (e) { toast(e.message); }
      }));
      if (state && state.peers.length) {
        const sel = el('select');
        sel.appendChild(el('option', '', 'Send to...'));
        state.peers.forEach((p) => { const o = el('option', '', p.name); o.value = p.nodeId; sel.appendChild(o); });
        sel.addEventListener('change', async () => {
          if (!sel.value) return;
          try { await postJson('/api/files/forward', { name: f.name, to: sel.value }); toast('Sending ' + f.name); } catch (e) { toast(e.message); }
          sel.selectedIndex = 0;
        });
        it.appendChild(sel);
      }
      list.appendChild(it);
    });
    const tl = $('transferList');
    tl.textContent = '';
    if (!d.transfers.length) tl.appendChild(el('p', 'muted', 'No transfers since ActiveDesk started.'));
    d.transfers.forEach((t) => {
      const it = el('div', 'item');
      const dot = el('span', 'dot ' + (t.status === 'done' ? 'ok' : t.status === 'failed' ? 'bad' : 'warn'));
      it.appendChild(dot);
      const g = el('div', 'grow');
      g.appendChild(el('div', '', (t.direction === 'out' ? 'To ' : 'From ') + t.peer + ': ' + t.name));
      const pct = t.size ? Math.floor((t.done / t.size) * 100) : 100;
      g.appendChild(el('div', 'sub', t.status + ' ' + pct + '% of ' + fmtSize(t.size) + (t.detail ? ' - ' + t.detail : '')));
      it.appendChild(g);
      tl.appendChild(it);
    });
  } catch (e) { /* */ }
}

// ------------------------------------------------------------------ devices

// ------------------------------------------------------------------ power + battery

function renderPower() {
  if (!state || !state.power) return;
  const p = state.power, b = state.battery || {};
  $('powerDevice').textContent = state.device;

  // one-time setup prompt (only when something here actually needs it)
  const setupRunning = (state.powerSetup || '').startsWith('running');
  const needs = p.needsSetup || (p.hasBattery && b.needsSetup);
  $('powerSetupCard').classList.toggle('hidden', !needs && !state.powerSetup);
  $('powerSetupMsg').textContent = state.powerSetup ? 'Setup: ' + state.powerSetup
    : 'Some settings below need a one-time permission on this device.';
  $('powerSetupBtn').disabled = setupRunning || state.viewOnly;
  $('powerSetupBtn').classList.toggle('hidden', !needs);

  // status line
  const bits = [];
  if (p.hasBattery && p.percent >= 0) bits.push('Battery ' + p.percent + '% - ' + (p.onBattery ? 'on battery' : p.charging ? 'charging' : 'plugged in'));
  else if (p.ready) bits.push('Plugged in (no battery)');
  if (p.saverActive) bits.push('auto power saver is ON');
  if (p.message) bits.push(p.message);
  $('powerStatus').textContent = p.ready ? bits.join('. ') : 'Checking this device...';

  // power mode
  const hasModes = p.profiles && p.profiles.length > 0;
  $('modeField').classList.toggle('hidden', !hasModes);
  fillModes($('powerMode'), p.profiles || [], null);
  if (document.activeElement !== $('powerMode')) $('powerMode').value = p.profile;
  $('powerMode').disabled = state.viewOnly;

  // brightness
  const hasBright = p.brightness >= 0;
  $('brightField').classList.toggle('hidden', !hasBright);
  if (hasBright && document.activeElement !== $('brightness')) {
    $('brightness').value = p.brightness;
    $('brightVal').textContent = p.brightness + '%';
  }
  $('brightness').disabled = state.viewOnly;
  $('powerNone').classList.toggle('hidden', !p.ready || hasModes || hasBright);

  // battery: the whole section only exists on devices that have one
  $('batterySection').classList.toggle('hidden', !p.hasBattery);
  $('noBattery').classList.toggle('hidden', p.hasBattery || !p.ready);
  if (!p.hasBattery) return;
  $('batteryMsg').textContent = b.supported
    ? (b.enabled ? (b.holding ? 'Holding charge (top of the range reached). ' : 'Limit is on. ') : '') + (b.message || '')
    : 'Charge limit: ' + (b.message || 'not supported here');
  $('batteryControls').classList.toggle('hidden', !b.supported);
  if (![$('batteryMin'), $('batteryMax')].includes(document.activeElement)) {
    $('batteryOn').checked = !!b.enabled;
    $('batteryMin').value = b.min;
    $('batteryMax').value = b.max;
  }
  fillModes($('saverProfile'), p.profiles || [], 'the most power-saving mode');
  if (![$('saverBelow'), $('saverDim'), $('saverProfile')].includes(document.activeElement)) {
    $('saverOn').checked = !!p.saverEnabled;
    $('saverBelow').value = p.saverBelow;
    $('saverDim').value = p.saverBrightness;
    $('saverProfile').value = p.saverProfile || '';
  }
  $('saverMsg').textContent = p.saverActive ? 'Active now' : '';
}

function fillModes(sel, profiles, firstLabel) {
  const key = (firstLabel || '') + '|' + profiles.map((x) => x.id + '=' + x.name).join(',');
  if (sel.dataset.key === key) return;
  sel.dataset.key = key;
  sel.textContent = '';
  if (firstLabel) { const o = el('option', '', firstLabel); o.value = ''; sel.appendChild(o); }
  profiles.forEach((x) => { const o = el('option', '', x.name); o.value = x.id; sel.appendChild(o); });
}

async function powerPost(path, data, ok) {
  try {
    const r = await postJson(path, data);
    if (r.power) state.power = r.power;
    if (r.battery) state.battery = r.battery;
    renderPower();
    if (ok) toast(ok);
  } catch (e) { toast(e.message); refreshState(); }
}

$('powerMode').addEventListener('change', () => {
  const o = $('powerMode').selectedOptions[0];
  powerPost('/api/power/profile', { id: $('powerMode').value }, 'Power mode: ' + (o ? o.textContent : ''));
});
$('brightness').addEventListener('input', () => { $('brightVal').textContent = $('brightness').value + '%'; });
$('brightness').addEventListener('change', () => {
  powerPost('/api/power/brightness', { value: parseInt($('brightness').value, 10) }, 'Brightness ' + $('brightness').value + '%');
});
$('batterySave').addEventListener('click', () => {
  const min = parseInt($('batteryMin').value, 10), max = parseInt($('batteryMax').value, 10);
  powerPost('/api/battery', { enabled: $('batteryOn').checked, min, max },
    $('batteryOn').checked ? 'Charging limited to ' + min + '-' + max + '%' : 'Normal charging');
});
$('saverSave').addEventListener('click', () => {
  powerPost('/api/power/saver', {
    enabled: $('saverOn').checked,
    below: parseInt($('saverBelow').value, 10),
    profile: $('saverProfile').value,
    brightness: parseInt($('saverDim').value, 10) || 0,
  }, $('saverOn').checked ? 'Auto power saver on' : 'Auto power saver off');
});
$('powerSetupBtn').addEventListener('click', async () => {
  try {
    await api('/api/power/setup', { method: 'POST' });
    toast('Approve the admin prompt on ' + state.device + ' itself');
    refreshState();
  } catch (e) { toast(e.message); }
});

function renderDevices() {
  if (!state) return;
  const list = $('deviceList');
  list.textContent = '';
  const me = el('div', 'item');
  me.appendChild(el('span', 'dot ok'));
  const g = el('div', 'grow');
  g.appendChild(el('div', '', state.device + ' (this device)'));
  g.appendChild(el('div', 'sub', state.os + ' - Tailscale ' + state.tailscale + ' - ' + (state.screen ? 'screen sharing on' : 'no screen sharing')));
  me.appendChild(g);
  list.appendChild(me);

  list.appendChild(el('h3', '', 'Online with ActiveDesk'));
  if (!state.peers.length) list.appendChild(el('p', 'muted', 'No other ActiveDesk devices connected. Install and run ActiveDesk on them with Tailscale on.'));
  state.peers.forEach((p) => {
    const it = el('div', 'item');
    it.appendChild(el('span', 'dot ok'));
    const gg = el('div', 'grow');
    gg.appendChild(el('div', '', p.name));
    gg.appendChild(el('div', 'sub', p.os + ' - ' + p.ip + (p.screen ? ' - screen' : '')));
    it.appendChild(gg);
    if (p.webUrl) {
      const a = el('a', 'button', 'Open');
      a.href = p.webUrl;
      a.rel = 'noreferrer';
      it.appendChild(a);
    }
    list.appendChild(it);
  });

  list.appendChild(el('h3', '', 'Asleep / offline'));
  if (!state.offline.length) list.appendChild(el('p', 'muted', 'None. Devices seen once with ActiveDesk show up here so you can wake them.'));
  state.offline.forEach((d) => {
    const it = el('div', 'item');
    it.appendChild(el('span', 'dot bad'));
    const gg = el('div', 'grow');
    gg.appendChild(el('div', '', d.name));
    gg.appendChild(el('div', 'sub', d.os + ' - last seen ' + (d.lastSeen ? new Date(d.lastSeen).toLocaleString() : 'never')));
    it.appendChild(gg);
    if (d.wakeable) {
      it.appendChild(button('Wake', async () => {
        try { const r = await postJson('/api/wake', { nodeId: d.nodeId }); toast('Wake signal sent (' + r.packets + '). Give it ~30 s, then refresh.'); } catch (e) { toast(e.message); }
      }));
    }
    if (d.rdp) it.appendChild(button('Sign-in (RDP)', () => downloadRdp(d.nodeId)));
    list.appendChild(it);
  });

  list.appendChild(el('h3', '', 'Other Tailscale devices'));
  state.tailnet.filter((t) => !t.activedesk).forEach((t) => {
    const it = el('div', 'item');
    it.appendChild(el('span', 'dot ' + (t.online ? 'warn' : '')));
    const gg = el('div', 'grow');
    gg.appendChild(el('div', '', t.name));
    gg.appendChild(el('div', 'sub', t.os + ' - ' + (t.ip || '') + (t.online ? ' - online, ActiveDesk not running' : ' - offline')));
    it.appendChild(gg);
    list.appendChild(it);
  });
}

// ------------------------------------------------------------------ tasks

async function loadTasks() {
  try {
    const d = await getJson('/api/tasks');
    $('tasksFile').textContent = 'Tasks are defined in ' + d.file + ' (edits load automatically).';
    const list = $('taskList');
    list.textContent = '';
    d.tasks.forEach((t) => {
      const it = el('div', 'item');
      const last = t.last;
      it.appendChild(el('span', 'dot ' + (t.running ? 'warn' : !last ? '' : last.status === 'SUCCEEDED' ? 'ok' : 'bad')));
      const g = el('div', 'grow');
      g.appendChild(el('div', '', t.name + ' '));
      g.firstChild.appendChild(el('span', 'badge', t.type));
      let sub = t.schedule + (t.next ? ' - next ' + new Date(t.next).toLocaleString() : '');
      if (t.dependsOn.length) sub += ' - after ' + t.dependsOn.join(', ');
      if (last) sub += ' - last ' + last.status + ' ' + fmtTime(last.startedAt);
      g.appendChild(el('div', 'sub', sub));
      it.appendChild(g);
      it.appendChild(button('Run', async () => {
        try { await postJson('/api/tasks/run', { id: t.id }); toast('Queued ' + t.id); } catch (e) { toast(e.message); }
      }));
      it.appendChild(button(t.enabled ? 'Disable' : 'Enable', async () => {
        try { await postJson('/api/tasks/enable', { id: t.id, enabled: !t.enabled }); loadTasks(); } catch (e) { toast(e.message); }
      }));
      list.appendChild(it);
    });
    Object.entries(d.invalid).forEach(([id, why]) => {
      const it = el('div', 'item');
      it.appendChild(el('span', 'dot bad'));
      it.appendChild(el('div', 'grow', id + ': ' + why));
      list.appendChild(it);
    });
    const runs = $('runList');
    runs.textContent = '';
    d.runs.forEach((r) => {
      const it = el('div', 'item');
      const g = el('div', 'grow');
      const head = el('div', '', r.taskId + ' ');
      head.appendChild(el('span', 'status-' + r.status, r.status));
      g.appendChild(head);
      const detail = (r.error || r.output || '').split('\n')[0];
      g.appendChild(el('div', 'sub', fmtTime(r.startedAt) + ' - ' + (r.finishedAt - r.startedAt) + ' ms - ' + r.attempts
        + ' attempt(s) - ' + r.trigger + (detail ? ' - ' + detail : '')));
      it.appendChild(g);
      runs.appendChild(it);
    });
  } catch (e) { /* */ }
}

// ------------------------------------------------------------------ boot

if (token) start(); else showLogin();
