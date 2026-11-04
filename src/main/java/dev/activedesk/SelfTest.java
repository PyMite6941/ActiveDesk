package dev.activedesk;

import dev.activedesk.chat.ChatMessage;
import dev.activedesk.net.Connection;
import dev.activedesk.net.Frame;
import dev.activedesk.sched.Schedule;
import dev.activedesk.sched.Scheduler;
import dev.activedesk.sched.TaskRun;
import dev.activedesk.tailscale.Tailscale;
import dev.activedesk.transfer.FileTransferService;
import dev.activedesk.util.Crypto;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * End-to-end test with two real nodes talking over loopback: peer handshake, chat, file transfer,
 * scheduler workflows, web security checks and the nc text console. CI runs it on every OS.
 */
final class SelfTest {
    private int pass;
    private int fail;

    private SelfTest() {}

    static boolean run() throws Exception {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        Log.init(null, true);
        return new SelfTest().all();
    }

    private void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    private static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }

    private boolean all() throws Exception {
        System.out.println("ActiveDesk self-test");
        units();
        Path tmp = Files.createTempDirectory("activedesk-selftest");
        int base = 20000 + new Random().nextInt(20000);
        Config ca = config(tmp.resolve("a"), "selftest-a", base, base + 1, "127.0.0.1:" + (base + 10));
        Config cb = config(tmp.resolve("b"), "selftest-b", base + 10, base + 11, "");
        String pinA = ca.freshPin();
        Node a = new Node(ca);
        Node b = new Node(cb);
        try {
            a.start();
            b.start();
            boolean up = waitFor(() -> a.hub().peers().size() == 1 && b.hub().peers().size() == 1, 45_000);
            check("peers connect and handshake over the NIO hub", up);
            if (!up) return false;
            Connection aToB = a.hub().peers().iterator().next();
            check("peer identity carried in hello", "selftest-b".equals(aToB.peerName()));

            // chat
            a.chat().post("general", "hello-from-a");
            check("chat message reaches the other device", waitFor(() -> has(b, "general", "hello-from-a"), 5000));
            a.chat().post("ops", "room-message");
            check("rooms are separate", waitFor(() -> has(b, "ops", "room-message"), 5000) && !has(b, "general", "room-message"));

            // file transfer
            Path f = tmp.resolve("payload.bin");
            byte[] data = new byte[700_000];
            new Random(42).nextBytes(data);
            Files.write(f, data);
            FileTransferService.Transfer t = a.transfers().sendBlocking(aToB.peerNodeId(), f, "payload.bin");
            Path got = cb.transferDir().resolve("payload.bin");
            check("file transfer completes with sha256 verified", "done".equals(t.status()) && Files.exists(got)
                    && java.util.Arrays.equals(Files.readAllBytes(got), data));
            a.transfers().sendBlocking(aToB.peerNodeId(), f, "../../evil.txt");
            check("path traversal in file names is neutralised", Files.exists(cb.transferDir().resolve("evil.txt"))
                    && !Files.exists(tmp.resolve("evil.txt")) && !Files.exists(cb.transferDir().getParent().resolve("evil.txt")));
            a.transfers().sendBlocking(aToB.peerNodeId(), f, "payload.bin");
            check("existing files are never overwritten", Files.exists(cb.transferDir().resolve("payload (1).bin")));

            // scheduler
            TaskRun r = a.scheduler().runWorkflow(new Scheduler.Trigger("announce", "selftest")).get(30, TimeUnit.SECONDS);
            check("workflow runs dependency then task", r.status() == TaskRun.Status.SUCCEEDED
                    && a.scheduler().runLog().recentFor("hello", 1).get(0).status() == TaskRun.Status.SUCCEEDED);
            check("chat task output reaches peers", waitFor(() -> has(b, "general", "hello task finished"), 5000));
            writeTasks(ca, a);
            check("dependency cycle is rejected", a.scheduler().invalidTasks().containsKey("cycle-a"));
            TaskRun skipped = a.scheduler().runWorkflow(new Scheduler.Trigger("after-fail", "selftest")).get(30, TimeUnit.SECONDS);
            check("failed dependency skips dependent (with retries)", skipped.status() == TaskRun.Status.SKIPPED
                    && a.scheduler().runLog().recentFor("fail", 1).get(0).attempts() == 2);
            TaskRun slow = a.scheduler().runWorkflow(new Scheduler.Trigger("slow", "selftest")).get(30, TimeUnit.SECONDS);
            check("timeout kills a hung task", slow.status() == TaskRun.Status.TIMED_OUT);

            web(ca, pinA);
            console(ca, cb);
        } finally {
            a.close();
            b.close();
        }
        System.out.println(pass + " passed, " + fail + " failed");
        return fail == 0;
    }

    private static boolean has(Node n, String room, String text) {
        for (ChatMessage m : n.chat().since(room, 0, 500)) if (m.text().contains(text)) return true;
        return false;
    }

    private static Config config(Path home, String name, int peer, int web, String staticPeers) throws Exception {
        Config c = Config.loadFrom(home);
        c.set("device.name", name);
        c.set("peer.port", String.valueOf(peer));
        c.set("web.port", String.valueOf(web));
        c.set("bind.tailscale", "false");
        c.set("control.enabled", "false");
        c.set("peers.static", staticPeers);
        c.set("transfer.dir", home.resolve("received").toString());
        c.save();
        return c;
    }

    private void writeTasks(Config c, Node n) throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String sleep = win ? "ping -n 6 127.0.0.1 >NUL" : "sleep 5";
        Path f = c.home().resolve("tasks.json");
        Files.writeString(f, Json.pretty(Json.o("tasks", List.of(
                Json.o("id", "fail", "type", "shell", "retries", 1, "retryDelay", "1s", "params", Json.o("command", "exit 3")),
                Json.o("id", "after-fail", "type", "chat", "dependsOn", List.of("fail"), "params", Json.o("text", "never")),
                Json.o("id", "slow", "type", "shell", "timeout", "1s", "params", Json.o("command", sleep)),
                Json.o("id", "cycle-a", "type", "chat", "dependsOn", List.of("cycle-b"), "params", Json.o("text", "x")),
                Json.o("id", "cycle-b", "type", "chat", "dependsOn", List.of("cycle-a"), "params", Json.o("text", "x"))))));
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        n.scheduler().reloadIfChanged();
    }

    private void web(Config c, String pin) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = "http://127.0.0.1:" + c.webPort();
        check("web UI is served", get(http, base + "/", null, null).body().contains("ActiveDesk"));
        check("API requires login", get(http, base + "/api/state", null, null).statusCode() == 401);
        check("foreign Host header refused (DNS rebinding)", get(http, base + "/", null, "evil.example").statusCode() == 421);
        check("wrong PIN refused", post(http, base + "/api/login", "{\"pin\":\"00000000x\"}", null, null).statusCode() == 403);
        HttpResponse<String> login = post(http, base + "/api/login", "{\"pin\":\"" + pin + "\"}", null, null);
        String token = login.statusCode() == 200 ? Json.str(Json.obj(login.body()), "token", "") : "";
        check("correct PIN gives a session", token.length() == 64);
        HttpResponse<String> st = get(http, base + "/api/state", token, null);
        check("session reads state incl. peers", st.statusCode() == 200 && st.body().contains("selftest-b"));
        check("security headers present", st.headers().firstValue("Content-Security-Policy").isPresent()
                && "DENY".equals(st.headers().firstValue("X-Frame-Options").orElse("")));
        HttpRequest cross = HttpRequest.newBuilder(URI.create(base + "/api/chat")).header("Authorization", "Bearer " + token)
                .header("Origin", "http://evil.example").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"room\":\"general\",\"text\":\"x\"}")).build();
        check("cross-origin request refused", http.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode() == 403);
        check("download cannot escape the transfer folder",
                get(http, base + "/api/files/get?name=..%2Fconfig.properties", token, null).statusCode() == 404);
        check("screen endpoint off when control disabled", get(http, base + "/api/screen", token, null).statusCode() == 503);
        check("chat via API", post(http, base + "/api/chat", "{\"room\":\"general\",\"text\":\"from-web\"}", token, null).statusCode() == 200);
        check("keep-awake rejects unknown modes",
                post(http, base + "/api/keepawake", "{\"mode\":\"forever\"}", token, null).statusCode() == 400);
        // a value changed on disk by another process (like `activedesk pin`) must survive the node saving a setting
        Files.writeString(c.file(), Files.readString(c.file()) + System.lineSeparator() + "selftest.marker=kept" + System.lineSeparator());
        boolean set = post(http, base + "/api/keepawake", "{\"mode\":\"off\"}", token, null).statusCode() == 200;
        String cfg = Files.readString(c.file());
        check("keep-awake setting saved without clobbering the config",
                set && cfg.contains("power.keepAwake=off") && cfg.contains("selftest.marker=kept")
                        && get(http, base + "/api/state", token, null).body().contains("\"keepAwake\":\"off\""));
        check("battery range validated by the API",
                post(http, base + "/api/battery", "{\"enabled\":true,\"min\":78,\"max\":80}", token, null).statusCode() == 400
                        && post(http, base + "/api/battery", "{\"enabled\":true,\"min\":10,\"max\":80}", token, null).statusCode() == 400);
        check("power settings validated by the API",
                post(http, base + "/api/power/profile", "{\"id\":\"no-such-mode\"}", token, null).statusCode() == 400
                        && post(http, base + "/api/power/brightness", "{\"value\":500}", token, null).statusCode() == 400
                        && post(http, base + "/api/power/saver", "{\"enabled\":true,\"below\":99,\"profile\":\"\",\"brightness\":0}", token, null).statusCode() == 400);
        String state = get(http, base + "/api/state", token, null).body();
        check("state reports power, battery and SSH fallback info",
                state.contains("\"power\":{") && state.contains("\"hasBattery\":") && state.contains("\"user\":") && state.contains("\"keepAwakeMethod\":"));
        check("bogus token refused", get(http, base + "/api/state", "f".repeat(64), null).statusCode() == 401);
        int last = 0;
        for (int i = 0; i < 6; i++) last = post(http, base + "/api/login", "{\"pin\":\"1\"}", null, null).statusCode();
        check("PIN brute force is rate limited", last == 429);
    }

    private static HttpResponse<String> get(HttpClient h, String url, String token, String host) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (host != null) b.header("Host", host);
        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient h, String url, String body, String token, String host) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (host != null) b.header("Host", host);
        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void console(Config a, Config b) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), a.peerPort()), 2000);
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.write("\n/who\n/lock\n/auth nope\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder all = new StringBuilder();
            long end = System.currentTimeMillis() + 4000;
            try {
                String l;
                while (System.currentTimeMillis() < end && (l = in.readLine()) != null) {
                    all.append(l).append('\n');
                    if (l.startsWith("ERR auth failed")) break;
                }
            } catch (java.net.SocketTimeoutException ignored) {
            }
            String t = all.toString();
            check("nc text console works and lists peers", t.contains("ActiveDesk on selftest-a") && t.contains("selftest-b"));
            check("privileged console commands need the local token", t.contains("ERR this command is only available")
                    && t.contains("ERR auth failed"));
            out.write(("/auth " + a.get("local.token") + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            boolean ok = false;
            try {
                String l;
                while ((l = in.readLine()) != null) if (l.startsWith("OK authenticated")) { ok = true; break; }
            } catch (java.net.SocketTimeoutException ignored) {
            }
            check("local CLI token authenticates", ok);
        }
        if (b == null) throw new IllegalStateException();
    }

    private void units() throws Exception {
        Map<String, Object> m = Json.obj("{\"a\":[1,2.5,\"x\\n\\u00e9\"],\"b\":{\"c\":true,\"d\":null}}");
        check("json round trip", Json.obj(Json.write(m)).equals(m));
        check("schedule parsing", Schedule.parse("every 15m").kind() == Schedule.Kind.EVERY
                && Schedule.parse("daily 09:30").kind() == Schedule.Kind.DAILY && Schedule.parseDuration("2h").toMinutes() == 120);
        check("unsafe file names sanitised", FileTransferService.safeName("..\\..\\x/CON.txt").equals("_CON.txt")
                && FileTransferService.safeName("a<b>:c?.txt").equals("a_b__c_.txt") && FileTransferService.safeName("...").equals("file"));
        check("tailnet address detection", Tailscale.isTailnetAddress("100.65.1.2") && !Tailscale.isTailnetAddress("100.128.0.1")
                && !Tailscale.isTailnetAddress("192.168.1.5") && Tailscale.isTailnetAddress("fd7a:115c:a1e0::1"));
        Frame f = new Frame(Json.o("type", "x", "n", 1), new byte[]{1, 2, 3});
        ByteBuffer bb = f.encode();
        Frame g = Frame.decode(bb);
        check("frame encode/decode", g != null && "x".equals(g.type()) && g.payload().length == 3);
        check("keep-awake mode parsing", dev.activedesk.remote.KeepAwake.parse("always") == dev.activedesk.remote.KeepAwake.Mode.ALWAYS
                && dev.activedesk.remote.KeepAwake.parse("off") == dev.activedesk.remote.KeepAwake.Mode.OFF
                && dev.activedesk.remote.KeepAwake.parse("garbage") == dev.activedesk.remote.KeepAwake.Mode.SESSION);
        check("battery range rules", dev.activedesk.power.BatteryService.invalid(40, 80) == null
                && dev.activedesk.power.BatteryService.invalid(19, 80) != null && dev.activedesk.power.BatteryService.invalid(60, 62) != null
                && dev.activedesk.power.BatteryService.invalid(40, 101) != null);
        String h = Crypto.hashSecret("12345678");
        check("pin hashing", Crypto.verifySecret("12345678", h) && !Crypto.verifySecret("12345679", h));
    }
}
