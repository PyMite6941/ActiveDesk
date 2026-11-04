package dev.activedesk.power;

import dev.activedesk.Config;
import dev.activedesk.util.Log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Battery status, power mode, screen brightness and automatic power saver, on every OS.
 *
 *              battery status            power mode                               brightness
 *   Windows    Win32_Battery + BatteryStatus  power plans (powercfg /setactive)    WmiMonitorBrightnessMethods
 *   Linux      /sys/class/power_supply   power-profiles-daemon, else CPU governor  brightnessctl, else /sys/class/backlight
 *   macOS      pmset -g batt             Low Power Mode (pmset, via a sudoers rule) not available (no built-in tool)
 *
 * Nothing here needs admin rights on Windows. On Linux, governors and backlight need the one-time
 * `activedesk power install` udev rule unless brightnessctl / power-profiles-daemon are present. On macOS,
 * Low Power Mode needs the same one-time setup (a sudoers rule allowing exactly `pmset -a lowpowermode 0|1`).
 *
 * Auto power saver: when on battery and at or below `power.saver.below` %, switch to the saver mode
 * (and optionally dim). Put back exactly what was there before once plugged in, switched off, or when
 * ActiveDesk stops.
 */
public final class PowerService {
    public record Profile(String id, String name) { }

    private final Config config;

    // battery
    private volatile boolean hasBattery;
    private volatile int percent = -1;
    private volatile boolean onBattery;
    private volatile boolean charging;
    // power mode
    private volatile List<Profile> profiles = List.of();
    private volatile String current = "";
    private volatile String profileBackend = "";
    private volatile boolean profileNeedsSetup;
    // brightness
    private volatile int brightness = -1;
    private volatile String brightnessBackend = "";
    private volatile boolean brightnessNeedsSetup;
    // auto saver
    private String savedProfile;
    private int savedBrightness = -1;
    private volatile boolean autoActive;
    private volatile String message = "";
    private volatile boolean refreshed;

    public PowerService(Config config) { this.config = config; }

    public boolean hasBattery() { return hasBattery; }
    public int percent() { return percent; }
    public boolean onBattery() { return onBattery; }
    public boolean refreshed() { return refreshed; }
    public int saverBelow() { return config.getInt("power.saver.below", 25); }
    public String saverProfileSetting() { return config.get("power.saver.profile"); }
    public int saverDim() { return config.getInt("power.saver.brightness", 0); }

    // ------------------------------------------------------------------ periodic

    public synchronized void tick() {
        try {
            refresh();
            autoSaver();
        } catch (RuntimeException e) {
            message = "power error: " + e.getMessage();
            Log.debug(message);
        }
        refreshed = true;
    }

    public synchronized void close() {
        if (autoActive) restoreAuto("ActiveDesk stopping");
    }

    private void refresh() {
        if (Proc.windows()) refreshWindows();
        else if (Proc.mac()) refreshMac();
        else refreshLinux();
    }

    // ------------------------------------------------------------------ settings

    public synchronized void setProfile(String id) throws IOException {
        if (profiles.stream().noneMatch(p -> p.id().equals(id))) throw new IllegalArgumentException("unknown power mode " + id);
        applyProfile(id);
        refresh();
    }

    public synchronized void setBrightness(int pct) throws IOException {
        if (pct < 1 || pct > 100) throw new IllegalArgumentException("brightness must be 1-100");
        if (brightness < 0) throw new IllegalArgumentException("brightness control is not available on this device");
        applyBrightness(pct);
        refresh();
    }

    public synchronized void setSaver(boolean enabled, int below, String profile, int dimTo) throws IOException {
        if (below < 5 || below > 95) throw new IllegalArgumentException("'below' must be 5-95 %");
        if (dimTo < 0 || dimTo > 100) throw new IllegalArgumentException("dim level must be 0-100 (0 = leave brightness alone)");
        if (!profile.isEmpty() && profiles.stream().noneMatch(p -> p.id().equals(profile))) throw new IllegalArgumentException("unknown power mode " + profile);
        config.update("power.saver.enabled", Boolean.toString(enabled));
        config.update("power.saver.below", Integer.toString(below));
        config.update("power.saver.profile", profile);
        config.update("power.saver.brightness", Integer.toString(dimTo));
        if (autoActive) restoreAuto("settings changed");
        autoSaver();
    }

    private void autoSaver() throws RuntimeException {
        boolean enabled = config.getBool("power.saver.enabled");
        int below = config.getInt("power.saver.below", 25);
        if (autoActive) {
            if (!enabled || !onBattery) restoreAuto(!enabled ? "auto saver switched off" : "plugged in");
            return;
        }
        if (!enabled || !hasBattery || !onBattery || percent < 0 || percent > below) return;
        String target = saverProfile();
        savedProfile = current;
        savedBrightness = brightness;
        autoActive = true;
        try {
            if (target != null && !target.equals(current)) applyProfile(target);
            int dim = config.getInt("power.saver.brightness", 0);
            if (dim > 0 && brightness > dim) applyBrightness(dim);
            message = "power saver on (battery " + percent + "%)";
            Log.info("auto power saver ON at " + percent + "%");
        } catch (IOException e) {
            message = "power saver: " + e.getMessage();
        }
    }

    private void restoreAuto(String why) {
        autoActive = false;
        try {
            if (savedProfile != null && !savedProfile.isEmpty() && !savedProfile.equals(current)
                    && profiles.stream().anyMatch(p -> p.id().equals(savedProfile))) applyProfile(savedProfile);
            if (savedBrightness > 0 && brightness >= 0 && brightness != savedBrightness) applyBrightness(savedBrightness);
            message = "power saver off (" + why + "), previous settings restored";
            Log.info("auto power saver OFF: " + why);
        } catch (IOException | RuntimeException e) {
            message = "could not restore previous power settings: " + e.getMessage();
        }
        savedProfile = null;
        savedBrightness = -1;
    }

    /** The configured saver mode, or the most frugal one this device offers. */
    private String saverProfile() {
        String chosen = config.get("power.saver.profile");
        if (!chosen.isBlank() && profiles.stream().anyMatch(p -> p.id().equals(chosen))) return chosen;
        for (String hint : List.of("power-saver", "powersave", "low", "saver", "efficien", "eco", "conservative", "balanced", "381b4222")) {
            for (Profile p : profiles) {
                if (p.id().toLowerCase().contains(hint) || p.name().toLowerCase().contains(hint)) return p.id();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Windows

    private static final Pattern WIN_PLAN = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s+\\((.*?)\\)\\s*(\\*)?\\s*$");

    private void refreshWindows() {
        Proc.Result r = Proc.powershell(25, String.join("\n",
                "$b = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1",
                "$s = Get-CimInstance -Namespace root/wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue | Select-Object -First 1",
                "$br = Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness -ErrorAction SilentlyContinue | Select-Object -First 1",
                "'bat=' + [int]($null -ne $b)",
                "'pct=' + $b.EstimatedChargeRemaining",
                "'ac=' + $s.PowerOnline",
                "'chg=' + $s.Charging",
                "'br=' + $br.CurrentBrightness"));
        Map<String, String> kv = kv(r.out());
        hasBattery = "1".equals(kv.get("bat"));
        percent = hasBattery ? parse(kv.get("pct"), -1) : -1;
        onBattery = hasBattery && "False".equalsIgnoreCase(kv.get("ac"));
        charging = "True".equalsIgnoreCase(kv.get("chg"));
        brightness = parse(kv.get("br"), -1);
        brightnessBackend = brightness >= 0 ? "Windows display brightness" : "";

        List<Profile> list = new ArrayList<>();
        String cur = "";
        for (String line : Proc.run(15, "powercfg", "/list").out().split("\\R")) {
            Matcher m = WIN_PLAN.matcher(line.trim());
            if (m.find()) {
                list.add(new Profile(m.group(1).toLowerCase(), m.group(2)));
                if (m.group(3) != null) cur = m.group(1).toLowerCase();
            }
        }
        profiles = List.copyOf(list);
        current = cur;
        profileBackend = list.isEmpty() ? "" : "Windows power plans";
        profileNeedsSetup = list.stream().noneMatch(p -> p.name().toLowerCase().matches(".*(saver|efficien|eco).*"));
    }

    // ------------------------------------------------------------------ macOS

    private static final Pattern MAC_BATT = Pattern.compile("(\\d{1,3})%;\\s*([a-zA-Z ]+)");

    private void refreshMac() {
        String batt = Proc.run(10, "pmset", "-g", "batt").out();
        Matcher m = MAC_BATT.matcher(batt);
        hasBattery = batt.contains("InternalBattery") && m.find();
        percent = hasBattery ? Integer.parseInt(m.group(1)) : -1;
        onBattery = hasBattery && batt.contains("'Battery Power'");
        charging = hasBattery && m.group(2).trim().startsWith("charging");

        String g = Proc.run(10, "pmset", "-g").out();
        Matcher lp = Pattern.compile("(?m)^\\s*lowpowermode\\s+(\\d)").matcher(g);
        if (lp.find()) {
            profiles = List.of(new Profile("normal", "Normal"), new Profile("low", "Low Power Mode"));
            current = "1".equals(lp.group(1)) ? "low" : "normal";
            profileBackend = "macOS Low Power Mode";
            profileNeedsSetup = !Proc.run(10, "sudo", "-n", "-l", "/usr/bin/pmset", "-a", "lowpowermode", "1").ok();
        } else {
            profiles = List.of();
            profileBackend = "";
        }
        brightness = -1;
        brightnessBackend = "";
    }

    // ------------------------------------------------------------------ Linux (incl. Raspberry Pi)

    private void refreshLinux() {
        // battery
        Path bat = null;
        boolean anyMains = false, mainsOnline = false;
        Path root = Path.of("/sys/class/power_supply");
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path d : ds) {
                    String type = Proc.read(d.resolve("type"));
                    if (type.equals("Battery") && !"Device".equals(Proc.read(d.resolve("scope"))) && bat == null) bat = d;
                    if (type.equals("Mains") || type.equals("USB") || type.equals("USB_PD")) {
                        anyMains = anyMains || type.equals("Mains");
                        mainsOnline = mainsOnline || "1".equals(Proc.read(d.resolve("online")));
                    }
                }
            } catch (IOException ignored) { }
        }
        hasBattery = bat != null;
        percent = bat == null ? -1 : Proc.readInt(bat.resolve("capacity"), -1);
        String status = bat == null ? "" : Proc.read(bat.resolve("status"));
        charging = status.equals("Charging");
        onBattery = hasBattery && (anyMains ? !mainsOnline : status.equals("Discharging"));

        // power mode
        if (Proc.onPath("powerprofilesctl")) {
            List<Profile> list = new ArrayList<>();
            for (String line : Proc.run(10, "powerprofilesctl", "list").out().split("\\R")) {
                Matcher m = Pattern.compile("^\\s*(\\*)?\\s*([a-z-]+):\\s*$").matcher(line);
                if (m.find()) {
                    list.add(new Profile(m.group(2), pretty(m.group(2))));
                    if (m.group(1) != null) current = m.group(2);
                }
            }
            profiles = List.copyOf(list);
            profileBackend = "power-profiles-daemon";
            profileNeedsSetup = false;
        } else {
            Path pol = governorFiles().stream().findFirst().orElse(null);
            if (pol != null) {
                String avail = Proc.read(pol.resolveSibling("scaling_available_governors"));
                List<Profile> list = new ArrayList<>();
                for (String gname : avail.split("\\s+")) if (gname.matches("[a-z_]{2,20}")) list.add(new Profile(gname, pretty(gname)));
                profiles = List.copyOf(list);
                current = Proc.read(pol);
                profileBackend = "CPU governor";
                profileNeedsSetup = !Files.isWritable(pol);
            } else {
                profiles = List.of();
                profileBackend = "";
            }
        }

        // brightness
        if (Proc.onPath("brightnessctl")) {
            // machine format: device,class,current,percent,max
            String[] f = Proc.run(10, "brightnessctl", "-m", "-c", "backlight").out().trim().split(",");
            brightness = f.length >= 4 ? parse(f[3].replace("%", ""), -1) : -1;
            brightnessBackend = brightness >= 0 ? "brightnessctl" : "";
            brightnessNeedsSetup = false;
        } else {
            Path bl = backlight();
            if (bl != null) {
                int max = Proc.readInt(bl.resolve("max_brightness"), 0);
                int cur = Proc.readInt(bl.resolve("brightness"), -1);
                brightness = max > 0 && cur >= 0 ? Math.round(cur * 100f / max) : -1;
                brightnessBackend = "backlight (" + bl.getFileName() + ")";
                brightnessNeedsSetup = !Files.isWritable(bl.resolve("brightness"));
            } else {
                brightness = -1;
                brightnessBackend = "";
            }
        }
    }

    static List<Path> governorFiles() {
        List<Path> out = new ArrayList<>();
        Path base = Path.of("/sys/devices/system/cpu/cpufreq");
        if (!Files.isDirectory(base)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, "policy*")) {
            for (Path p : ds) if (Files.exists(p.resolve("scaling_governor"))) out.add(p.resolve("scaling_governor"));
        } catch (IOException ignored) { }
        return out;
    }

    private static Path backlight() {
        Path base = Path.of("/sys/class/backlight");
        if (!Files.isDirectory(base)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path p : ds) if (Files.exists(p.resolve("brightness"))) return p;
        } catch (IOException ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ apply

    private void applyProfile(String id) throws IOException {
        if (Proc.windows()) {
            if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("bad plan id");
            Proc.Result r = Proc.run(15, "powercfg", "/setactive", id);
            if (!r.ok()) throw new IOException("powercfg failed: " + r.out().trim());
        } else if (Proc.mac()) {
            String v = id.equals("low") ? "1" : "0";
            Proc.Result r = Proc.run(15, "sudo", "-n", "/usr/bin/pmset", "-a", "lowpowermode", v);
            if (!r.ok()) throw new IOException("needs one-time setup (Power tab > Set up, or 'activedesk power install')");
        } else if (profileBackend.equals("power-profiles-daemon")) {
            if (!id.matches("[a-z-]{2,30}")) throw new IllegalArgumentException("bad profile");
            Proc.Result r = Proc.run(15, "powerprofilesctl", "set", id);
            if (!r.ok()) throw new IOException("powerprofilesctl failed: " + r.out().trim());
        } else {
            if (!id.matches("[a-z_]{2,20}")) throw new IllegalArgumentException("bad governor");
            List<Path> files = governorFiles();
            if (files.isEmpty() || !Files.isWritable(files.get(0))) throw new IOException("needs one-time setup (Power tab > Set up, or 'activedesk power install')");
            for (Path f : files) Files.writeString(f, id);
        }
        current = id;
    }

    private void applyBrightness(int pct) throws IOException {
        pct = Math.max(1, Math.min(100, pct));
        if (Proc.windows()) {
            Proc.Result r = Proc.powershell(20, "$m = Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods | Select-Object -First 1\n"
                    + "Invoke-CimMethod -InputObject $m -MethodName WmiSetBrightness -Arguments @{ Timeout = [uint32]0; Brightness = [byte]" + pct + " } | Out-Null");
            if (!r.ok()) throw new IOException("could not set brightness: " + r.out().trim());
        } else if (brightnessBackend.equals("brightnessctl")) {
            Proc.Result r = Proc.run(10, "brightnessctl", "-q", "-c", "backlight", "set", pct + "%");
            if (!r.ok()) throw new IOException("brightnessctl failed: " + r.out().trim());
        } else {
            Path bl = backlight();
            if (bl == null || !Files.isWritable(bl.resolve("brightness"))) throw new IOException("needs one-time setup (Power tab > Set up, or 'activedesk power install')");
            int max = Proc.readInt(bl.resolve("max_brightness"), 0);
            Files.writeString(bl.resolve("brightness"), Integer.toString(Math.max(1, Math.round(max * pct / 100f))));
        }
        brightness = pct;
    }

    // ------------------------------------------------------------------ state

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ready", refreshed);
        m.put("hasBattery", hasBattery);
        m.put("percent", percent);
        m.put("onBattery", onBattery);
        m.put("charging", charging);
        List<Object> ps = new ArrayList<>();
        for (Profile p : profiles) ps.add(Map.of("id", p.id(), "name", p.name()));
        m.put("profiles", ps);
        m.put("profile", current);
        m.put("profileBackend", profileBackend);
        m.put("brightness", brightness);
        m.put("brightnessBackend", brightnessBackend);
        m.put("needsSetup", (profileNeedsSetup && !profiles.isEmpty()) || (brightnessNeedsSetup && brightness >= 0));
        m.put("saverEnabled", config.getBool("power.saver.enabled"));
        m.put("saverBelow", config.getInt("power.saver.below", 25));
        m.put("saverProfile", config.get("power.saver.profile"));
        m.put("saverBrightness", config.getInt("power.saver.brightness", 0));
        m.put("saverActive", autoActive);
        m.put("message", message);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> kv(String out) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        return m;
    }

    private static int parse(String s, int def) {
        try { return s == null || s.isBlank() ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String pretty(String id) {
        String s = id.replace('-', ' ').replace('_', ' ');
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
