# ActiveDesk: handoff notes (2026-11-03)

This file is the context for whoever picks this up next. Delete it once the work below is finished.

## Project in one paragraph

ActiveDesk is a pure-JDK 17, zero-dependency Java app. It runs on every device (Windows, macOS, Linux, Raspberry Pi) and talks peer-to-peer over **Tailscale**. There is no server.

Features:
- chat
- file transfer
- remote screen and input from a phone PWA or a browser
- task scheduler
- Wake-on-LAN
- RDP helper
- keep-awake
- power mode, brightness and auto power saver
- battery charge range

The repo is its **own git repo**, separate from the parent ドキュメント repo. The remote is the **private** `https://github.com/PyMite6941/ActiveDesk`, branch `main`. The last pushed commit is `f3ab4dd`. **Everything below is uncommitted.**

## Build and test

```sh
sh build.sh                                   # -> dist/activedesk.jar (Windows: build.cmd, or Git Bash sh)
java -jar dist/activedesk.jar selftest        # 41 checks, must print "41 passed, 0 failed"
```

- **Rebuild after stopping the nodes.** `build.sh` can't replace `dist/activedesk.jar` while a node is running from it ("Device or resource busy").
- **For live testing, set `ACTIVEDESK_HOME`** to a scratch folder so the user's real `~/.activedesk` (config and PIN) is never touched.
- **Talking to a running node:** `activedesk power status`, `battery status` and `awake` reach the local node through the loopback console (`/power`, `/battery`, `/awake`).

## Hard-won gotchas (don't relearn these)

- **PowerShell from Java: always use `-EncodedCommand`** (UTF-16LE base64; see `power/Proc.powershell`). ProcessBuilder doesn't escape embedded `"`, so scripts silently break.
- **Inside a running node, write config with `Config.update(key, value)`, never `save()`.** `update()` re-reads the file first, so the PIN hash and the user's edits survive.
- **Windows battery detection can time out on WMI at startup.** It now retries (`BatteryService.detect` sets `detected=false` when the output has no `asus=`). Don't call it "unsupported" on a single failure.
- **Edit Java with the Edit tool or a Python script file.** Python heredocs run through the Bash tool mangle backslashes and sometimes truncate.
- **The selftest peer handshake was flaky once or twice right after a rebuild.** The wait was raised from 20 s to 45 s.
- **`install.sh` can't be tested under Git Bash on Windows** (the `C:/` classpath breaks `:`). CI tests it on Linux and macOS instead.

## Done this session (uncommitted, built, selftest 41/41)

1. **Power and battery**
   - New files: `power/PowerService`, `BatteryService` (rewritten), `PowerSetup`, `Proc`, and `resources/power/battery-guard.ps1`. Everything is per-OS and per-vendor, nothing laptop-specific.
   - Windows: `powercfg` plans and WMI brightness. The charge range works on ASUS (ATK WMI) and Dell (cctk), through an elevated scheduled-task guard.
   - Linux: power-profiles-daemon or CPU governors, `brightnessctl` or sysfs backlight, sysfs charge thresholds, plus a udev rule for one-time permission.
   - macOS: `pmset` Low Power Mode through a sudoers rule. No charge limit.
   - Auto power saver: on battery and below N% → saver mode plus dimming; the old settings are restored when plugged in.
2. **CLI**
   - `activedesk power install|uninstall|status|mode <id>|brightness <n>|saver off|saver <below> [dim]`
   - `battery install` now calls `PowerSetup`.
   - Console: `/power`.
   - New `activedesk setup` wizard (`Setup.java`): Tailscale check, screen check with an offer to run `tailscale set --ssh` when headless, device name, PIN, keep-awake mode, optional power setup, autostart. `--yes` accepts all defaults.
3. **Keep-awake now nudges input every 30 s**, on top of the OS API: Shift tap plus a 1 px pointer move, **never a click**. Implemented in `KeepAwake.nudge()` → `ScreenService.nudgeInput()`; the Node ticks every 10 s.
   - Live-verified on this Windows laptop: the idle timer reset.
   - State exposes `keepAwakeMethod` and `keepAwakeNudgeAgo`.
4. **Web UI** (`resources/web/`)
   - New **Power** tab: mode select, brightness slider, Set up button with status, a Battery section (charge range and auto saver) shown **only when `state.power.hasBattery`**, and a "no battery" note otherwise. The battery card was moved out of Devices.
   - Screen tab: when `state.screen` is false it shows a **Tailscale SSH** panel instead: a copyable `tailscale ssh user@host`, steps for phone SSH apps, and an OpenSSH note for Windows.
   - State now includes `user` and `selfName`.
5. **Selftest:** new checks for power API validation (400s) and the new state fields.
6. **Installers in the repo root**
   - `install.sh` (Linux, Pi, macOS) and `install.ps1` (Windows). They:
     - check for Java and offer to install it (apt/dnf/pacman/brew, or winget Temurin)
     - check for Tailscale and offer to install it (official script, or winget)
     - download `activedesk.jar` from the latest GitHub release and **verify SHA-256** against `SHA256SUMS.txt`
     - add the `activedesk` command, then launch `activedesk setup`
   - Environment overrides: `ACTIVEDESK_JAR`, `ACTIVEDESK_VERSION`, `ACTIVEDESK_YES`.
   - `install.ps1` was tested end to end on Windows with a scratch LOCALAPPDATA, APPDATA and home; the user PATH was restored afterwards.
7. **CI:** new `installer` job in `.github/workflows/ci.yml` runs both installers on ubuntu x64/arm, macOS and Windows.
8. **Docs tooling:** `docs/tools/screenshots.mjs`. It uses Node 24 and headless Chrome over CDP, with no npm packages. It swaps `/api/state`, `/api/chat` and `/api/screen` for **demo data**, so no real desktop, tailnet names, IPs or email reach the pictures. It writes `docs/img/*.png` (desktop and phone shots of every tab, plus the Pi no-screen and no-battery variants).

## Not done yet (in order)

1. **Take the screenshots.** A demo node is running at `http://127.0.0.1:47911` (scratch home `…/scratchpad/docshome`, PIN in `…/scratchpad/docspin.txt`). Run:
   `node docs/tools/screenshots.mjs http://127.0.0.1:47911 <PIN>`
   Then **look at every PNG** to check for any personal data. Afterwards kill the demo node:
   `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? CommandLine -match 'activedesk.jar run' | % { Stop-Process -Id $_.ProcessId -Force }`
2. **Build the GitHub Pages site in `docs/`** in the style of https://pymite6941.github.io/prompt-guard/.
   - Copy its `site.css` exactly: dark `#101827`, accent `#73e0bd`, `.hero`, `.eyebrow`, `.grid`, `.feature`, `.code-card`, `.docs-main`, `.page-crumb`, `.tip`, `.warning`. Add a `figure img` style for the screenshots.
   - Pages, with a header nav (Features · Quick start · Setup · Guide · FAQ · GitHub):
     - `index.html`: hero "Use all your devices from your phone", a features grid, the one-line installs, screenshots.
     - `setup.html`: install per OS, phone setup (Tailscale app → open `http://<ip>:47801` → Add to Home Screen), what the wizard asks.
     - `guide.html`: one section per tab with its screenshot, keep-awake, the no-screen Tailscale SSH path, power and battery.
     - `faq.html`: troubleshooting, limits, security in plain words, "is it free?" (yes: Tailscale Personal, GitHub, OpenJDK).
   - **Keep the wording super simple** (the user's explicit ask): short sentences, numbered steps.
   - **Add `.github/workflows/pages.yml`** (actions/upload-pages-artifact plus deploy-pages). It copies `docs/` plus the root `install.sh` and `install.ps1`, so these work:
     - `curl -fsSL https://pymite6941.github.io/ActiveDesk/install.sh | sh`
     - `irm https://pymite6941.github.io/ActiveDesk/install.ps1 | iex`
3. **BLOCKER: the user must decide.** The repo is **private**. GitHub Pages on a private repo needs a paid plan, and the installers can't download release assets from a private repo anonymously. The user wants **everything free**, so either:
   - (a) make `PyMite6941/ActiveDesk` public (no secrets are in the repo; check before flipping), or
   - (b) publish the docs and releases from a separate public repo.

   **Ask the user; don't flip visibility yourself.** After that, enable Pages with source = GitHub Actions and tag `v1.0.0` so `release.yml` publishes `activedesk.jar` and `SHA256SUMS.txt`, which the installers need.
4. **Update `README.md` and `SECURITY.md`:**
   - the power tab, auto saver and `power install`
   - the setup wizard and one-line installs
   - keep-awake input nudging
   - the Tailscale SSH fallback
   - the "all free" statement
   - a link to the docs site
5. **Commit in logical chunks** (power, UI, installers, docs). End each message with:
   `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
   Push to `main` and check that CI is green (the build-test matrix plus the new installer job). The `installer` job is new and untested in CI: if it fails, read the logs (e.g. a `systemctl --user` absence on runners is harmless; the wizard only writes files).
6. **Update the memory file** `~/.claude/projects/C--Users-gresh-OneDrive-------/memory/project_activedesk.md`.

## Tell the user, honestly

**Not tested live:**
- `power install` and `battery install`: they need someone to click UAC on the laptop.
- `schtasks /run` as non-admin.
- Linux, Pi and macOS power backends: only CI compiles and selftests them.
- The installers on real Linux and macOS (CI covers them once it runs).

**Hard limits:**
- UAC and secure-desktop prompts can't be approved remotely.
- No app can type into the Windows or macOS lock screen; RDP over Tailscale covers Windows.
- macOS has no charge limit and no app-controlled brightness.
- Lenovo, HP and similar laptops have no software charge-limit API on Windows.
- Tailscale SSH can't serve *on* Windows (use the built-in OpenSSH Server).
