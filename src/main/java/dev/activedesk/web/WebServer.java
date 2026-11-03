package dev.activedesk.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.activedesk.Node;
import dev.activedesk.chat.ChatMessage;
import dev.activedesk.chat.ChatService;
import dev.activedesk.net.Connection;
import dev.activedesk.sched.TaskDefinition;
import dev.activedesk.sched.TaskRun;
import dev.activedesk.sched.ThreadPools;
import dev.activedesk.tailscale.AccessPolicy;
import dev.activedesk.tailscale.Tailscale;
import dev.activedesk.transfer.FileTransferService;
import dev.activedesk.util.Crypto;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Phone / browser UI and JSON API (JDK built-in HttpServer, no framework).
 *
 * Security layers, in order, for every request:
 *  1. bound only to loopback + this device's Tailscale IP (never 0.0.0.0, never the LAN)
 *  2. AccessPolicy: caller must be this device or a Tailscale device owned by an allowed login
 *  3. Host header must be an IP, localhost, this device's name or *.ts.net (blocks DNS rebinding);
 *     a present Origin header must pass the same check (blocks cross-site requests)
 *  4. everything except static files and /api/login needs a bearer session token obtained with the
 *     device PIN. Tokens live in memory only, are bound to the caller's IP, expire after 30 min idle /
 *     12 h absolute, and are all revoked by `activedesk lock`. No cookies are used, so CSRF is moot.
 *  5. PIN guessing: 5 failures per IP per 15 min, 20 per device per 15 min, PBKDF2 (120k rounds).
 *  6. Strict CSP, no framing, no-store caching, nosniff; audit log of logins, control and transfers.
 */
public final class WebServer {
    private static final long IDLE_MS = 30 * 60_000L;
    private static final long ABSOLUTE_MS = 12 * 3600_000L;
    private static final long FAIL_WINDOW_MS = 15 * 60_000L;
    private static final int FAILS_PER_IP = 5;
    private static final int FAILS_GLOBAL = 20;
    private static final int MAX_JSON = 64 * 1024;
    private static final Set<String> STATIC = Set.of("index.html", "app.js", "style.css", "manifest.webmanifest", "icon.svg");

    private record Session(String token, String ip, String who, long created, long[] lastUsed, boolean[] controlAnnounced) {}

    private record Cached(AccessPolicy.Decision d, long at) {}

    private final Node node;
    private final List<HttpServer> servers = new ArrayList<>();
    private final Set<String> bound = ConcurrentHashMap.newKeySet();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failsByIp = new ConcurrentHashMap<>();
    private final Deque<Long> failsGlobal = new ArrayDeque<>();
    private final Map<String, Cached> policyCache = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(8, ThreadPools.named("ad-web"));

    public WebServer(Node node) {
        this.node = node;
        ThreadPools.get().ticker().scheduleAtFixedRate(this::expire, 30, 30, TimeUnit.SECONDS);
    }

    public void bind(InetAddress addr, int port) throws IOException {
        String key = addr.getHostAddress() + ":" + port;
        if (!bound.add(key)) return;
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(addr, port), 32);
            s.createContext("/", this::handle);
            s.setExecutor(pool);
            s.start();
            servers.add(s);
            Log.info("web UI listening on http://" + (addr.getHostAddress().contains(":") ? "[" + addr.getHostAddress() + "]" : addr.getHostAddress()) + ":" + port + "/");
        } catch (IOException e) {
            bound.remove(key);
            throw e;
        }
    }

    public boolean isBound(InetAddress addr, int port) { return bound.contains(addr.getHostAddress() + ":" + port); }

    public void stop() {
        for (HttpServer s : servers) s.stop(0);
        pool.shutdownNow();
    }

    /** Revoke every session (used by `activedesk lock`). */
    public int revokeAll() {
        int n = sessions.size();
        sessions.clear();
        node.indicator().hideNow();
        node.audit("all web sessions revoked (" + n + ")");
        return n;
    }

    public int sessionCount() { return sessions.size(); }

    private void expire() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now - s.lastUsed()[0] > IDLE_MS || now - s.created() > ABSOLUTE_MS);
        policyCache.values().removeIf(c -> now - c.at() > 30_000);
        node.indicator().tick();
    }

    // ------------------------------------------------------------------ request pipeline

    private void handle(HttpExchange ex) throws IOException {
        // plain try + finally (not try-with-resources): the catch blocks below must still be able to respond
        try {
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            AccessPolicy.Decision d = policy(ip);
            if (!d.allowed()) {
                Log.warn("web: refused " + ip + ": " + d.reason());
                plain(ex, 403, "Forbidden: this device only accepts your own Tailscale devices.");
                return;
            }
            Headers h = ex.getResponseHeaders();
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; "
                    + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            h.set("X-Frame-Options", "DENY");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");
            h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

            if (!hostAllowed(ex.getRequestHeaders().getFirst("Host"))) {
                plain(ex, 421, "Unexpected Host header.");
                return;
            }
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !originAllowed(origin, ex.getRequestHeaders().getFirst("Host"))) {
                plain(ex, 403, "Cross-origin request refused.");
                return;
            }

            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!path.startsWith("/api/")) {
                if (!"GET".equals(method) && !"HEAD".equals(method)) { plain(ex, 405, "Method not allowed"); return; }
                serveStatic(ex, path);
                return;
            }
            if ("/api/login".equals(path) && "POST".equals(method)) {
                login(ex, ip, d.who());
                return;
            }
            Session s = session(ex, ip);
            if (s == null) {
                json(ex, 401, Json.o("error", "login required"));
                return;
            }
            route(ex, s, path, method);
        } catch (HttpError e) {
            try {
                json(ex, e.code, Json.o("error", e.getMessage()));
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            Log.error("web handler error", e);
            try {
                json(ex, 500, Json.o("error", "internal error"));
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private AccessPolicy.Decision policy(String ip) {
        Cached c = policyCache.get(ip);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < 30_000) return c.d();
        AccessPolicy.Decision d = node.policy().check(ip);
        policyCache.put(ip, new Cached(d, now));
        return d;
    }

    private boolean hostAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return false;
        String host = hostHeader.trim().toLowerCase();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 && host.substring(1, end).matches("[0-9a-f:.]+");
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;
        if (host.equals("localhost")) return true;
        if (host.endsWith(".ts.net") && host.matches("[a-z0-9.-]+")) return true;
        Tailscale.Peer self = node.tailscale().status().self();
        if (self != null && host.equalsIgnoreCase(self.displayName())) return true; // MagicDNS short name
        return host.equalsIgnoreCase(node.config().deviceName());
    }

    private boolean originAllowed(String origin, String hostHeader) {
        if (origin == null || hostHeader == null) return false;
        String o = origin.toLowerCase();
        String hostPart = o.replaceFirst("^https?://", "");
        return hostPart.equals(hostHeader.toLowerCase()) && hostAllowed(hostPart);
    }

    // ------------------------------------------------------------------ auth

    private void login(HttpExchange ex, String ip, String who) throws IOException {
        long now = System.currentTimeMillis();
        Deque<Long> ipFails = failsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (failsGlobal) {
            prune(failsGlobal, now);
            prune(ipFails, now);
            if (ipFails.size() >= FAILS_PER_IP || failsGlobal.size() >= FAILS_GLOBAL) {
                node.audit("login BLOCKED (rate limit) from " + who + " " + ip);
                json(ex, 429, Json.o("error", "Too many wrong PINs. Wait 15 minutes."));
                return;
            }
        }
        Map<String, Object> body = readJson(ex);
        String pin = Json.str(body, "pin", "");
        boolean ok = pin.length() <= 64 && Crypto.verifySecret(pin, node.config().currentPinHash());
        if (!ok) {
            synchronized (failsGlobal) {
                ipFails.addLast(now);
                failsGlobal.addLast(now);
            }
            node.audit("login FAILED from " + who + " " + ip);
            json(ex, 403, Json.o("error", "Wrong PIN"));
            return;
        }
        ipFails.clear();
        String token = Crypto.token(32);
        sessions.put(token, new Session(token, ip, who, now, new long[]{now}, new boolean[]{false}));
        node.audit("login OK from " + who + " " + ip);
        json(ex, 200, Json.o("token", token, "device", node.config().deviceName()));
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > FAIL_WINDOW_MS) q.removeFirst();
    }

    private Session session(HttpExchange ex, String ip) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7).trim();
        if (!token.matches("[a-f0-9]{64}")) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (!s.ip().equals(ip) || now - s.lastUsed()[0] > IDLE_MS || now - s.created() > ABSOLUTE_MS) {
            if (!s.ip().equals(ip)) node.audit("session token presented from a different IP " + ip + " - revoked");
            sessions.remove(token);
            return null;
        }
        s.lastUsed()[0] = now;
        return s;
    }

    // ------------------------------------------------------------------ routes

    private void route(HttpExchange ex, Session s, String path, String method) throws Exception {
        Map<String, String> q = query(ex);
        switch (method + " " + path) {
            case "POST /api/logout" -> {
                sessions.remove(s.token());
                json(ex, 200, Json.o("ok", true));
            }
            case "GET /api/state" -> json(ex, 200, state());
            case "GET /api/chat" -> {
                String room = ChatService.validRoom(q.getOrDefault("room", "general"));
                if (room == null) throw new HttpError(400, "bad room");
                long since = parseLong(q.get("since"), 0);
                List<Object> msgs = new ArrayList<>();
                for (ChatMessage m : node.chat().since(room, since, 200)) msgs.add(m.toJson());
                json(ex, 200, Json.o("room", room, "messages", msgs, "rooms", node.chat().rooms(), "lastSeq", node.chat().lastSeq()));
            }
            case "POST /api/chat" -> {
                Map<String, Object> b = readJson(ex);
                try {
                    // author is always this device: peers only accept a device's own name (anti-spoofing)
                    ChatMessage m = node.chat().post(Json.str(b, "room", "general"), Json.str(b, "text", ""));
                    json(ex, 200, m.toJson());
                } catch (IllegalArgumentException e) {
                    throw new HttpError(400, e.getMessage());
                }
            }
            case "GET /api/screen" -> screen(ex, s, q);
            case "POST /api/input" -> input(ex, s);
            case "GET /api/tasks" -> json(ex, 200, tasks());
            case "POST /api/tasks/run" -> {
                String id = Json.str(readJson(ex), "id", "");
                if (!node.scheduler().trigger(id, "web:" + shortWho(s.who()))) throw new HttpError(404, "unknown task");
                node.audit("task " + id + " triggered by " + s.who());
                json(ex, 200, Json.o("ok", true));
            }
            case "POST /api/tasks/enable" -> {
                Map<String, Object> b = readJson(ex);
                String id = Json.str(b, "id", "");
                boolean en = Json.bool(b, "enabled", true);
                if (!node.scheduler().setEnabled(id, en)) throw new HttpError(404, "unknown task");
                node.audit("task " + id + (en ? " enabled" : " disabled") + " by " + s.who());
                json(ex, 200, Json.o("ok", true));
            }
            case "GET /api/files" -> {
                List<Object> transfers = new ArrayList<>();
                for (FileTransferService.Transfer t : node.transfers().recent()) transfers.add(t.toJson());
                json(ex, 200, Json.o("files", node.transfers().receivedFiles(), "transfers", transfers));
            }
            case "GET /api/files/get" -> download(ex, s, q.get("name"));
            case "POST /api/files/upload" -> upload(ex, s, q);
            case "POST /api/files/forward" -> forward(ex, s);
            case "POST /api/wake" -> {
                String id = Json.str(readJson(ex), "nodeId", "");
                int n = node.wake(id, s.who());
                if (n < 0) throw new HttpError(404, "unknown device");
                if (n == 0) throw new HttpError(409, "no LAN address known for that device yet (it must have been online with ActiveDesk once)");
                json(ex, 200, Json.o("ok", true, "packets", n));
            }
            case "GET /api/rdp" -> rdp(ex, s, q.get("nodeId"));
            case "POST /api/battery" -> {
                Map<String, Object> b = readJson(ex);
                boolean en = Json.bool(b, "enabled", false);
                int min = (int) Json.num(b, "min", node.battery().min()), max = (int) Json.num(b, "max", node.battery().max());
                String bad = dev.activedesk.power.BatteryService.invalid(min, max);
                if (bad != null) throw new HttpError(400, bad);
                node.setBattery(en, min, max, s.who());
                json(ex, 200, Json.o("ok", true, "battery", node.battery().state()));
            }
            case "POST /api/keepawake" -> {
                String m = Json.str(readJson(ex), "mode", "");
                if (!m.matches("off|session|always")) throw new HttpError(400, "mode must be off, session or always");
                node.setKeepAwake(dev.activedesk.remote.KeepAwake.parse(m), s.who());
                json(ex, 200, Json.o("ok", true, "keepAwake", m));
            }
            default -> throw new HttpError(404, "not found");
        }
    }

    private static String shortWho(String who) {
        int lt = who.indexOf(" <");
        return lt > 0 ? who.substring(0, lt) : who;
    }

    private Map<String, Object> state() {
        List<Object> peers = new ArrayList<>();
        Set<String> activeIps = new java.util.HashSet<>();
        for (Connection c : node.hub().peers()) {
            activeIps.add(c.remoteIp());
            String webUrl = c.peerWebPort() > 0 && !c.isLoopback()
                    ? "http://" + (c.remoteIp().contains(":") ? "[" + c.remoteIp() + "]" : c.remoteIp()) + ":" + c.peerWebPort() + "/"
                    : "";
            peers.add(Json.o("nodeId", c.peerNodeId(), "name", c.peerName(), "ip", c.remoteIp(), "os", c.peerOs(),
                    "screen", c.peerScreen(), "webUrl", webUrl));
        }
        List<Object> tailnet = new ArrayList<>();
        Tailscale.Status st = node.tailscale().status();
        for (Tailscale.Peer p : st.peers()) {
            tailnet.add(Json.o("name", p.displayName(), "os", p.os(), "online", p.online(), "ip", p.ipv4(),
                    "activedesk", p.ipv4() != null && activeIps.contains(p.ipv4())));
        }
        Set<String> online = new java.util.HashSet<>();
        for (Connection c : node.hub().peers()) online.add(c.peerNodeId());
        List<Object> offline = new ArrayList<>();
        for (Map<String, Object> d : node.knownDevices().values()) {
            String id = Json.str(d, "nodeId", "");
            if (online.contains(id)) continue;
            offline.add(Json.o("nodeId", id, "name", Json.str(d, "name", "?"), "os", Json.str(d, "os", ""),
                    "lastSeen", Json.num(d, "lastSeen", 0), "wakeable", !Json.list(d, "lan").isEmpty(),
                    "rdp", Json.bool(d, "rdp", false), "ip", Json.str(d, "ip", "")));
        }
        boolean selfRdp = System.getProperty("os.name", "").toLowerCase().contains("win") && rdpListening();
        return Json.o("device", node.config().deviceName(), "nodeId", node.config().nodeId(), "offline", offline,
                "rdp", selfRdp, "selfIp", st.self() == null ? "" : String.valueOf(st.self().ipv4()),
                "os", System.getProperty("os.name"), "version", dev.activedesk.net.Hub.VERSION,
                "tailscale", st.backendState(),
                "screen", node.screen().available(), "screenReason", node.screen().unavailableReason() == null ? "" : node.screen().unavailableReason(),
                "viewOnly", node.config().getBool("control.viewOnly"),
                "keepAwake", node.keepAwake().mode().name().toLowerCase(java.util.Locale.ROOT),
                "keepAwakeHolding", node.keepAwake().holding(),
                "battery", node.battery().state(),
                "peers", peers, "tailnet", tailnet, "rooms", node.chat().rooms());
    }

    private void screen(HttpExchange ex, Session s, Map<String, String> q) throws IOException {
        if (!node.screen().available()) throw new HttpError(503, node.screen().unavailableReason());
        int w = (int) Math.max(320, Math.min(2560, parseLong(q.get("w"), 1280)));
        float quality = (float) Math.max(0.1, Math.min(0.95, parseDouble(q.get("q"), 0.6)));
        announceControl(s, false);
        node.indicator().touch(shortWho(s.who()), false);
        node.keepAwake().activity();
        byte[] jpg = node.screen().captureJpeg(w, quality);
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.sendResponseHeaders(200, jpg.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(jpg);
        }
    }

    private void input(HttpExchange ex, Session s) throws IOException {
        if (!node.screen().available()) throw new HttpError(503, node.screen().unavailableReason());
        if (node.config().getBool("control.viewOnly")) throw new HttpError(403, "this device is view-only");
        Map<String, Object> b = readJson(ex);
        announceControl(s, true);
        node.indicator().touch(shortWho(s.who()), true);
        node.keepAwake().activity();
        node.screen().input(b);
        json(ex, 200, Json.o("ok", true));
    }

    private void announceControl(Session s, boolean control) {
        if (control && !s.controlAnnounced()[0]) {
            s.controlAnnounced()[0] = true;
            node.audit("REMOTE CONTROL started by " + s.who() + " " + s.ip());
            node.chat().system("general", "Remote control of this device started by " + shortWho(s.who()));
        }
    }

    private static boolean rdpListening() {
        try (java.net.Socket so = new java.net.Socket()) {
            so.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), 3389), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A .rdp file for Windows Remote Desktop over Tailscale. That is the supported way to type at the
     * Windows sign-in / lock screen, which no user-level app may see or control.
     */
    private void rdp(HttpExchange ex, Session s, String nodeId) throws IOException {
        String ip;
        String name;
        if (nodeId == null || nodeId.isBlank() || nodeId.equals(node.config().nodeId())) {
            Tailscale.Peer self = node.tailscale().status().self();
            if (self == null || self.ipv4() == null) throw new HttpError(409, "Tailscale is not running on this device");
            ip = self.ipv4();
            name = node.config().deviceName();
        } else {
            Map<String, Object> d = node.knownDevices().get(nodeId);
            if (d == null) throw new HttpError(404, "unknown device");
            ip = Json.str(d, "ip", "");
            name = Json.str(d, "name", "pc");
        }
        if (!Tailscale.isTailnetAddress(ip)) throw new HttpError(409, "device has no Tailscale address");
        String body = "full address:s:" + ip + ":3389\r\nprompt for credentials:i:1\r\n"
                + "authentication level:i:2\r\nscreen mode id:i:2\r\nsmart sizing:i:1\r\n";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/x-rdp");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9_-]", "_") + ".rdp\"");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private Map<String, Object> tasks() {
        List<Object> list = new ArrayList<>();
        for (TaskDefinition t : node.scheduler().tasks().values()) {
            Map<String, Object> m = new LinkedHashMap<>(t.toJson());
            m.remove("params"); // params may contain paths/commands; the file is the place to read them
            ZonedDateTime next = node.scheduler().nextFire(t.id());
            m.put("next", next == null ? "" : next.toString());
            m.put("running", node.scheduler().isRunning(t.id()));
            List<TaskRun> last = node.scheduler().runLog().recentFor(t.id(), 1);
            m.put("last", last.isEmpty() ? null : last.get(0).toJson());
            list.add(m);
        }
        List<Object> runs = new ArrayList<>();
        for (TaskRun r : node.scheduler().runLog().recent(30)) runs.add(r.toJson());
        return Json.o("tasks", list, "invalid", node.scheduler().invalidTasks(), "runs", runs,
                "file", node.scheduler().tasksFile().toString());
    }

    private void download(HttpExchange ex, Session s, String name) throws IOException {
        Path p = node.transfers().resolveReceived(name);
        if (p == null) throw new HttpError(404, "no such file");
        node.audit("download " + p.getFileName() + " by " + s.who());
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        String ascii = p.getFileName().toString().replaceAll("[^A-Za-z0-9._ ()-]", "_");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(p.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20"));
        long size = Files.size(p);
        ex.sendResponseHeaders(200, size);
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(p, os);
        }
    }

    /** Upload from the browser into this device's ActiveDesk folder; optionally forward to a peer. */
    private void upload(HttpExchange ex, Session s, Map<String, String> q) throws IOException {
        long limit = maxBytes();
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl != null && parseLong(cl, 0) > limit) throw new HttpError(413, "file too large");
        Path saved;
        try (InputStream in = ex.getRequestBody()) {
            saved = node.transfers().storeLocal(q.getOrDefault("name", "upload"), in, limit);
        }
        node.audit("upload " + saved.getFileName() + " by " + s.who());
        node.chat().system("files", "Uploaded " + saved.getFileName() + " from " + shortWho(s.who()));
        String to = q.getOrDefault("to", "self");
        if (!"self".equals(to)) forwardAsync(saved, to, s);
        json(ex, 200, Json.o("ok", true, "savedAs", saved.getFileName().toString()));
    }

    /** Send a file that is already in this device's ActiveDesk folder to another device. */
    private void forward(HttpExchange ex, Session s) throws IOException {
        Map<String, Object> b = readJson(ex);
        Path p = node.transfers().resolveReceived(Json.str(b, "name", null));
        if (p == null) throw new HttpError(404, "no such file");
        forwardAsync(p, Json.str(b, "to", ""), s);
        json(ex, 200, Json.o("ok", true));
    }

    private void forwardAsync(Path file, String nodeId, Session s) {
        if (node.hub().peer(nodeId) == null) throw new HttpError(404, "device not connected");
        node.audit("send " + file.getFileName() + " to " + node.hub().peer(nodeId).peerName() + " by " + s.who());
        node.transfers().send(nodeId, file).whenComplete((t, e) -> {
            if (e != null) node.chat().system("files", "Sending " + file.getFileName() + " failed: " + e.getMessage());
            else node.chat().system("files", "Sent " + file.getFileName() + " to " + t.peer + " (verified)");
        });
    }

    private long maxBytes() { return parseLong(node.config().get("transfer.maxBytes"), 8L * 1024 * 1024 * 1024); }

    // ------------------------------------------------------------------ helpers

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!STATIC.contains(name)) {
            plain(ex, 404, "Not found");
            return;
        }
        byte[] body;
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) { plain(ex, 404, "Not found"); return; }
            body = in.readAllBytes();
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : "application/manifest+json";
        ex.getResponseHeaders().set("Content-Type", type);
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) throw new HttpError(415, "expected application/json");
        byte[] b;
        try (InputStream in = ex.getRequestBody()) {
            b = in.readNBytes(MAX_JSON + 1);
        }
        if (b.length > MAX_JSON) throw new HttpError(413, "body too large");
        try {
            return Json.obj(new String(b, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "bad JSON");
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return s == null ? def : Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        if (code >= 400) ex.getResponseHeaders().set("Connection", "close"); // body may be unread: don't reuse
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void plain(HttpExchange ex, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        if (code >= 400) ex.getResponseHeaders().set("Connection", "close");
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    @SuppressWarnings("unused")
    private static String newId() { return UUID.randomUUID().toString(); }
}
