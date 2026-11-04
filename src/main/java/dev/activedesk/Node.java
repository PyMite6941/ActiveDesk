package dev.activedesk;

import dev.activedesk.chat.ChatService;
import dev.activedesk.net.Connection;
import dev.activedesk.net.Frame;
import dev.activedesk.net.Hub;
import dev.activedesk.net.TextConsole;
import dev.activedesk.remote.ControlIndicator;
import dev.activedesk.remote.KeepAwake;
import dev.activedesk.power.BatteryService;
import dev.activedesk.power.PowerService;
import dev.activedesk.power.PowerSetup;
import dev.activedesk.remote.ScreenService;
import dev.activedesk.sched.RunLog;
import dev.activedesk.sched.Scheduler;
import dev.activedesk.sched.ThreadPools;
import dev.activedesk.tailscale.AccessPolicy;
import dev.activedesk.tailscale.Tailscale;
import dev.activedesk.transfer.FileTransferService;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import dev.activedesk.web.WebServer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import dev.activedesk.net.Lan;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** One ActiveDesk device: peer network + chat + file transfer + remote screen + scheduler + web UI. */
public final class Node implements Hub.Listener, AutoCloseable {
    private final Config config;
    private final Tailscale tailscale;
    private final AccessPolicy policy;
    private final Hub hub;
    private final ChatService chat;
    private final FileTransferService transfers;
    private final ScreenService screen;
    private final ControlIndicator indicator;
    private final KeepAwake keepAwake;
    private final BatteryService battery;
    private final PowerService power;
    private volatile String setupStatus = "";
    private volatile long setupStarted;
    private final Scheduler scheduler;
    private final WebServer web;
    private final TextConsole console;
    private final Path auditFile;
    private final Path devicesFile;
    /** Every ActiveDesk device ever seen: what we need to wake it when it is asleep. */
    private final Map<String, Map<String, Object>> known = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWake = new ConcurrentHashMap<>();

    public Node(Config config) throws IOException {
        this.config = config;
        this.auditFile = config.home().resolve("audit.log");
        this.devicesFile = config.home().resolve("devices.json");
        loadKnown();
        this.tailscale = new Tailscale(config.get("tailscale.path"));
        this.policy = new AccessPolicy(tailscale, config);
        this.screen = new ScreenService(config.getBool("control.enabled"));
        this.screen.setTypeMode(config.get("control.typeMode"));
        this.indicator = new ControlIndicator(config.getBool("control.indicator") && screen.available());
        this.keepAwake = new KeepAwake(screen, KeepAwake.parse(config.get("power.keepAwake")));
        this.power = new PowerService(config);
        this.battery = new BatteryService(config, power);
        this.hub = new Hub(config.nodeId(), policy, this::helloInfo);
        this.chat = new ChatService(hub, config.nodeId(), config.deviceName(), config.home().resolve("chat.jsonl"));
        long maxBytes;
        try {
            maxBytes = Long.parseLong(config.get("transfer.maxBytes").isBlank() ? "8589934592" : config.get("transfer.maxBytes").trim());
        } catch (NumberFormatException e) {
            maxBytes = 8L * 1024 * 1024 * 1024;
        }
        this.transfers = new FileTransferService(hub, config.transferDir(), config.getBool("transfer.autoAccept"), maxBytes);
        this.transfers.setNotifier(chat::system);
        this.scheduler = new Scheduler(config, RunLog.open(config), chat::post);
        this.web = new WebServer(this);
        this.console = new TextConsole(this);
        hub.setListener(this);
    }

    private Map<String, Object> helloInfo() {
        return Json.o("name", config.deviceName(), "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "screen", screen.available(), "webPort", config.webPort(), "lan", lanInfo(),
                "rdp", rdpAvailable());
    }

    public void start() throws IOException {
        hub.start();
        InetAddress lo = InetAddress.getLoopbackAddress();
        hub.bind(lo, config.peerPort());
        web.bind(lo, config.webPort());
        bindTailscale();
        chat.addListener(console::onChat);
        scheduler.start();
        ThreadPools.get().ticker().scheduleWithFixedDelay(this::maintain, 3, 20, TimeUnit.SECONDS);
        ThreadPools.get().ticker().scheduleWithFixedDelay(this::keepAwakeTick, 1, 10, TimeUnit.SECONDS);
        battery.start();
        ThreadPools.get().io().submit(this::powerTick); // detection can take a couple of seconds on Windows
        ThreadPools.get().ticker().scheduleWithFixedDelay(() -> ThreadPools.get().io().submit(this::powerTick), 30, 30, TimeUnit.SECONDS);
        audit("node started (" + config.deviceName() + ")");
    }

    /** Bind to this device's Tailscale IPv4 when it exists. Retried periodically (Tailscale may start later). */
    private void bindTailscale() {
        if (!config.get("bind.tailscale").isBlank() && !config.getBool("bind.tailscale")) return;
        Tailscale.Status st = tailscale.status();
        if (!st.running() || st.self().ipv4() == null) return;
        try {
            InetAddress ts = InetAddress.getByName(st.self().ipv4());
            if (!hub.isBound(ts, config.peerPort())) hub.bind(ts, config.peerPort());
            if (!web.isBound(ts, config.webPort())) web.bind(ts, config.webPort());
        } catch (IOException e) {
            Log.debug("tailscale bind not possible yet: " + e.getMessage());
        }
    }

    private void keepAwakeTick() {
        try {
            keepAwake.tick();
        } catch (RuntimeException e) {
            Log.error("keep-awake tick failed", e);
        }
    }

    private void powerTick() {
        try {
            power.tick();     // battery level, mode, brightness, auto saver
            battery.tick();   // charge range (uses the level power just read)
        } catch (RuntimeException e) {
            Log.error("power tick failed", e);
        }
    }

    public void setPowerProfile(String id, String who) throws IOException {
        power.setProfile(id);
        audit("power mode set to " + id + " by " + who);
    }

    public void setBrightness(int pct, String who) throws IOException {
        power.setBrightness(pct);
        audit("brightness set to " + pct + "% by " + who);
    }

    public void setPowerSaver(boolean enabled, int below, String profile, int dim, String who) throws IOException {
        power.setSaver(enabled, below, profile, dim);
        audit("auto power saver " + (enabled ? "on below " + below + "%" : "off") + " set by " + who);
    }

    /**
     * Start the one-time privileged setup in the background. The OS shows its admin prompt on THIS device,
     * so whoever triggers it remotely still needs someone (or their own remote view) to approve it here.
     */
    public synchronized boolean startPowerSetup(String who) {
        if (System.currentTimeMillis() - setupStarted < 120_000 && setupStatus.startsWith("running")) return false;
        setupStarted = System.currentTimeMillis();
        setupStatus = "running: approve the admin prompt on this device";
        audit("power setup started by " + who);
        Thread t = new Thread(() -> {
            StringBuilder log = new StringBuilder();
            int rc;
            try {
                rc = PowerSetup.install(config, false, line -> log.append(line).append(' '));
            } catch (IOException | RuntimeException e) {
                rc = -1;
                log.append(e.getMessage());
            }
            setupStatus = (rc == 0 ? "done: " : "failed: ") + log.toString().trim();
            audit("power setup " + (rc == 0 ? "completed" : "failed (" + rc + ")"));
            battery.redetect();
            power.tick();
        }, "power-setup");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public String powerSetupStatus() { return setupStatus; }

    /** Change the battery charge range (persisted) and apply it now. */
    public void setBattery(boolean enabled, int min, int max, String who) throws IOException {
        battery.set(enabled, min, max);
        audit("battery range " + (enabled ? min + "-" + max + "%" : "off") + " set by " + who);
    }

    /** Change the keep-awake mode now and remember it across restarts. */
    public void setKeepAwake(KeepAwake.Mode m, String who) {
        keepAwake.setMode(m);
        try {
            config.update("power.keepAwake", m.name().toLowerCase(java.util.Locale.ROOT));
        } catch (IOException e) {
            Log.error("could not save keep-awake setting", e);
        }
        audit("keep-awake set to " + m.name().toLowerCase(java.util.Locale.ROOT) + " by " + who);
    }

    /** Periodic: (re)bind, dial every online tailnet device and static peers. */
    private void maintain() {
        try {
            bindTailscale();
            Tailscale.Status st = tailscale.status(true);
            if (st.running()) {
                for (Tailscale.Peer p : st.peers()) {
                    if (p.online() && p.ipv4() != null) hub.dial(p.ipv4(), config.peerPort());
                }
            }
            for (String sp : config.getList("peers.static")) {
                int c = sp.lastIndexOf(':');
                String host = c > 0 ? sp.substring(0, c) : sp;
                int port = c > 0 ? Integer.parseInt(sp.substring(c + 1)) : config.peerPort();
                hub.dial(host, port);
            }
        } catch (RuntimeException e) {
            Log.error("maintenance failed", e);
        }
    }

    // ---------------------------------------------------------------- wake-on-LAN

    private static List<Object> lanInfo() {
        List<Object> l = new ArrayList<>();
        for (Lan.Iface i : Lan.interfaces()) l.add(Json.o("mac", i.mac(), "broadcast", i.broadcast()));
        return l;
    }

    /** Windows with Remote Desktop listening: the only supported way to type at the Windows sign-in screen. */
    private static boolean rdpAvailable() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 3389), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void remember(Connection c) {
        Map<String, Object> h = c.peerHello();
        List<Object> lan = new ArrayList<>();
        for (Object o : Json.list(h, "lan")) {
            if (o instanceof Map<?, ?> m && Lan.validMac(String.valueOf(m.get("mac")))) {
                lan.add(Json.o("mac", String.valueOf(m.get("mac")), "broadcast", String.valueOf(m.get("broadcast"))));
            }
        }
        Map<String, Object> d = Json.o("nodeId", c.peerNodeId(), "name", c.peerName(), "os", c.peerOs(),
                "ip", c.remoteIp(), "webPort", c.peerWebPort(), "lan", lan,
                "rdp", Json.bool(h, "rdp", false), "lastSeen", System.currentTimeMillis());
        if (lan.isEmpty() && known.containsKey(c.peerNodeId())) d.put("lan", Json.list(known.get(c.peerNodeId()), "lan"));
        known.put(c.peerNodeId(), d);
        saveKnown();
    }

    private void loadKnown() {
        try {
            if (!Files.exists(devicesFile)) return;
            Map<String, Object> root = Json.obj(Files.readString(devicesFile, StandardCharsets.UTF_8));
            root.forEach((k, v) -> {
                if (v instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
                    known.put(k, m);
                }
            });
        } catch (IOException | RuntimeException e) {
            Log.warn("could not read devices.json: " + e.getMessage());
        }
    }

    private synchronized void saveKnown() {
        try {
            Files.writeString(devicesFile, Json.pretty(new LinkedHashMap<>(known)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("could not write devices.json: " + e.getMessage());
        }
    }

    public Map<String, Map<String, Object>> knownDevices() { return known; }

    /**
     * Wake a sleeping device: broadcast its magic packet from here AND ask every awake peer to do the same,
     * so whichever device shares its LAN (e.g. an always-on Pi) gets it there.
     */
    public int wake(String nodeId, String who) {
        Map<String, Object> d = known.get(nodeId);
        if (d == null) return -1;
        List<String> macs = new ArrayList<>();
        Set<String> bcast = new LinkedHashSet<>();
        for (Object o : Json.list(d, "lan")) {
            if (o instanceof Map<?, ?> m) {
                macs.add(String.valueOf(m.get("mac")));
                bcast.add(String.valueOf(m.get("broadcast")));
            }
        }
        if (macs.isEmpty()) return 0;
        audit("WAKE " + d.get("name") + " requested by " + who);
        int sent = wakeLocal(macs, bcast);
        hub.broadcast(Frame.of(Json.o("type", "wake", "macs", macs, "broadcasts", new ArrayList<>(bcast))));
        return sent + hub.peers().size();
    }

    private int wakeLocal(List<String> macs, Set<String> extraBroadcasts) {
        Set<String> b = new LinkedHashSet<>(extraBroadcasts);
        for (Lan.Iface i : Lan.interfaces()) b.add(i.broadcast());
        int sent = 0;
        long now = System.currentTimeMillis();
        for (String mac : macs) {
            if (!Lan.validMac(mac)) continue;
            Long last = lastWake.get(mac);
            if (last != null && now - last < 10_000) continue; // rate limit per target
            lastWake.put(mac, now);
            sent += Lan.wake(mac, b);
        }
        return sent;
    }

    private void onWakeRequest(Connection c, Frame f) {
        List<String> macs = new ArrayList<>();
        for (Object o : Json.list(f.header(), "macs")) if (macs.size() < 8) macs.add(String.valueOf(o));
        Set<String> b = new LinkedHashSet<>();
        for (Object o : Json.list(f.header(), "broadcasts")) if (b.size() < 8) b.add(String.valueOf(o));
        ThreadPools.get().io().execute(() -> {
            int n = wakeLocal(macs, b);
            if (n > 0) Log.info("relayed wake-on-lan for " + macs + " (asked by " + c.peerName() + ")");
        });
    }

    public void dialNow() { ThreadPools.get().io().execute(this::maintain); }

    public synchronized void audit(String event) {
        String line = LocalDateTime.now().withNano(0) + " " + event + System.lineSeparator();
        Log.info("AUDIT " + event);
        try {
            Files.writeString(auditFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.error("audit write failed", e);
        }
    }

    // ---------------------------------------------------------------- Hub.Listener

    @Override
    public void onPeerUp(Connection c) {
        remember(c);
        chat.onPeerUp(c);
        chat.system("general", c.peerName() + " connected");
    }

    @Override
    public void onPeerDown(Connection c) { chat.system("general", c.peerName() + " disconnected"); }

    @Override
    public void onFrame(Connection c, Frame f) {
        String t = f.type();
        if (t.startsWith("chat")) chat.onFrame(c, f);
        else if (t.startsWith("file_")) transfers.onFrame(c, f);
        else if (t.equals("wake")) onWakeRequest(c, f);
        else Log.debug("ignored frame " + t + " from " + c);
    }

    @Override
    public void onTextOpen(Connection c) { console.open(c); }

    @Override
    public void onTextLine(Connection c, String line) { console.line(c, line); }

    @Override
    public void onTextClosed(Connection c) { }

    // ---------------------------------------------------------------- accessors

    public Config config() { return config; }
    public Tailscale tailscale() { return tailscale; }
    public AccessPolicy policy() { return policy; }
    public Hub hub() { return hub; }
    public ChatService chat() { return chat; }
    public FileTransferService transfers() { return transfers; }
    public ScreenService screen() { return screen; }
    public ControlIndicator indicator() { return indicator; }
    public KeepAwake keepAwake() { return keepAwake; }
    public BatteryService battery() { return battery; }
    public PowerService power() { return power; }
    public Scheduler scheduler() { return scheduler; }
    public WebServer web() { return web; }

    @Override
    public void close() {
        audit("node stopping");
        scheduler.close();
        web.stop();
        hub.stop();
        transfers.close();
        keepAwake.close();
        battery.close();
        power.close();
    }
}
