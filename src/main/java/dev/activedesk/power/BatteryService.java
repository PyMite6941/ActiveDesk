package dev.activedesk.power;

import dev.activedesk.Config;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

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
 *            Writing needs root once: `activedesk battery install` (runs sudo) adds a udev rule that lets the user's
 *            group write just those files. The node itself never runs as root.
 *   Windows  an elevated guard script (resources/power/battery-guard.ps1) started on demand from a scheduled
 *            task that `activedesk battery install` registers with one UAC prompt. Supports ASUS (WMI) and
 *            Dell (Dell Command | Configure); the guard exits and restores normal charging when the node stops.
 *   macOS    not supported: needs a root helper writing undocumented SMC keys.
 *
 * The original thresholds are remembered on disk, so even after a crash the next start (or uninstall)
 * restores them.
 */
public final class BatteryService {
    public static final String TASK = "\\ActiveDesk\\Battery";
    public static final int FLOOR = 20, GAP = 5;

    private final Config config;
    private final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private final Path pidFile;
    private final Path originals;

    private volatile boolean detected;
    private volatile boolean hasBattery;
    private volatile String windowsVendor = "";   // "asus" | "dell" | ""
    private volatile boolean taskInstalled;
    private Sysfs sysfs;

    private volatile String message = "";
    private volatile int percent = -1;
    private volatile boolean holding;
    private boolean firstLinuxTick = true;
    private long lastLaunch;

    public BatteryService(Config config) {
        this.config = config;
        this.pidFile = config.home().resolve("node.pid");
        this.originals = config.home().resolve("battery-original.properties");
    }

    private boolean windows() { return os.contains("win"); }
    private boolean mac() { return os.contains("mac"); }

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
        config.update("battery.min", Integer.toString(min));
        config.update("battery.max", Integer.toString(max));
        config.update("battery.enabled", Boolean.toString(enabled));
        firstLinuxTick = true;
        tick();
    }

    /** Every ~30 s from the node. */
    public synchronized void tick() {
        try {
            if (!detected) detect();
            if (!hasBattery) return;
            if (windows()) tickWindows();
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

    // ------------------------------------------------------------------ detection

    private void detect() {
        detected = true;
        if (windows()) {
            String out = powershell("$b=@(Get-CimInstance Win32_Battery).Count;"
                    + "$a=[bool](Get-CimClass -Namespace root/wmi -ClassName AsusAtkWmi_WMNB -ErrorAction SilentlyContinue);"
                    + "'battery=' + $b + ' asus=' + $a", 20); // no double quotes: Windows arg quoting mangles them
            hasBattery = out.matches("(?s).*battery=[1-9].*");
            if (out.contains("asus=True")) windowsVendor = "asus";
            else if (Files.exists(Path.of(env("ProgramFiles"), "Dell", "Command Configure", "X86_64", "cctk.exe"))
                    || Files.exists(Path.of(env("ProgramFiles(x86)"), "Dell", "Command Configure", "X86_64", "cctk.exe")))
                windowsVendor = "dell";
            taskInstalled = run(20, "schtasks", "/query", "/tn", TASK) == 0;
        } else if (!mac()) {
            sysfs = Sysfs.find();
            hasBattery = sysfs != null;
            if (sysfs != null && Files.exists(originals)) restoreLinux(); // a previous run crashed while holding
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "C:\\Program Files" : v;
    }

    // ------------------------------------------------------------------ Windows

    private Path statusFile() { return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "ActiveDesk", "battery-status.json"); }

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
            taskInstalled = run(20, "schtasks", "/query", "/tn", TASK) == 0;
            if (!taskInstalled) { message = "needs one-time setup: run 'activedesk battery install' on this computer (asks for admin once)"; return; }
        }
        if (!enabled()) { message = "off - normal charging"; return; }
        Map<String, Object> st = guardStatus();
        boolean fresh = System.currentTimeMillis() - Json.num(st, "updated", 0) < 75_000;
        if (Json.bool(st, "running", false) && fresh) {
            message = Json.str(st, "message", "");
            percent = (int) Json.num(st, "percent", -1);
            holding = Json.bool(st, "holding", false);
            return;
        }
        if (System.currentTimeMillis() - lastLaunch > 60_000) {
            lastLaunch = System.currentTimeMillis();
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
            int rc = run(20, "schtasks", "/run", "/tn", TASK);
            message = rc == 0 ? "starting battery guard..." : "could not start the battery guard task (schtasks exit " + rc + ")";
        } else if (!st.isEmpty()) {
            message = Json.str(st, "message", message);
        }
    }

    // ------------------------------------------------------------------ Linux

    private void tickLinux() throws IOException {
        percent = sysfs.capacity();
        if (!sysfs.writable()) {
            message = "needs one-time setup: run 'activedesk battery install' on this computer (asks for sudo once)";
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
            int curEnd = sysfs.readInt(sysfs.end, 100);
            // the kernel rejects start >= end, so order the writes
            if (lo >= curEnd) { sysfs.write(sysfs.end, hi); sysfs.write(sysfs.start, lo); }
            else { sysfs.write(sysfs.start, lo); sysfs.write(sysfs.end, hi); }
            holding = false;
            message = "firmware keeps the battery between " + lo + "% and " + hi + "%";
            return;
        }
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
        if (sysfs.start != null) p.setProperty("start", Integer.toString(sysfs.readInt(sysfs.start, 0)));
        if (sysfs.end != null) p.setProperty("end", Integer.toString(sysfs.readInt(sysfs.end, 100)));
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
                if (sysfs.end != null && p.containsKey("end")) sysfs.write(sysfs.end, Math.max(end, sysfs.readInt(sysfs.end, 100)));
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
            this.behaviour = Files.exists(b) && read(b).contains("inhibit-charge") ? b : null;
        }

        static Sysfs find() {
            Path root = Path.of("/sys/class/power_supply");
            if (!Files.isDirectory(root)) return null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path d : ds) {
                    // the system battery: type Battery and not a peripheral (mouse, pen: scope=Device)
                    if (!"Battery".equals(read(d.resolve("type")).trim())) continue;
                    if ("Device".equals(read(d.resolve("scope")).trim())) continue;
                    Sysfs s = new Sysfs(d);
                    if (s.start != null || s.end != null || s.behaviour != null) return s;
                }
            } catch (IOException ignored) { }
            return null;
        }

        boolean writable() {
            return (start == null || Files.isWritable(start)) && (end == null || Files.isWritable(end))
                    && (behaviour == null || start != null || end != null || Files.isWritable(behaviour));
        }

        int capacity() { return readInt(dir.resolve("capacity"), -1); }

        String currentBehaviour() {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([a-z-]+)]").matcher(read(behaviour));
            return m.find() ? m.group(1) : "auto";
        }

        int readInt(Path f, int def) {
            try { return Integer.parseInt(read(f).trim()); } catch (NumberFormatException e) { return def; }
        }

        void write(Path f, int v) throws IOException { writeRaw(f, Integer.toString(Math.max(0, Math.min(100, v)))); }

        void writeRaw(Path f, String v) throws IOException {
            if (read(f).trim().equals(v)) return;
            Files.writeString(f, v);
        }

        private static Path first(Path dir, String... names) {
            for (String n : names) if (Files.exists(dir.resolve(n))) return dir.resolve(n);
            return null;
        }

        private static String read(Path f) {
            try { return Files.readString(f); } catch (IOException e) { return ""; }
        }
    }

    // ------------------------------------------------------------------ state for UI / CLI

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        String backend;
        boolean supported;
        if (mac()) { supported = false; backend = ""; }
        else if (windows()) { supported = !windowsVendor.isEmpty(); backend = windowsVendor.isEmpty() ? "" : windowsVendor + " (Windows)"; }
        else { supported = sysfs != null; backend = sysfs == null ? "" : "linux sysfs (" + sysfs.dir.getFileName() + ")"; }
        m.put("hasBattery", hasBattery);
        m.put("supported", supported && hasBattery);
        m.put("backend", backend);
        m.put("enabled", enabled());
        m.put("min", min());
        m.put("max", max());
        m.put("percent", percent);
        m.put("holding", holding);
        m.put("message", mac() ? "macOS has no supported way for apps to pause charging (needs a root SMC helper). Use System Settings > Battery > Optimized Battery Charging."
                : !detected ? "detecting..." : !hasBattery ? "no laptop battery on this device" : message);
        return m;
    }

    // ------------------------------------------------------------------ one-time privileged setup (CLI)

    /** `activedesk battery install`: one-time privileged setup for this computer. Returns a process exit code. */
    public static int install(Config config) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            System.out.println("macOS: not supported (pausing charging needs a root helper writing undocumented SMC keys).");
            return 1;
        }
        if (os.contains("win")) return installWindows(config);
        return installLinux();
    }

    public static int uninstall(Config config) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        config.update("battery.enabled", "false");
        if (os.contains("win")) {
            System.out.println("Switching the range off and waiting for the guard to restore normal charging...");
            Path status = Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "ActiveDesk", "battery-status.json");
            for (int i = 0; i < 45; i++) {
                try {
                    if (!Json.bool(Json.obj(Files.readString(status)), "running", false)) break;
                } catch (IOException | RuntimeException e) { break; }
                Thread.sleep(1000);
            }
            String script = "$ErrorActionPreference='Stop'; try {"
                    + " Unregister-ScheduledTask -TaskName 'Battery' -TaskPath '\\ActiveDesk\\' -Confirm:$false -ErrorAction SilentlyContinue;"
                    + " Remove-Item -LiteralPath (Join-Path $env:ProgramFiles 'ActiveDesk\\battery-guard.ps1') -Force -ErrorAction SilentlyContinue;"
                    + " exit 0 } catch { exit 1 }";
            int rc = elevate(script);
            System.out.println(rc == 0 ? "Removed the battery guard." : "Uninstall failed or was cancelled (exit " + rc + ").");
            return rc;
        }
        if (!os.contains("mac")) {
            System.out.println("Range switched off; ActiveDesk restores the original thresholds on its next tick.");
            System.out.println("To also remove write access:  sudo rm " + UDEV_RULE + " && sudo udevadm control --reload");
        }
        return 0;
    }

    private static int installWindows(Config config) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("activedesk-battery-guard", ".ps1");
        try {
            try (InputStream in = BatteryService.class.getResourceAsStream("/power/battery-guard.ps1")) {
                if (in == null) throw new IOException("guard script missing from the jar");
                Files.write(tmp, in.readAllBytes());
            }
            String user = System.getenv().getOrDefault("USERDOMAIN", ".") + "\\" + System.getenv().getOrDefault("USERNAME", "");
            String home = config.home().toAbsolutePath().toString();
            String script = String.join("\n",
                    "$ErrorActionPreference = 'Stop'",
                    "try {",
                    "  $dir = Join-Path $env:ProgramFiles 'ActiveDesk'",   // admin-only writable: the elevated script can't be swapped by user-level code
                    "  New-Item -ItemType Directory -Force -Path $dir | Out-Null",
                    "  $dest = Join-Path $dir 'battery-guard.ps1'",
                    "  Copy-Item -LiteralPath " + q(tmp.toString()) + " -Destination $dest -Force",
                    "  $arg = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File \"' + $dest + '\" -UserHome \"' + " + q(home) + " + '\"'",
                    "  $a = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg",
                    "  $p = New-ScheduledTaskPrincipal -UserId " + q(user) + " -LogonType Interactive -RunLevel Highest",
                    "  $s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew",
                    "  Register-ScheduledTask -TaskName 'Battery' -TaskPath '\\ActiveDesk\\' -Action $a -Principal $p -Settings $s -Force | Out-Null",
                    "  exit 0",
                    "} catch { exit 1 }");
            System.out.println("Windows will ask for admin permission once to install the battery guard...");
            int rc = elevate(script);
            if (rc != 0) {
                System.out.println("Install failed or was cancelled (exit " + rc + ").");
                return rc;
            }
            if (run(20, "schtasks", "/query", "/tn", TASK) != 0) {
                System.out.println("The task was not registered.");
                return 1;
            }
            System.out.println("Installed. Turn the range on in the web UI (Devices tab) or with: activedesk battery set 40 80");
            return 0;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static final String UDEV_RULE = "/etc/udev/rules.d/90-activedesk-battery.rules";

    private static int installLinux() throws IOException, InterruptedException {
        Sysfs s = Sysfs.find();
        if (s == null) {
            System.out.println("No laptop battery with charge-control support found in /sys/class/power_supply (your kernel driver may not offer it).");
            return 1;
        }
        String group = output(10, "id", "-gn").trim();
        if (!group.matches("[a-z_][a-z0-9_-]{0,31}")) {
            System.out.println("Unexpected group name '" + group + "'.");
            return 1;
        }
        String files = "/sys%p/charge_control_start_threshold /sys%p/charge_control_end_threshold /sys%p/charge_start_threshold /sys%p/charge_stop_threshold /sys%p/charge_behaviour";
        String rule = "SUBSYSTEM==\"power_supply\", ACTION!=\"remove\", ATTR{type}==\"Battery\", RUN+=\"/bin/sh -c 'chgrp " + group + " " + files
                + " 2>/dev/null; chmod g+w " + files + " 2>/dev/null; true'\"\n";
        Path tmp = Files.createTempFile("activedesk", ".rules");
        Files.writeString(tmp, "# ActiveDesk: let group '" + group + "' change battery charge thresholds (nothing else)\n" + rule);
        System.out.println("Installing " + UDEV_RULE + " (lets group '" + group + "' write only the battery charge-control files). sudo may ask for your password.");
        int rc = new ProcessBuilder("sudo", "sh", "-c",
                "install -m 0644 \"$1\" " + UDEV_RULE + " && udevadm control --reload && udevadm trigger --subsystem-match=power_supply --action=change",
                "sh", tmp.toString()).inheritIO().start().waitFor();
        Files.deleteIfExists(tmp);
        System.out.println(rc == 0 ? "Installed. Turn the range on in the web UI (Devices tab) or with: activedesk battery set 40 80" : "Install failed (exit " + rc + ").");
        return rc;
    }

    // ------------------------------------------------------------------ process helpers

    /** Single-quoted PowerShell literal. */
    private static String q(String s) { return "'" + s.replace("'", "''") + "'"; }

    /** Run a script elevated (UAC prompt) and wait. EncodedCommand avoids every quoting problem. */
    private static int elevate(String script) throws IOException, InterruptedException {
        String b64 = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String outer = "try { $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
                + "-ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand " + b64 + "'; exit $p.ExitCode } catch { exit 2 }";
        String outerB64 = Base64.getEncoder().encodeToString(outer.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", outerB64).inheritIO().start().waitFor();
    }

    private static String powershell(String cmd, int timeoutSec) {
        String b64 = Base64.getEncoder().encodeToString(cmd.getBytes(StandardCharsets.UTF_16LE));
        return output(timeoutSec, "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", b64);
    }

    private static String output(int timeoutSec, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private static int run(int timeoutSec, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
