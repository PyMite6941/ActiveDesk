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
        config.update("battery.enabled", Boolean.toString(enabled));
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
        if (!enabled()) { message = "off - normal charging"; return; }
        Map<String, Object> st = guardStatus();
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
