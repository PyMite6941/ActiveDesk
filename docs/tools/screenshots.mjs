// Regenerates the screenshots in docs/img/ from a running ActiveDesk node.
//
//   node docs/tools/screenshots.mjs <base-url> <pin> [chrome-path]
//
// Uses headless Chrome over the DevTools protocol (Node 22+, no npm packages). Personal data never reaches the
// pictures: /api/state, /api/chat and the remote screen image are swapped for demo data on the way in.
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const [base, pin, chromeArg] = process.argv.slice(2);
if (!base || !pin) { console.error('usage: node screenshots.mjs <base-url> <pin> [chrome-path]'); process.exit(2); }
const chrome = chromeArg || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
  : process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome');
const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'img');
mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ browser + CDP
const port = 9333;
const proc = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', '--hide-scrollbars',
  `--remote-debugging-port=${port}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'ad-shots-'))}`, 'about:blank'], { stdio: 'ignore' });
let target;
for (let i = 0; i < 50 && !target; i++) {
  try { target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === 'page'); } catch { await sleep(200); }
}
if (!target) throw new Error('Chrome did not start');
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let nextId = 1;
const pending = new Map();
const handlers = [];
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); }
  else if (m.method) handlers.forEach((h) => h(m));
});
const cdp = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expr) => (await cdp('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result.value;

// ------------------------------------------------------------------ demo data
let variant = 'laptop';   // 'laptop' | 'pi' (no screen, no battery)
const now = Date.now();
function demoState(s) {
  const pi = variant === 'pi';
  Object.assign(s, {
    device: pi ? 'raspberrypi' : 'laptop', selfName: pi ? 'raspberrypi' : 'laptop', selfIp: pi ? '100.64.0.2' : '100.64.0.1',
    user: pi ? 'pi' : 'alex', os: pi ? 'Linux' : s.os, tailscale: 'Running',
    peers: pi ? [{ name: 'laptop', os: 'Windows 11', ip: '100.64.0.1', screen: true, webUrl: '#' }]
      : [{ name: 'raspberrypi', os: 'Linux', ip: '100.64.0.2', screen: false, webUrl: '#' },
         { name: 'desktop', os: 'Windows 11', ip: '100.64.0.3', screen: true, webUrl: '#' }],
    offline: [{ nodeId: 'demo', name: 'gaming-pc', os: 'Windows 11', lastSeen: now - 3 * 3600e3, wakeable: true, rdp: true, ip: '100.64.0.4' }],
    tailnet: [{ name: 'phone', os: 'iOS', ip: '100.64.0.9', online: true, activedesk: false }],
    rooms: ['general', 'ops'],
  });
  if (pi) {
    s.screen = false;
    s.screenReason = 'no display (headless)';
    s.power = Object.assign({}, s.power, { hasBattery: false, percent: -1, onBattery: false, brightness: -1, needsSetup: false,
      profiles: [{ id: 'performance', name: 'Performance' }, { id: 'ondemand', name: 'Ondemand' }, { id: 'powersave', name: 'Powersave' }],
      profile: 'ondemand', message: '' });
    s.battery = Object.assign({}, s.battery, { hasBattery: false, supported: false });
  } else {
    s.screen = true;
    s.power = Object.assign({}, s.power, { hasBattery: true, percent: 64, onBattery: false, charging: false });
  }
  s.powerSetup = '';
  return s;
}
const chat = [
  ['laptop', 'Heading out - can someone check the Pi backup?'],
  ['raspberrypi', 'Nightly notes backup finished'],
  ['desktop', 'Sent you the slides, they are in Files'],
  ['laptop', 'Got them, thanks!'],
].map(([from, text], i) => ({ seq: i + 1, id: 'd' + i, room: 'general', fromNode: from, from, text, ts: now - (40 - i * 9) * 60e3, system: false, via: '' }));

// a pretend desktop for the remote-screen picture (rendered by the same browser)
async function demoDesktop() {
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false });
  const html = `<body style="margin:0;height:800px;font:15px system-ui;background:linear-gradient(135deg,#1e3a5f,#0f766e);position:relative;overflow:hidden">
    <div style="position:absolute;left:120px;top:90px;width:700px;height:440px;background:#fff;border-radius:10px;box-shadow:0 20px 50px #0006">
      <div style="height:38px;background:#f1f5f9;border-radius:10px 10px 0 0;display:flex;align-items:center;padding:0 14px;color:#334155">Notes - Weekly plan</div>
      <div style="padding:22px 26px;color:#1e293b;line-height:1.9"><b style="font-size:20px">This week</b><br>&#x2611; Finish slides for Monday<br>&#x2610; Back up photos to the Pi<br>&#x2610; Water the plants<br>&#x2610; Call grandma on Sunday</div></div>
    <div style="position:absolute;right:90px;top:150px;width:300px;height:220px;background:#0f172a;border-radius:10px;color:#a7f3d0;font:13px monospace;padding:16px;box-shadow:0 20px 50px #0006">$ df -h /<br>Filesystem Size Used Avail<br>/dev/root  58G  21G  35G<br>$ _</div>
    <div style="position:absolute;left:0;right:0;bottom:0;height:46px;background:#0f172acc;display:flex;align-items:center;justify-content:center;gap:14px">
      ${['#38bdf8', '#f59e0b', '#22c55e', '#a78bfa', '#f472b6'].map((c) => `<span style="width:26px;height:26px;border-radius:7px;background:${c}"></span>`).join('')}</div></body>`;
  await cdp('Page.navigate', { url: 'data:text/html;base64,' + Buffer.from(html).toString('base64') });
  await sleep(800);
  return (await cdp('Page.captureScreenshot', { format: 'jpeg', quality: 80 })).data;
}

// ------------------------------------------------------------------ run
await cdp('Page.enable');
await cdp('Runtime.enable');
const desktopJpeg = await demoDesktop();

await cdp('Fetch.enable', { patterns: [
  { urlPattern: '*/api/state*', requestStage: 'Response' },
  { urlPattern: '*/api/chat?*', requestStage: 'Response' },
  { urlPattern: '*/api/tasks', requestStage: 'Response' },
  { urlPattern: '*/api/screen*', requestStage: 'Request' },
] });
handlers.push(async (m) => {
  if (m.method !== 'Fetch.requestPaused') return;
  const { requestId, request } = m.params;
  const fulfill = (body, type) => cdp('Fetch.fulfillRequest', { requestId, responseCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: type }, { name: 'Cache-Control', value: 'no-store' }], body });
  try {
    if (request.url.includes('/api/screen')) return fulfill(desktopJpeg, 'image/jpeg');
    const raw = await cdp('Fetch.getResponseBody', { requestId });
    const json = JSON.parse(raw.base64Encoded ? Buffer.from(raw.body, 'base64').toString() : raw.body);
    if (request.url.includes('/api/state')) demoState(json);
    else if (request.url.includes('/api/tasks')) json.file = '~/.activedesk/tasks.json';
    else if (request.method === 'GET') { json.messages = json.room === 'general' ? chat : []; json.rooms = ['general', 'ops']; json.lastSeq = chat.length; }
    return fulfill(Buffer.from(JSON.stringify(json)).toString('base64'), 'application/json');
  } catch (e) {
    cdp('Fetch.continueRequest', { requestId }).catch(() => {});
  }
});

const desktop = { width: 1280, height: 860, deviceScaleFactor: 1, mobile: false };
const phone = { width: 390, height: 844, deviceScaleFactor: 2, mobile: true };

async function open(metrics) {
  await cdp('Emulation.setDeviceMetricsOverride', metrics);
  await cdp('Page.navigate', { url: base + '/' });
  await sleep(3500); // let /api/state arrive so the header shows the device name
}
async function shot(name, metrics, prep) {
  await cdp('Emulation.setDeviceMetricsOverride', metrics);
  if (prep) await evaluate(prep);
  await sleep(1800);
  const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
  writeFileSync(join(out, name + '.png'), Buffer.from(data, 'base64'));
  console.log('wrote', name + '.png');
}
const tab = (t) => `document.querySelector('nav button[data-tab="${t}"]').click()`;

// login screen first (no token yet)
await open(phone);
await shot('login-phone', phone, `$('pin').blur()`);
// sign in exactly like the page does
await evaluate(`fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({pin:${JSON.stringify(pin)}})})
  .then(r=>r.json()).then(d=>{sessionStorage.setItem('ad_token',d.token);})`);
await open(desktop);
await shot('screen-desktop', desktop, tab('screen'));
await shot('screen-phone', phone, tab('screen'));
await shot('chat-phone', phone, tab('chat'));
await shot('files-desktop', desktop, tab('files'));
await shot('devices-desktop', desktop, tab('devices'));
await shot('devices-phone', phone, tab('devices'));
await shot('tasks-desktop', desktop, tab('tasks'));
await shot('power-desktop', desktop, tab('power'));
await shot('power-phone', phone, tab('power'));
variant = 'pi';
await open(desktop);
await shot('noscreen-desktop', desktop, tab('screen'));
await shot('power-pi-desktop', desktop, tab('power'));

ws.close();
proc.kill();
process.exit(0);
