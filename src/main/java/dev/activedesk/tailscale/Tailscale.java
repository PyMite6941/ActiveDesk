package dev.activedesk.tailscale;

import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the local `tailscale` CLI. ActiveDesk never talks to a Tailscale server itself:
 * the tailnet (WireGuard) is the transport and encryption layer, and `tailscale status --json`
 * tells us which device and which owner sits behind each 100.x address.
 */
public final class Tailscale {

    public record Peer(String hostName, String dnsName, List<String> ips, boolean online,
                       String login, String os, List<String> tags, boolean self) {
        public String ipv4() {
            for (String ip : ips) if (ip.indexOf(':') < 0) return ip;
            return ips.isEmpty() ? null : ips.get(0);
        }

        public String displayName() {
            if (dnsName != null && !dnsName.isBlank()) {
                String d = dnsName.endsWith(".") ? dnsName.substring(0, dnsName.length() - 1) : dnsName;
                int dot = d.indexOf('.');
                return dot > 0 ? d.substring(0, dot) : d;
            }
            return hostName;
        }
    }

    public record Status(String backendState, Peer self, List<Peer> peers) {
        public boolean running() { return "Running".equals(backendState) && self != null; }

        public Optional<Peer> byIp(String ip) {
            String norm = normalize(ip);
            if (self != null && self.ips().stream().anyMatch(a -> normalize(a).equals(norm))) return Optional.of(self);
            return peers.stream().filter(p -> p.ips().stream().anyMatch(a -> normalize(a).equals(norm))).findFirst();
        }
    }

    private final String binary;
    private volatile Status cached;
    private volatile long cachedAt;

    public Tailscale(String configuredPath) {
        this.binary = locate(configuredPath);
    }

    public String binary() { return binary; }

    private static String locate(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            candidates.add(pf + "\\Tailscale\\tailscale.exe");
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Tailscale.app/Contents/MacOS/Tailscale");
            candidates.add("/usr/local/bin/tailscale");
            candidates.add("/opt/homebrew/bin/tailscale");
        } else {
            candidates.add("/usr/bin/tailscale");
            candidates.add("/usr/local/bin/tailscale");
        }
        for (String c : candidates) if (Files.isExecutable(Path.of(c))) return c;
        return "tailscale"; // fall back to PATH
    }

    /** Cached status (10 s). Pass force=true when an unknown IP shows up. */
    public Status status(boolean force) {
        long now = System.currentTimeMillis();
        Status s = cached;
        if (!force && s != null && now - cachedAt < 10_000) return s;
        synchronized (this) {
            if (!force && cached != null && System.currentTimeMillis() - cachedAt < 10_000) return cached;
            try {
                cached = parse(run("status", "--json"));
            } catch (Exception e) {
                Log.debug("tailscale status failed: " + e);
                cached = new Status("Unavailable", null, List.of());
            }
            cachedAt = System.currentTimeMillis();
            return cached;
        }
    }

    public Status status() { return status(false); }

    static Status parse(String json) {
        Map<String, Object> root = Json.obj(json);
        Map<String, Object> users = Json.map(root, "User");
        Peer self = root.get("Self") instanceof Map ? peer(Json.map(root, "Self"), users, true) : null;
        List<Peer> peers = new ArrayList<>();
        for (Object v : Json.map(root, "Peer").values()) {
            if (v instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
                peers.add(peer(m, users, false));
            }
        }
        return new Status(Json.str(root, "BackendState", "Unknown"), self, peers);
    }

    private static Peer peer(Map<String, Object> m, Map<String, Object> users, boolean self) {
        List<String> ips = new ArrayList<>();
        for (Object o : Json.list(m, "TailscaleIPs")) ips.add(String.valueOf(o));
        List<String> tags = new ArrayList<>();
        for (Object o : Json.list(m, "Tags")) tags.add(String.valueOf(o));
        Object uid = m.get("UserID");
        String uidKey = uid instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(uid);
        String login = Json.str(Json.map(users, uidKey), "LoginName", "");
        // Self reports Online=false on some platforms (it is the local node); treat self as online.
        boolean online = self || Json.bool(m, "Online", false);
        return new Peer(Json.str(m, "HostName", "?"), Json.str(m, "DNSName", ""), ips, online,
                login, Json.str(m, "OS", ""), tags, self);
    }

    private String run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.addAll(List.of(args));
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        byte[] out;
        try (InputStream in = pr.getInputStream()) {
            out = in.readAllBytes();
        }
        if (!pr.waitFor(5, TimeUnit.SECONDS)) {
            pr.destroyForcibly();
            throw new IOException("tailscale CLI timed out");
        }
        if (pr.exitValue() != 0) throw new IOException("tailscale exited " + pr.exitValue());
        return new String(out, StandardCharsets.UTF_8);
    }

    /** True for addresses inside Tailscale's ranges: 100.64.0.0/10 and fd7a:115c:a1e0::/48. */
    public static boolean isTailnetAddress(String ip) {
        String n = normalize(ip);
        if (n.startsWith("fd7a:115c:a1e0:")) return true;
        String[] parts = n.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean isLoopback(String ip) {
        String n = normalize(ip);
        return n.startsWith("127.") || n.equals("::1") || n.equals("0:0:0:0:0:0:0:1");
    }

    /** Strip brackets / zone ids and lower-case so "::ffff:100.1.2.3" style addresses compare cleanly. */
    public static String normalize(String ip) {
        if (ip == null) return "";
        String s = ip.trim().toLowerCase();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("[")) s = s.substring(1, s.indexOf(']') > 0 ? s.indexOf(']') : s.length());
        int pct = s.indexOf('%');
        if (pct > 0) s = s.substring(0, pct);
        if (s.startsWith("::ffff:") && s.indexOf('.') > 0) s = s.substring(7);
        return s;
    }
}
