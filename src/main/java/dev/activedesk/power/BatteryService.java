package dev.activedesk.power;

import dev.activedesk.Config;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Battery charge range ("only charge between 40 % and 80 %"), applied only while ActiveDesk runs.
 *
 * No OS has a vendor-neutral, unprivileged way to pause charging, so this picks a backend per device:
 *
 *   Linux    the kernel's standard sysfs knobs on the laptop battery (ThinkPad, ASUS, Dell, Framework, Huawei,
 *            LG, Samsung, MSI, System76 ...):
 *              charge_control_start_threshold + end_threshold  -> native range, firmware does the hysteresis
 *              only end_threshold                               -> range emulated (end := min while holding)
 *              charge_behaviour with inhibit-charge             -> range emulated (inhibit while holding)
 *            Writing needs root once: the one-time setup (PowerSetup) adds a udev rule that lets the user's
 *            group write just those files. The node itself never runs as root.
 *   Windows  an elevated guard script (resources/power/battery-guard.ps1) started on demand from a scheduled
 *            task that the one-time setup registers with one UAC prompt. Supports ASUS (WMI) and Dell
 *            (Dell Command | Configure); the guard exits and restores normal charging when the node stops.
 *   macOS    not supported: needs a root helper writing undocumented SMC keys.
 *
 * Battery presence comes from PowerService, so a laptop whose battery has no charge control still shows
 * up as "has a battery, range not supported". The original thresholds are remembered on disk, so even
 * after a crash the next start restores them.
 */
public final class BatteryService {
    public static final String TASK = "\\ActiveDesk\\Battery";
    public static final int FLOOR = 20, GAP = 5;

    private final Config config;
    private final PowerService power;
    private final Path pidFile;
    private final Path originals;

    private volatile boolean detected;
    private volatile String windowsVendor = "";   // "asus" | "dell" | ""
    private volatile boolean taskInstalled;
    private Sysfs sysfs;

    private volatile String message = "";
    private volatile boolean holding;
    private boolean firstLinuxTick = true;
    private long lastLaunch;
    /** Windows: run the guard once with the range off, which makes it put the firmware back to normal charging. */
    private volatile boolean restorePass = true;

    public BatteryService(Config config, PowerService power) {
        this.config = config;
        this.power = power;
        this.pidFile = config.home().resolve("node.pid");
        this.originals = config.home().resolve("battery-original.properties");
    }

    public boolean enabled() { return config.getBool("battery.enabled"); }
    public int min() { return config.getInt("battery.min", 40); }
    public int max() { return config.getInt("battery.max", 80); }

    /** Validate a range. Returns null when fine, otherwise the reason. */
    public static String invalid(int min, int max) {
        if (max > 100 || max < FLOOR + GAP) return "max must be between " + (FLOOR + GAP) + " and 100";
        if (min < FLOOR) return "min must be at least " + FLOOR;
        if (max - min < GAP) return "keep at least " + GAP + " points between min and max";
        return null;
    }

    public void start() {
        try {
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
        } catch (IOException e) {
            Log.debug("battery: cannot write pid file: " + e.getMessage());
        }
    }

    /** Change the range / switch it on or off (persisted, applied right away). */
    public synchronized void set(boolean enabled, int min, int max) throws IOException {
        String bad = invalid(min, max);
        if (bad != null) throw new IllegalArgumentException(bad);
        if (enabled && !supported()) throw new IllegalArgumentException("this device's battery has no charge control ActiveDesk can drive");
        config.update("battery.min", Integer.toString(min));
        config.update("battery.max", Integer.toString(max));
        boolean wasOn = enabled();
        config.update("battery.enabled", Boolean.toString(enabled));
        if (wasOn && !enabled) restorePass = true;
        firstLinuxTick = true;
        tick();
    }

    /** After the one-time setup ran: look again. */
    public synchronized void redetect() {
        detected = false;
        tick();
    }

    /** Every ~30 s from the node (after PowerService refreshed the battery status). */
    public synchronized void tick() {
        try {
            if (!detected) detect();
            if (!power.hasBattery()) return;
            if (Proc.windows()) tickWindows();
            else if (sysfs != null) tickLinux();
        } catch (RuntimeException | IOException e) {
            message = "battery control error: " + e.getMessage();
            Log.debug(message);
        }
    }

    public synchronized void close() {
        try {
            Files.deleteIfExists(pidFile); // the Windows guard sees this and restores normal charging
        } catch (IOException ignored) { }
        if (sysfs != null) restoreLinux();
    }

    public boolean supported() {
        if (Proc.mac()) return false;
        if (Proc.windows()) return !windowsVendor.isEmpty();
        return sysfs != null;
    }

    /** Whether the one-time privileged setup is still missing for the range. */
    public boolean needsSetup() {
        if (!supported()) return false;
        if (Proc.windows()) return !taskInstalled;
        return !sysfs.writable();
    }

    public String windowsVendor() { return windowsVendor; }

    // ------------------------------------------------------------------ detection

    private void detect() {
        detected = true;
        if (Proc.windows()) {
            String out = Proc.powershell(45, "'asus=' + [bool](Get-CimClass -Namespace root/wmi -ClassName AsusAtkWmi_WMNB -ErrorAction SilentlyContinue)").out();
            if (!out.contains("asus=")) {
                detected = false; // WMI was slow or PowerShell failed: try again next tick rather than saying "unsupported"
                Log.debug("battery detection inconclusive: " + out.strip());
                return;
            }
            if (out.contains("asus=True")) windowsVendor = "asus";
            else if (dellCctk() != null) windowsVendor = "dell";
            else windowsVendor = "";
            taskInstalled = Proc.run(20, "schtasks", "/query", "/tn", TASK).ok();
        } else if (Proc.linux()) {
            sysfs = Sysfs.find();
            if (sysfs != null && Files.exists(originals)) restoreLinux(); // a previous run crashed while holding
        }
    }

    static Path dellCctk() {
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)"}) {
            String base = System.getenv(env);
            if (base == null) continue;
            Path p = Path.of(base, "Dell", "Command Configure", "X86_64", "cctk.exe");
            if (Files.exists(p)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ Windows

    static Path statusFile() {
        return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "ActiveDesk", "battery-status.json");
    }

    private Map<String, Object> guardStatus() {
        try {
            return Json.obj(Files.readString(statusFile(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private void tickWindows() throws IOException {
        if (windowsVendor.isEmpty()) {
            message = "this laptop has no charge-limit interface ActiveDesk can drive (Windows: ASUS, or Dell with Dell Command | Configure). Use the vendor app or BIOS battery setting.";
            return;
        }
        if (!taskInstalled) {
            taskInstalled = Proc.run(20, "schtasks", "/query", "/tn", TASK).ok();
            if (!taskInstalled) { message = "needs the one-time setup (Set up button, or 'activedesk power install'); asks for admin once"; return; }
        }
        Map<String, Object> st = guardStatus();
        if (!enabled()) {
            // Repair pass at every start and every switch-off: a limit left behind by a crash, a killed
            // guard or a failed restore would otherwise keep the laptop from charging until a reboot.
            boolean guardUp = Json.bool(st, "running", false) && System.currentTimeMillis() - Json.num(st, "updated", 0) < 75_000;
            if (restorePass && !guardUp) {
                restorePass = false;
                Proc.Result r = Proc.run(20, "schtasks", "/run", "/tn", TASK);
                if (!r.ok()) Log.info("battery: could not run the restore pass: " + r.out().trim());
            }
            if (guardUp) message = "switching off - restoring normal charging...";
            else if (st.containsKey("restored") && !Json.bool(st, "restored", true)) message = Json.str(st, "message", "could not restore normal charging");
            else if (System.currentTimeMillis() - Json.num(st, "restoredAt", 0) < 600_000)
                message = "off - normal charging restored (if it still doesn't charge, unplug the charger and plug it back in: some laptops only apply the change then)";
            else message = "off - normal charging";
            return;
        }
        boolean fresh = System.currentTimeMillis() - Json.num(st, "updated", 0) < 75_000;
        if (Json.bool(st, "running", false) && fresh) {
            message = Json.str(st, "message", "");
            holding = Json.bool(st, "holding", false);
            return;
        }
        if (System.currentTimeMillis() - lastLaunch > 60_000) {
            lastLaunch = System.currentTimeMillis();
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
            Proc.Result r = Proc.run(20, "schtasks", "/run", "/tn", TASK);
            message = r.ok() ? "starting battery guard..." : "could not start the battery guard task: " + r.out().trim();
        } else if (!st.isEmpty()) {
            message = Json.str(st, "message", message);
        }
    }

    /**
     * `activedesk battery reset`: switch the range off and put the battery back to normal charging NOW,
     * whether or not ActiveDesk is running. Windows: runs the guard task (no prompt); if that is missing
     * or fails, one admin prompt runs the restore directly. Linux: writes back the saved thresholds.
     */
    public static int reset(Config config, java.util.function.Consumer<String> out) throws IOException, InterruptedException {
        config.update("battery.enabled", "false");
        if (Proc.mac()) { out.accept("macOS: ActiveDesk never changes charging here. Nothing to reset."); return 0; }
        if (Proc.linux()) {
            Sysfs sf = Sysfs.find();
            Path orig = config.home().resolve("battery-original.properties");
            if (sf == null || !Files.exists(orig)) { out.accept("Normal charging: ActiveDesk has no changed thresholds to undo."); return 0; }
            BatteryService b = new BatteryService(config, new PowerService(config));
            b.sysfs = sf;
            b.restoreLinux();
            if (Files.exists(orig)) {
                out.accept("Could not write the battery thresholds (the one-time setup is missing). Run: sudo activedesk battery reset  - or restart the computer.");
                return 1;
            }
            out.accept("Normal charging restored (original thresholds written back).");
            return 0;
        }
        if (restoreViaTask(out)) return 0;
        int rc = Proc.elevate(RESTORE_PS, 120);
        out.accept(rc == 0 ? "Normal charging restored. If it still doesn't charge, unplug the charger and plug it back in (some laptops only apply the change then)."
                : rc == 3 ? "This laptop has no charge limit ActiveDesk controls. Nothing to reset."
                : rc == 2 ? "Cancelled at the admin prompt. Nothing was changed. (Restarting the laptop also resets the ASUS limit.)"
                : "Could not restore (exit " + rc + "). Restarting the laptop also resets the ASUS limit.");
        return rc == 3 ? 0 : rc;
    }

    /**
     * Windows, no prompt: runs the installed guard task with the range off (which always restores normal
     * charging) and waits for its confirmation. False when the task or its script is missing, or it did
     * not confirm within ~20 s: the caller then restores with one admin prompt instead.
     */
    static boolean restoreViaTask(java.util.function.Consumer<String> out) throws InterruptedException {
        String pf = System.getenv("ProgramFiles");
        if (pf == null || !Files.exists(Path.of(pf, "ActiveDesk", "battery-guard.ps1"))
                || !Proc.run(20, "schtasks", "/query", "/tn", TASK).ok()) {
            out.accept("The battery task isn't (fully) installed, so Windows asks for admin permission once to restore normal charging.");
            return false;
        }
        out.accept("Restoring normal charging...");
        long t0 = System.currentTimeMillis();
        Proc.Result r = Proc.run(20, "schtasks", "/run", "/tn", TASK);
        if (r.ok()) {
            for (int i = 0; i < 40; i++) {
                Thread.sleep(500);
                Map<String, Object> st;
                try { st = Json.obj(Files.readString(statusFile(), StandardCharsets.UTF_8)); } catch (IOException | RuntimeException e) { continue; }
                if (Json.num(st, "updated", 0) < t0 || Json.bool(st, "running", false)) continue;
                if (!Json.bool(st, "supported", true)) { out.accept("This laptop has no charge limit ActiveDesk controls. Nothing to reset."); return true; }
                if (Json.bool(st, "restored", false)) {
                    out.accept("Normal charging restored. If it still doesn't charge, unplug the charger and plug it back in (some laptops only apply the change then).");
                    return true;
                }
                break;
            }
        }
        out.accept("The battery task didn't confirm, so Windows asks for admin permission once to restore normal charging.");
        return false;
    }

    /**
     * Elevated restore, same effect as the guard's: ASUS limit 100 %, Dell back to the saved setting.
     * Sets $restoreRc: 0 restored, 1 failed, 3 nothing to control. Never exits, so it can be combined.
     */
    static final String RESTORE_BODY = """
            $restoreRc = 1
            try {
              $ErrorActionPreference = 'Stop'
              $adDir = Join-Path $env:ProgramData 'ActiveDesk'
              $atk = Get-CimInstance -Namespace root/wmi -ClassName AsusAtkWmi_WMNB -ErrorAction SilentlyContinue | Select-Object -First 1
              if ($atk) {
                for ($i = 0; $i -lt 5; $i++) {
                  try { Invoke-CimMethod -InputObject $atk -MethodName DEVS -Arguments @{ Device_ID = [uint32]0x00120057; Control_status = [uint32]100 } | Out-Null; break }
                  catch { if ($i -eq 4) { throw }; Start-Sleep 2 }
                }
                $restoreRc = 0
              } else {
                $cctk = @("$env:ProgramFiles\\Dell\\Command Configure\\X86_64\\cctk.exe", "${env:ProgramFiles(x86)}\\Dell\\Command Configure\\X86_64\\cctk.exe") |
                        Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
                if (-not $cctk) { $restoreRc = 3 }
                else {
                  $f = Join-Path $adDir 'dell-original.txt'
                  $o = if (Test-Path -LiteralPath $f) { ([IO.File]::ReadAllText($f)).Trim() } else { 'Adaptive' }
                  if ($o -notmatch '^(Standard|Express|Adaptive|PrimAcUse|Custom:\\d{1,3}-\\d{1,3})$') { $o = 'Adaptive' }
                  & $cctk "--PrimaryBattChargeCfg=$o" | Out-Null
                  if ($LASTEXITCODE -ne 0) { throw "cctk failed ($LASTEXITCODE)" }
                  Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue
                  $restoreRc = 0
                }
              }
              if ($restoreRc -eq 0) {
                New-Item -ItemType Directory -Force -Path $adDir | Out-Null
                $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                [IO.File]::WriteAllText((Join-Path $adDir 'battery-status.json'), (@{ running = $false; supported = $true; restored = $true; restoredAt = $now; updated = $now; message = 'off - normal charging restored' } | ConvertTo-Json -Compress))
              }
            } catch { $restoreRc = 1 }
            """;

    /** One-shot elevated restore for `battery reset`. Exit 3 = nothing to control. */
    static final String RESTORE_PS = RESTORE_BODY + "exit $restoreRc\n";

    // ------------------------------------------------------------------ Linux

    private void tickLinux() throws IOException {
        int percent = power.percent();
        if (!sysfs.writable()) {
            message = "needs the one-time setup (Set up button, or 'activedesk power install'); asks for your password once";
            return;
        }
        if (!enabled()) {
            if (Files.exists(originals)) restoreLinux();
            message = "off - normal charging";
            return;
        }
        saveOriginals();
        int lo = min(), hi = max();
        if (sysfs.start != null && sysfs.end != null) {
            int curEnd = Proc.readInt(sysfs.end, 100);
            // the kernel rejects start >= end, so order the writes
            if (lo >= curEnd) { sysfs.write(sysfs.end, hi); sysfs.write(sysfs.start, lo); }
            else { sysfs.write(sysfs.start, lo); sysfs.write(sysfs.end, hi); }
            holding = false;
            message = "firmware keeps the battery between " + lo + "% and " + hi + "%";
            return;
        }
        if (percent < 0) { message = "battery level unknown"; return; }
        if (percent >= hi) holding = true;
        else if (percent <= lo) holding = false;
        else if (firstLinuxTick) holding = false;
        firstLinuxTick = false;
        if (sysfs.end != null) sysfs.write(sysfs.end, holding ? lo : hi);
        else sysfs.writeRaw(sysfs.behaviour, holding ? "inhibit-charge" : "auto");
        message = holding ? "charging paused at " + percent + "% until it drops to " + lo + "%" : "charging allowed up to " + hi + "%";
    }

    private void saveOriginals() throws IOException {
        if (Files.exists(originals)) return;
        Properties p = new Properties();
        if (sysfs.start != null) p.setProperty("start", Integer.toString(Proc.readInt(sysfs.start, 0)));
        if (sysfs.end != null) p.setProperty("end", Integer.toString(Proc.readInt(sysfs.end, 100)));
        if (sysfs.behaviour != null) p.setProperty("behaviour", sysfs.currentBehaviour());
        try (var w = Files.newBufferedWriter(originals, StandardCharsets.UTF_8)) { p.store(w, "battery thresholds before ActiveDesk changed them"); }
    }

    private void restoreLinux() {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(originals, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return;
        }
        try {
            if (sysfs.writable()) {
                int start = Integer.parseInt(p.getProperty("start", "0")), end = Integer.parseInt(p.getProperty("end", "100"));
                // widen first so every intermediate state is valid
                if (sysfs.end != null && p.containsKey("end")) sysfs.write(sysfs.end, Math.max(end, Proc.readInt(sysfs.end, 100)));
                if (sysfs.start != null && p.containsKey("start")) sysfs.write(sysfs.start, start);
                if (sysfs.end != null && p.containsKey("end")) sysfs.write(sysfs.end, end);
                String b = p.getProperty("behaviour", "");
                if (sysfs.behaviour != null && b.matches("[a-z-]{1,20}")) sysfs.writeRaw(sysfs.behaviour, b);
            }
            Files.deleteIfExists(originals);
            Log.info("battery: original charge thresholds restored");
        } catch (IOException | RuntimeException e) {
            Log.info("battery: could not restore thresholds: " + e.getMessage());
        }
    }

    /** The laptop battery's sysfs folder and whichever control files its driver offers. */
    static final class Sysfs {
        final Path dir, start, end, behaviour;

        private Sysfs(Path dir) {
            this.dir = dir;
            this.start = first(dir, "charge_control_start_threshold", "charge_start_threshold");
            this.end = first(dir, "charge_control_end_threshold", "charge_stop_threshold");
            Path b = dir.resolve("charge_behaviour");
            this.behaviour = Files.exists(b) && Proc.read(b).contains("inhibit-charge") ? b : null;
        }

        static Sysfs find() {
            Path root = Path.of("/sys/class/power_supply");
            if (!Files.isDirectory(root)) return null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path d : ds) {
                    // the system battery: type Battery and not a peripheral (mouse, pen: scope=Device)
                    if (!"Battery".equals(Proc.read(d.resolve("type")))) continue;
                    if ("Device".equals(Proc.read(d.resolve("scope")))) continue;
                    Sysfs s = new Sysfs(d);
                    if (s.start != null || s.end != null || s.behaviour != null) return s;
                }
            } catch (IOException ignored) { }
            return null;
        }

        boolean writable() {
            if (start != null || end != null) {
                return (start == null || Files.isWritable(start)) && (end == null || Files.isWritable(end));
            }
            return behaviour != null && Files.isWritable(behaviour);
        }

        String currentBehaviour() {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([a-z-]+)]").matcher(Proc.read(behaviour));
            return m.find() ? m.group(1) : "auto";
        }

        void write(Path f, int v) throws IOException { writeRaw(f, Integer.toString(Math.max(0, Math.min(100, v)))); }

        void writeRaw(Path f, String v) throws IOException {
            if (Proc.read(f).equals(v)) return;
            Files.writeString(f, v);
        }

        private static Path first(Path dir, String... names) {
            for (String n : names) if (Files.exists(dir.resolve(n))) return dir.resolve(n);
            return null;
        }
    }

    // ------------------------------------------------------------------ state for UI / CLI

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean has = power.hasBattery();
        String backend = !supported() ? "" : Proc.windows() ? windowsVendor + " (Windows)" : "linux sysfs (" + sysfs.dir.getFileName() + ")";
        m.put("hasBattery", has);
        m.put("supported", has && supported());
        m.put("needsSetup", has && needsSetup());
        m.put("backend", backend);
        m.put("enabled", enabled());
        m.put("min", min());
        m.put("max", max());
        m.put("percent", power.percent());
        m.put("holding", holding);
        m.put("message", !power.refreshed() ? "detecting..."
                : !has ? "no battery on this device"
                : Proc.mac() ? "macOS has no supported way for apps to pause charging (needs a root SMC helper). Use System Settings > Battery > Optimized Battery Charging."
                : !supported() && Proc.windows() ? "this laptop has no charge-limit interface ActiveDesk can drive (Windows: ASUS, or Dell with Dell Command | Configure). Use the vendor app or BIOS battery setting."
                : !supported() ? "this battery's kernel driver offers no charge control"
                : message);
        return m;
    }
}
