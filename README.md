# ActiveDesk

Private chat, file transfer, remote screen control, a task/workflow scheduler, keep-awake and battery charge
limits for your own devices. It needs **no server**: every device runs the same small Java program, and they
find each other over your **Tailscale** tailnet.

```
 phone (browser / PWA) ──https/http over Tailscale──▶  laptop  ◀──ActiveDesk mesh (NIO, port 47800)──▶  Raspberry Pi
                                                      ▲    web UI :47801                                ▲
 another computer ── `activedesk chat` / `nc laptop 47800` ┘                                            desktop
```

- **Pure JDK 17+, zero dependencies.** It runs anywhere Java runs: Windows, macOS, Linux, Raspberry Pi (64-bit Pi OS).
- **Phones** use the built-in web app. Open `http://<device>:47801` over Tailscale and "Add to Home Screen".
- **`nc` works as a fallback client** for chat: `nc <device> 47800`.
- **All free.** Tailscale Personal, GitHub and OpenJDK cost nothing, and so does ActiveDesk.
- **Docs, with screenshots:** https://pymite6941.github.io/ActiveDesk/

## Features

| | |
|---|---|
| **Chat** | Rooms, history sync between devices, de-duplication, anti-spoofing (a message must arrive from its author's own connection). |
| **File transfer** | Offer → 64 KB chunks → SHA-256 verified. Backpressure keeps memory use flat. Safe file names, never overwrites. |
| **Remote screen** | Live view with a real mouse, touch gestures and keyboard. A red on-screen banner shows whenever someone is watching or controlling. |
| **Keep screen on** | `session` (default): the display stays on while someone is connected. `always`: kiosk/dashboard use. `off`. |
| **Wake devices** | Wake-on-LAN, relayed through any awake ActiveDesk device on the same LAN. |
| **Sign-in screen** | Windows lock-screen access through Remote Desktop over Tailscale (a `.rdp` file is offered). |
| **Battery range** | Only charge between e.g. 40 % and 80 %, only while ActiveDesk runs (see below). |
| **Scheduler** | Interval / daily / startup / manual triggers. Dependency workflows (DAG), retries with backoff, timeouts. Action types: `shell`, `http` (scraper), `backup` (zip), `email` (SMTP over TLS), `chat`. |
| **Persistence** | JSONL by default. Any JDBC database (SQLite, H2, Postgres...) if you drop its driver jar in `lib/`. |

## Install

1. Install **Tailscale** on every device and sign in to the same tailnet.
2. Install **Java 17+**, or download a release bundle that already includes Java:
   - Windows: `winget install EclipseAdoptium.Temurin.21.JRE`
   - macOS: `brew install --cask temurin`
   - Raspberry Pi / Debian / Ubuntu: `sudo apt install openjdk-17-jre`
3. Get ActiveDesk, either way:
   - From [Releases](../../releases): the portable `activedesk.jar`, or a per-OS bundle.
   - Build it: `./build.sh` (Linux/macOS) or `build.cmd` (Windows), which produces `dist/activedesk.jar`.
4. Run it: `./activedesk` (or `activedesk.cmd`, or `java -jar activedesk.jar`).
   - The first start prints an **8-digit web PIN**. Note it.
   - Run `activedesk pin` any time to set a new one.
5. Start it automatically with `activedesk install-service`:
   - Windows: a Startup entry.
   - macOS: a LaunchAgent.
   - Linux: XDG autostart, plus a systemd user unit.

### One-line installers

- Linux / Pi / macOS: `curl -fsSL https://pymite6941.github.io/ActiveDesk/install.sh | sh`
- Windows PowerShell: `irm https://pymite6941.github.io/ActiveDesk/install.ps1 | iex`

The installer checks for Java and Tailscale, offers to install them, downloads `activedesk.jar` from the
latest release, **verifies its SHA-256**, adds the `activedesk` command and then starts the setup wizard.
Overrides: `ACTIVEDESK_JAR` (use a local jar), `ACTIVEDESK_VERSION` (pin a release), `ACTIVEDESK_YES=1` (no questions).

### Setup wizard

`activedesk setup` (add `--yes` to accept every default) walks through:

1. Tailscale is installed and logged in.
2. The device has a screen. If it is headless, it offers `tailscale set --ssh` (see below).
3. Device name and web PIN.
4. Keep-awake mode.
5. Optional power and battery setup (`activedesk power install`).
6. Start on login.

### Devices without a screen

A Raspberry Pi or server with no display has nothing to remote-control. The **Screen** tab then shows a
**Tailscale SSH** panel instead: a copyable `tailscale ssh user@host` command and steps for phone SSH apps.
Tailscale SSH cannot run *on* Windows; use the built-in OpenSSH Server there.

### Uninstall

ActiveDesk does not scatter files across your system. To remove it completely:

1. Stop the node: close the terminal, or `taskkill /F /IM java.exe` (Windows) / `pkill -f activedesk.jar` (Linux/macOS).
2. Remove autostart (if you ran `activedesk install-service`).
3. Delete the app folder: `%LOCALAPPDATA%\ActiveDesk` (Windows) or `~/.local/share/activedesk` + `~/.local/bin/activedesk` (Linux/macOS).
4. Delete the data folder (optional — removes config, PIN, audit log, chat history): `%APPDATA%\ActiveDesk` (Windows) or `~/.activedesk` (Linux/macOS).

Uninstalling ActiveDesk does not remove Tailscale or Java — those are separate installations.

### Phone

1. Install the Tailscale app on the phone.
2. Open `http://<device-name>:47801`, or `http://100.x.y.z:47801`.
3. Enter the PIN.
4. Use "Add to Home Screen" to install the app.

For HTTPS, run `tailscale serve --bg 47801` on the device and open `https://<device>.<tailnet>.ts.net`. HTTPS also lets the phone keep its own screen on while it shows the remote screen (the browser's Wake Lock API needs a secure page).

### Remote control in practice

| Input | How |
|---|---|
| Mouse | Move, click, drag and wheel map 1:1. |
| Touch | Tap = click. Long-press = right-click. Drag = drag. Two fingers = scroll. |
| Keyboard | Physical keyboards are forwarded directly. On phones, tap ⌨ to open the on-screen keyboard. Ctrl/Alt/Win chords are in the toolbar. |

**Platform notes:**
- **Raspberry Pi:** use the **X11** session (`sudo raspi-config` → Advanced → Wayland → X11). Wayland blocks screen capture for all apps.
- **macOS:** grant **Screen Recording** and **Accessibility** to Java (or to the ActiveDesk app) in System Settings → Privacy & Security.

### Waking a sleeping or locked device

| Situation | What works |
|---|---|
| Device **asleep / off** | **Devices → Wake**. Needs Wake-on-LAN enabled in the BIOS/UEFI and network adapter, plus another ActiveDesk device awake on the same LAN. Packets only go to private ranges and are rate limited. |
| Screen **dimmed / screensaver** | **Wake screen** in the toolbar. |
| **Windows lock screen** | No app can type into it. Windows forbids it by design. Use **Sign-in screen (RDP)**, which downloads a `.rdp` pointing at the Tailscale IP. Needs Windows Pro with Remote Desktop on. Phones: Microsoft's "Windows App". |
| **macOS lock screen** | Same restriction. Enable Screen Sharing and connect with `vnc://<tailscale-ip>`. |
| **Linux / Pi** | Simplest is to enable auto-login. Or use `xrdp`. |

Setting **Keep screen on** to `session` or `always` stops the device from blanking and locking in the first place. That covers most "it went idle" cases. A domain or MDM "machine inactivity limit" policy can still lock the device.

## Keep screen on

```
activedesk awake                       # show the mode
activedesk awake always|session|off
```

You can also set it from **Screen → "Screen: …"** in the web app. The setting is saved to `power.keepAwake`.

| OS | How the screen is kept on |
|---|---|
| Windows | `SetThreadExecutionState` (display + system required) |
| macOS | `caffeinate -d -i -w <pid>` |
| Linux | `systemd-inhibit --what=idle:sleep`, plus `xset s reset` / `dpms force on` on X11 |

On top of the OS call, ActiveDesk **nudges input every 30 s**: a Shift tap plus a 1-pixel pointer move, and
**never a click**. This also resets idle timers that ignore the OS call (for example, chat apps that mark you
"away"). The web app shows the method in use and when the last nudge happened. Each helper is tied to the ActiveDesk process, so a crash can never leave a machine stuck awake.

## Power mode, brightness and auto power saver

```
activedesk power install          # once per computer: lets the node change power settings
activedesk power status
activedesk power mode <id>        # ids come from `power status` (e.g. saver / balanced / performance)
activedesk power brightness 60
activedesk power saver 20 40      # on battery below 20 %: saver mode, dim to 40 %. Restored when plugged in.
activedesk power saver off
activedesk power uninstall
```

The same controls are on the web app's **Power** tab. The battery section only appears on devices that have a battery.

| OS | Power mode | Brightness |
|---|---|---|
| Windows | `powercfg` plans | WMI (built-in screens) |
| Linux / Pi | power-profiles-daemon, or CPU governors | `brightnessctl` or the sysfs backlight |
| macOS | `pmset` Low Power Mode (sudoers rule added by `power install`) | Not available to apps |

## Battery charge range

Example: "only charge between 40 % and 80 %, and pause charging when it reaches 80 %". The range applies **only while ActiveDesk runs**.

No operating system lets an ordinary app pause charging, so each computer needs a **one-time privileged setup**:

```
activedesk battery install        # once per computer (UAC prompt on Windows, sudo on Linux)
activedesk battery 40 80          # set and switch on   (or use the web app: Power → Battery)
activedesk battery off | on | status | uninstall
```

| OS | Supported hardware | How |
|---|---|---|
| **Linux** | Any laptop whose kernel driver exposes charge control: ThinkPad, ASUS, Dell, Framework, Huawei, LG, Samsung, MSI, System76 and others | Standard sysfs `charge_control_{start,end}_threshold` or `charge_behaviour=inhibit-charge`. `install` adds a udev rule letting your group write **only** those files; the node never runs as root. |
| **Windows** | **ASUS** (firmware charge limit via ATK WMI); **Dell** with *Dell Command \| Configure* installed (native start/stop range) | `install` copies a small guard script to `C:\Program Files\ActiveDesk` (admin-only) and registers an on-demand scheduled task. The node starts the task; the task exits and restores normal charging once the node stops. |
| **Windows, other brands** | Not available from software (Lenovo, HP, Surface, ...) | Use the vendor app or the BIOS battery setting. |
| **macOS** | Not supported | Needs a root helper writing undocumented SMC keys. Use Optimized Battery Charging. |
| **Raspberry Pi / desktops** | No battery, so the panel says so | |

Where the firmware only has a "stop at N %" limit (ASUS, some Linux drivers), ActiveDesk emulates the range:
- At max, it lowers the limit to min. Charging **pauses**, the laptop runs on wall power and the percentage holds.
- At min, it raises the limit back to max.

Thresholds that existed before are saved and restored. If ActiveDesk is killed, they are restored at the next start, or by `battery uninstall`.

## CLI and `nc`

```
activedesk                      run the node (default)
activedesk chat [room]          terminal chat           |  nc <device> 47800  (same thing, no Java needed)
activedesk send <device> <file> send a file
activedesk who | tasks          devices / scheduled tasks
activedesk run-task <id>        run a task and its dependencies now
activedesk lock                 end every web / remote-control session immediately
activedesk pin                  new web PIN
activedesk status               Tailscale + config summary
activedesk selftest             built-in end-to-end test (what CI runs)
```

## Tasks (`~/.activedesk/tasks.json`)

The file is hot-reloaded. See [`examples/tasks.example.json`](examples/tasks.example.json).

```json
{ "id": "backup-notes", "type": "backup", "schedule": "daily 23:00", "retries": 2, "retryDelay": "30s",
  "timeout": "20m", "params": { "source": "~/Documents/notes", "dest": "~/Backups/notes", "keep": "14" } }
```

| Field | Values |
|---|---|
| Schedules | `manual`, `startup`, `every 30s / 15m / 2h / 1d`, `daily HH:MM` |
| `dependsOn` | Runs the dependencies first. If a dependency fails, the task is skipped. Cycles are rejected. |
| `node` | Restricts a task to one device. |

SMTP settings live in `config.properties` (`smtp.*`). Run logs go to JSONL, or to a database if `store.jdbc.url` is set and the driver jar is in `lib/`.

## Security model

ActiveDesk can control your computer, so it is locked down in layers:

1. **Network:** it listens only on loopback and the device's **Tailscale IP**, never `0.0.0.0`. Your LAN and the internet cannot reach it.
2. **Identity:** each connection's Tailscale IP is looked up with `tailscale status`. Only devices owned by **your** login are accepted, plus any logins or tags you list in `allow.logins` / `allow.tags`. Shared-in or foreign nodes are refused.
3. **PIN:**
   - The web app also needs the PIN, stored only as a PBKDF2 hash.
   - Wrong guesses are rate-limited: 5 per IP and 20 in total per 15 minutes.
   - Sessions are bound to the client IP and expire after 30 minutes idle or 12 hours.
   - `activedesk lock` revokes them all.
4. **Web hardening:**
   - Host and Origin checks (against DNS rebinding and CSRF).
   - Strict CSP, `X-Frame-Options: DENY`, `nosniff`, `no-store`.
   - All network text is rendered as text, never HTML.
5. **Visibility:**
   - A red banner shows on the controlled screen.
   - Every login, control session, transfer, wake, battery and keep-awake change is written to `~/.activedesk/audit.log`.
   - Remote control also posts a chat notice.
6. **Local privileges:** commands like `send`, `run-task`, `lock`, `awake` and `battery` need the owner-only `local.token`, so another user on the same machine can't drive your node.
7. **Least privilege for battery control:**
   - The Windows guard lives in admin-only `Program Files` and reads just three integers from your config.
   - On Linux, the udev rule grants write access to the charge-control files only.
8. **Opt-outs:** `control.enabled=false` or `control.viewOnly=true` limits or disables remote control per device.

See [SECURITY.md](SECURITY.md) for the threat model and how to report issues.

## Development

```
./build.sh && java -jar dist/activedesk.jar selftest
```

- CI (GitHub Actions) builds and self-tests on Linux x64, Linux ARM64 (Pi), Windows and macOS, with Java 17 and 21.
- CodeQL scans the Java and JavaScript.
- Tagging `v*` publishes the portable jar, per-OS bundles with Java included, `SHA256SUMS.txt` and build-provenance attestations.
