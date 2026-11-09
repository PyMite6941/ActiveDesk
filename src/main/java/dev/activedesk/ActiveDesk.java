package dev.activedesk;

import dev.activedesk.net.Hub;
import dev.activedesk.tailscale.Tailscale;
import dev.activedesk.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Command line entry point. `activedesk help` lists commands. */
public final class ActiveDesk {
    private ActiveDesk() {}

    public static void main(String[] args) throws Exception {
        // No arguments (double-click, Start menu, plain `activedesk`): start the node if needed and
        // open the app in the browser. `run` stays silent, for autostart at login and services.
        String cmd = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        List<String> rest = Arrays.asList(args).subList(Math.min(1, args.length), args.length);
        switch (cmd) {
            case "run" -> run(false);
            case "open", "gui", "app" -> open();
            case "chat" -> chat(rest.isEmpty() ? "general" : rest.get(0));
            case "send" -> {
                if (rest.size() < 2) usage();
                Path p = Path.of(rest.get(1)).toAbsolutePath();
                System.exit(local(List.of("/send " + rest.get(0) + " " + p),
                        l -> l.startsWith("OK sent") || l.startsWith("ERR"), 3_600_000) ? 0 : 1);
            }
            case "lock" -> System.exit(local(List.of("/lock"), l -> l.startsWith("OK") || l.startsWith("ERR"), 10_000) ? 0 : 1);
            case "awake" -> System.exit(local(List.of("/awake" + (rest.isEmpty() ? "" : " " + rest.get(0))),
                    l -> l.startsWith("OK") || l.startsWith("ERR"), 10_000) ? 0 : 1);
            case "battery" -> battery(rest);
            case "power" -> power(rest);
            case "setup" -> Setup.run(rest);
            case "run-task" -> {
                if (rest.isEmpty()) usage();
                System.exit(local(List.of("/run " + rest.get(0)), l -> l.startsWith("OK") || l.startsWith("ERR"), 10_000) ? 0 : 1);
            }
            case "who", "peers" -> local(List.of("/who"), l -> false, 1500);
            case "tasks" -> local(List.of("/tasks"), l -> false, 1500);
            case "pin" -> pin();
            case "status" -> status();
            case "install-service" -> Installer.install();
            case "selftest" -> System.exit(SelfTest.run() ? 0 : 1);
            case "version", "--version", "-v" -> System.out.println("ActiveDesk " + Hub.VERSION);
            default -> usage();
        }
    }

    private static void power(List<String> rest) throws Exception {
        String sub = rest.isEmpty() ? "status" : rest.get(0).toLowerCase(Locale.ROOT);
        Predicate<String> done = l -> l.startsWith("OK") || l.startsWith("ERR");
        switch (sub) {
            case "install" -> System.exit(dev.activedesk.power.PowerSetup.install(Config.load(), true, System.out::println));
            case "uninstall" -> System.exit(dev.activedesk.power.PowerSetup.uninstall(Config.load(), true, System.out::println));
            case "status" -> System.exit(local(List.of("/power"), done, 15_000) ? 0 : 1);
            case "mode", "brightness" -> {
                // mode names can contain spaces ("Ultimate Performance"), so join the rest
                String arg = rest.size() < 2 ? "" : String.join(" ", rest.subList(1, rest.size()));
                if (!arg.matches("[A-Za-z0-9._{}() -]{1,80}") || ("brightness".equals(sub) && rest.size() != 2)) usage();
                System.exit(local(List.of("/power " + sub + " " + arg), done, 20_000) ? 0 : 1);
            }
            case "saver" -> {
                if (rest.size() < 2 || rest.size() > 3 || !String.join(" ", rest.subList(1, rest.size())).matches("off|[0-9]{1,2}( [0-9]{1,3})?")) usage();
                System.exit(local(List.of("/power saver " + String.join(" ", rest.subList(1, rest.size()))), done, 20_000) ? 0 : 1);
            }
            default -> usage();
        }
    }

    private static void battery(List<String> rest) throws Exception {
        String sub = rest.isEmpty() ? "status" : rest.get(0).toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "install" -> System.exit(dev.activedesk.power.PowerSetup.install(Config.load(), true, System.out::println));
            case "uninstall" -> System.exit(dev.activedesk.power.PowerSetup.uninstall(Config.load(), true, System.out::println));
            case "status" -> System.exit(local(List.of("/battery"), l -> l.startsWith("OK") || l.startsWith("ERR"), 15_000) ? 0 : 1);
            case "on", "off" -> System.exit(local(List.of("/battery " + sub), l -> l.startsWith("OK") || l.startsWith("ERR"), 15_000) ? 0 : 1);
            default -> {
                if (rest.size() != 2 || !rest.get(0).matches("[0-9]{1,3}") || !rest.get(1).matches("[0-9]{1,3}")) usage();
                System.exit(local(List.of("/battery " + rest.get(0) + " " + rest.get(1)),
                        l -> l.startsWith("OK") || l.startsWith("ERR"), 15_000) ? 0 : 1);
            }
        }
    }

    private static void usage() {
        System.out.println(String.join("\n",
                "ActiveDesk " + Hub.VERSION + " - private chat, file transfer, remote screen and task scheduler over Tailscale",
                "",
                "  activedesk                     start the node if needed and open the app in your browser",
                "  activedesk run                 start the node only, no browser (autostart, services)",
                "  activedesk chat [room]         chat in the terminal (or: nc <device> 47800)",
                "  activedesk send <device> <file>  send a file to another device",
                "  activedesk who                 connected devices",
                "  activedesk tasks               scheduled tasks",
                "  activedesk run-task <id>       run a task (and its dependencies) now",
                "  activedesk lock                end every remote-control / web session now",
                "  activedesk awake [off|session|always]  keep the screen on (session = while being controlled)",
                "  activedesk setup               guided first-time setup (PIN, Tailscale check, autostart, power)",
                "  activedesk power install       one-time admin/root setup for power mode, brightness and charge limits",
                "  activedesk power [status|mode <name|id>|brightness <1-100>|saver off|saver <below%> [dim%]]",
                "  activedesk battery <min> <max> only charge between min% and max% while ActiveDesk runs (e.g. 40 80)",
                "  activedesk battery on|off|status|uninstall",
                "  activedesk pin                 set a new web PIN (old sessions keep working until 'lock')",
                "  activedesk status              Tailscale + config summary",
                "  activedesk install-service     start ActiveDesk automatically at login/boot",
                "  activedesk selftest            run the built-in end-to-end test"));
        System.exit(2);
    }

    // ------------------------------------------------------------------ run

    /** Open the web app; start the node first when none is running on this device. */
    private static void open() throws Exception {
        Config config = Config.load();
        if (canConnect(config.peerPort())) {
            String url = "http://127.0.0.1:" + config.webPort() + "/";
            System.out.println("ActiveDesk is already running. Opening " + url);
            if (!openBrowser(url)) System.out.println("Could not open a browser here. Open " + url + " yourself.");
            return;
        }
        run(true);
    }

    /** Best effort: true when a browser was asked to open the URL. */
    static boolean openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                return true;
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
                return true;
            }
            if (System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null) return false; // headless Pi / server
            new ProcessBuilder("xdg-open", url).redirectErrorStream(true).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void run(boolean openApp) throws Exception {
        Config config = Config.load();
        Log.init(config.home().resolve("activedesk.log"), false);
        Node node = new Node(config);
        try {
            node.start();
        } catch (java.net.BindException e) {
            System.err.println("  Can't start: port " + config.peerPort() + " or " + config.webPort()
                    + " is already in use. ActiveDesk is probably already running (check: activedesk status),"
                    + " or another program uses that port (change peer.port / web.port in "
                    + config.home().resolve("config.properties") + ").");
            System.exit(1);
        }
        Tailscale.Status st = node.tailscale().status();
        System.out.println();
        System.out.println("  ActiveDesk " + Hub.VERSION + " running as '" + config.deviceName() + "'");
        boolean tsBind = config.get("bind.tailscale").isBlank() || config.getBool("bind.tailscale");
        if (!tsBind) {
            System.out.println("  bind.tailscale=false: listening on this computer only, http://127.0.0.1:" + config.webPort() + "/");
        } else if (st.running() && st.self().ipv4() != null) {
            System.out.println("  Open on your phone / other computer (Tailscale on):  http://" + st.self().ipv4() + ":" + config.webPort() + "/");
            System.out.println("  Terminal chat from any tailnet device:               nc " + st.self().ipv4() + " " + config.peerPort());
        } else {
            System.out.println("  Tailscale is not running here (" + st.backendState() + "). Only http://127.0.0.1:" + config.webPort()
                    + "/ works until it is. ActiveDesk will bind to Tailscale automatically once it is up.");
        }
        if (config.freshPin() != null) {
            System.out.println();
            System.out.println("  Your web PIN (shown once, write it down):  " + config.freshPin());
            System.out.println("  Forgot it? Run 'activedesk pin' to set a new one.");
        }
        System.out.println("  Data folder: " + config.home());
        if (openApp) {
            String url = "http://127.0.0.1:" + config.webPort() + "/";
            System.out.println(openBrowser(url) ? "  Opened the app in your browser: " + url
                    : "  Open the app on this computer:  " + url);
            System.out.println("  Keep this window open while you use ActiveDesk (closing it stops it).");
        }
        System.out.println();
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            node.close();
            done.countDown();
        }));
        done.await();
    }

    private static void pin() throws IOException {
        Config c = Config.load();
        if (c.freshPin() != null) {
            System.out.println("New PIN: " + c.freshPin());
            return;
        }
        System.out.println("New PIN: " + c.resetPin());
        System.out.println("Takes effect immediately on the running node. Run 'activedesk lock' to also end existing sessions.");
    }

    private static void status() throws IOException {
        Config c = Config.load();
        Tailscale ts = new Tailscale(c.get("tailscale.path"));
        Tailscale.Status st = ts.status(true);
        System.out.println("device      : " + c.deviceName() + "  (node " + c.nodeId() + ")");
        System.out.println("config      : " + c.file());
        System.out.println("tailscale   : " + st.backendState() + "  [" + ts.binary() + "]");
        if (st.self() != null) System.out.println("this device : " + st.self().displayName() + " " + st.self().ips() + " owner " + st.self().login());
        for (Tailscale.Peer p : st.peers())
            System.out.println("  " + (p.online() ? "online " : "offline") + "  " + p.displayName() + "  " + p.ipv4() + "  " + p.os() + "  " + p.login());
        System.out.println("allowed     : your own login" + (c.getList("allow.logins").isEmpty() ? "" : " + " + c.getList("allow.logins"))
                + (c.getList("allow.tags").isEmpty() ? "" : ", tags " + c.getList("allow.tags")));
        System.out.println("node running: " + (canConnect(c.peerPort()) ? "yes" : "no"));
    }

    private static boolean canConnect(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ local console client

    /** Interactive terminal chat through the local node's text console. */
    private static void chat(String room) throws IOException {
        Config c = Config.load();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), c.peerPort()), 2000);
            OutputStream out = s.getOutputStream();
            send(out, "/auth " + c.get("local.token"));
            send(out, "/join " + room);
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = in.readLine()) != null) if (!l.startsWith("OK authenticated")) System.out.println(l);
                } catch (IOException ignored) {
                }
                System.exit(0);
            });
            reader.setDaemon(true);
            reader.start();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = stdin.readLine()) != null) {
                send(out, line);
                if (line.trim().equals("/quit")) break;
            }
        } catch (IOException e) {
            System.err.println("ActiveDesk is not running on this device (start it with 'activedesk run').");
            System.exit(1);
        }
    }

    /** Send commands to the local node and print replies until one matches {@code until} or the timeout passes. */
    static boolean local(List<String> commands, Predicate<String> until, long timeoutMs) throws IOException {
        Config c = Config.load();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), c.peerPort()), 2000);
            s.setSoTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            send(out, "/auth " + c.get("local.token"));
            send(out, "/join cli-" + Long.toHexString(System.nanoTime() & 0xffff)); // quiet room: no history noise
            boolean header = true;
            for (String cmd : commands) send(out, cmd);
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                String l;
                while (System.currentTimeMillis() < deadline && (l = in.readLine()) != null) {
                    if (header && (l.startsWith("ActiveDesk on ") || l.startsWith("[") || l.startsWith("OK authenticated") || l.startsWith("joined #"))) continue;
                    header = false;
                    if (l.startsWith("ERR auth")) {
                        System.out.println("Local auth failed (config changed?). Restart the node.");
                        return false;
                    }
                    System.out.println(l);
                    if (until.test(l)) return !l.startsWith("ERR");
                }
            } catch (java.net.SocketTimeoutException e) {
                return true; // listing commands simply stop after the timeout
            }
            return false;
        } catch (java.net.ConnectException e) {
            System.err.println("ActiveDesk is not running on this device (start it with 'activedesk run').");
            return false;
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @SuppressWarnings("unused")
    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean exists(Path p) { return Files.exists(p); }
}
