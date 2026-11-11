package dev.activedesk;

import dev.activedesk.util.Crypto;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Per-device settings in ~/.activedesk/config.properties (override the folder with ACTIVEDESK_HOME).
 * Every device is a full peer: there is no central server.
 */
public final class Config {
    private final Path home;
    private final Path file;
    private final Properties p = new Properties();
    /** Set only on the run that generated it, so it can be shown once. Never persisted in clear. */
    private String freshPin;

    private Config(Path home) {
        this.home = home;
        this.file = home.resolve("config.properties");
    }

    public static Config load() throws IOException {
        return loadFrom(defaultHome());
    }

    /** The data folder `load()` uses, without creating it. */
    public static Path defaultHome() {
        String env = System.getenv("ACTIVEDESK_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".activedesk");
    }

    /** Load (creating defaults) from an explicit folder. Used by the self-test to run two nodes in one JVM. */
    public static Config loadFrom(Path home) throws IOException {
        Files.createDirectories(home);
        Config c = new Config(home);
        if (Files.exists(c.file)) {
            try (Reader r = Files.newBufferedReader(c.file, StandardCharsets.UTF_8)) {
                c.p.load(r);
            }
        }
        c.applyDefaults();
        return c;
    }

    private void applyDefaults() throws IOException {
        boolean dirty = false;
        dirty |= def("node.id", UUID.randomUUID().toString());
        dirty |= def("device.name", hostName());
        dirty |= def("peer.port", "47800");
        dirty |= def("web.port", "47801");
        dirty |= def("allow.logins", "");
        dirty |= def("allow.tags", "");
        dirty |= def("peers.static", "");
        dirty |= def("transfer.dir", Path.of(System.getProperty("user.home"), "Downloads", "ActiveDesk").toString());
        dirty |= def("transfer.autoAccept", "true");
        dirty |= def("control.enabled", "true");
        dirty |= def("control.viewOnly", "false");
        dirty |= def("tailscale.path", "");
        dirty |= def("store.jdbc.url", "");
        dirty |= def("store.jdbc.user", "");
        dirty |= def("store.jdbc.password", "");
        dirty |= def("smtp.host", "");
        dirty |= def("smtp.port", "465");
        dirty |= def("smtp.user", "");
        dirty |= def("smtp.password", "");
        dirty |= def("smtp.from", "");
        dirty |= def("local.token", Crypto.token(24));
        dirty |= def("control.typeMode", "keys");
        dirty |= def("transfer.maxBytes", "8589934592");
        dirty |= def("bind.tailscale", "true");
        dirty |= def("power.keepAwake", "session");
        dirty |= def("battery.enabled", "false");
        dirty |= def("battery.min", "40");
        dirty |= def("battery.max", "80");
        dirty |= def("power.saver.enabled", "false");
        dirty |= def("power.saver.below", "25");
        dirty |= def("power.saver.profile", "");
        dirty |= def("power.saver.brightness", "0");
        if (p.getProperty("web.pinHash", "").isBlank()) {
            resetPin();
            dirty = true;
        }
        if (dirty) save();
    }

    private boolean def(String k, String v) {
        if (p.getProperty(k) != null) return false;
        p.setProperty(k, v);
        return true;
    }

    /** Generates a new 8-digit PIN, stores only its PBKDF2 hash, and returns the clear PIN once. */
    public String resetPin() throws IOException {
        freshPin = Crypto.pin(8);
        p.setProperty("web.pinHash", Crypto.hashSecret(freshPin));
        save();
        return freshPin;
    }

    public String freshPin() { return freshPin; }

    /**
     * The PIN hash as currently on disk, so `activedesk pin` (a separate process) takes effect on a
     * running node without a restart.
     */
    public synchronized String currentPinHash() {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties fresh = new Properties();
            fresh.load(r);
            String h = fresh.getProperty("web.pinHash", "");
            if (!h.isBlank()) p.setProperty("web.pinHash", h);
        } catch (IOException ignored) {
            // keep the in-memory value
        }
        return p.getProperty("web.pinHash", "");
    }

    /**
     * Change one setting from a running node: re-read the file first, so edits made meanwhile by the
     * user or by `activedesk pin` (a separate process) are never overwritten with stale values.
     */
    public synchronized void update(String key, String value) throws IOException {
        Properties fresh = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            fresh.load(r);
        }
        fresh.setProperty(key, value);
        p.setProperty(key, value);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            fresh.store(w, "ActiveDesk device config. Restart the node after editing.");
        }
        restrictPermissions(file);
    }

    public synchronized void save() throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "ActiveDesk device config. Restart the node after editing.");
        }
        restrictPermissions(file);
    }

    private static void restrictPermissions(Path f) {
        // best effort: owner-only on POSIX; on Windows the user profile ACL already applies
        try {
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null) n = System.getenv("HOSTNAME");
        if (n == null) {
            try { n = InetAddress.getLocalHost().getHostName(); } catch (IOException e) { n = "device"; }
        }
        return n;
    }

    public Path home() { return home; }
    public Path file() { return file; }
    public String get(String k) { return p.getProperty(k, ""); }
    public void set(String k, String v) { p.setProperty(k, v); }
    public int getInt(String k, int def) {
        try { return Integer.parseInt(get(k).trim()); } catch (NumberFormatException e) { return def; }
    }
    public boolean getBool(String k) { return Boolean.parseBoolean(get(k).trim()); }
    public List<String> getList(String k) {
        String v = get(k).trim();
        if (v.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(v.split(","))) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public String nodeId() { return get("node.id"); }
    public String deviceName() { return get("device.name"); }
    public int peerPort() { return getInt("peer.port", 47800); }
    public int webPort() { return getInt("web.port", 47801); }
    public Path transferDir() { return Path.of(get("transfer.dir")); }
}
