package dev.activedesk.net;

import dev.activedesk.sched.ThreadPools;
import dev.activedesk.tailscale.AccessPolicy;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Non-blocking peer network (java.nio Selector). One thread multiplexes every listening socket,
 * every inbound connection and every outbound dial. Other threads never touch a SelectionKey
 * directly: they enqueue a Runnable onto {@code pending} and wake the selector.
 *
 * Every connection passes the {@link AccessPolicy} before a single byte of it is interpreted.
 * Inbound connections then self-identify: binary peers send the 5-byte magic, anything else that
 * sends a line is treated as a human on netcat (text mode).
 */
public final class Hub implements Runnable {

    public interface Listener {
        void onPeerUp(Connection c);
        void onPeerDown(Connection c);
        void onFrame(Connection c, Frame f);
        void onTextOpen(Connection c);
        void onTextLine(Connection c, String line);
        void onTextClosed(Connection c);
    }

    private static final int MAX_CONNECTIONS = 64;
    private static final int MAX_TEXT_LINE = 4096;
    private static final long HELLO_TIMEOUT_MS = 15_000;
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long IDLE_TIMEOUT_MS = 90_000;
    private static final long PING_EVERY_MS = 25_000;
    public static final String VERSION = "1.0.3";

    private final String nodeId;
    private final AccessPolicy policy;
    private final Supplier<Map<String, Object>> helloInfo;
    private final Selector selector;
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final Set<Connection> conns = ConcurrentHashMap.newKeySet();
    private final Map<String, Connection> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Set<String> dialing = ConcurrentHashMap.newKeySet();
    private final Set<String> boundAddrs = ConcurrentHashMap.newKeySet();
    private volatile Listener listener;
    private volatile boolean running;
    private Thread thread;
    private long lastHousekeeping;

    public Hub(String nodeId, AccessPolicy policy, Supplier<Map<String, Object>> helloInfo) throws IOException {
        this.nodeId = nodeId;
        this.policy = policy;
        this.helloInfo = helloInfo;
        this.selector = Selector.open();
    }

    public void setListener(Listener l) { this.listener = l; }

    public void start() {
        running = true;
        thread = new Thread(this, "ad-nio");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        selector.wakeup();
        try {
            if (thread != null) thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Connection c : conns) closeQuietly(c);
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }

    /** Listen on one specific address. Never call with 0.0.0.0: we only bind loopback + Tailscale. */
    public void bind(InetAddress addr, int port) throws IOException {
        String key = addr.getHostAddress() + ":" + port;
        if (!boundAddrs.add(key)) return;
        ServerSocketChannel ssc = ServerSocketChannel.open();
        try {
            ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ssc.bind(new InetSocketAddress(addr, port), 32);
            ssc.configureBlocking(false);
        } catch (IOException e) {
            boundAddrs.remove(key);
            ssc.close();
            throw e;
        }
        submit(() -> {
            try {
                ssc.register(selector, SelectionKey.OP_ACCEPT);
            } catch (ClosedChannelException e) {
                Log.error("bind register failed", e);
            }
        });
        Log.info("peer protocol listening on " + key);
    }

    public boolean isBound(InetAddress addr, int port) { return boundAddrs.contains(addr.getHostAddress() + ":" + port); }

    private void submit(Runnable r) {
        pending.add(r);
        selector.wakeup();
    }

    // ------------------------------------------------------------------ public API

    public Collection<Connection> peers() { return peers.values(); }

    public Connection peer(String nodeId) { return peers.get(nodeId); }

    public boolean isConnectedTo(String ip, int port) {
        String k = ip + ":" + port;
        if (dialing.contains(k)) return true;
        for (Connection c : conns) {
            if (c.closed) continue;
            if (!c.remoteIp.equals(ip)) continue;
            if (c.outbound ? c.remotePort == port : c.mode == Connection.Mode.BINARY) return true;
        }
        return false;
    }

    /** Dial a peer unless already connected or backing off after failures. Policy is checked first. */
    public void dial(String ip, int port) {
        String k = ip + ":" + port;
        long now = System.currentTimeMillis();
        if (isConnectedTo(ip, port) || retryAt.getOrDefault(k, 0L) > now) return;
        if (!dialing.add(k)) return;
        ThreadPools.get().io().execute(() -> {
            AccessPolicy.Decision d = policy.check(ip);
            if (!d.allowed()) {
                Log.debug("not dialing " + k + ": " + d.reason());
                dialing.remove(k);
                backoff(k);
                return;
            }
            submit(() -> {
                try {
                    SocketChannel ch = SocketChannel.open();
                    ch.configureBlocking(false);
                    ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                    Connection c = new Connection(ch, true, ip, port);
                    c.authorized = true;
                    c.who = d.who();
                    conns.add(c);
                    boolean done = ch.connect(new InetSocketAddress(ip, port));
                    c.key = ch.register(selector, done ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT, c);
                    if (done) onConnected(c);
                } catch (IOException e) {
                    dialing.remove(k);
                    backoff(k);
                }
            });
        });
    }

    private void backoff(String k) {
        int n = failures.merge(k, 1, Integer::sum);
        long delay = Math.min(300_000L, 5_000L * (1L << Math.min(6, n - 1)));
        retryAt.put(k, System.currentTimeMillis() + delay);
    }

    public boolean send(Connection c, Frame f) {
        if (c == null || c.closed) return false;
        ByteBuffer b = f.encode();
        c.pendingBytes.addAndGet(b.remaining());
        c.out.add(b);
        submit(() -> enableWrite(c));
        return true;
    }

    public boolean sendTo(String nodeId, Frame f) { return send(peers.get(nodeId), f); }

    public int broadcast(Frame f) {
        int n = 0;
        for (Connection c : peers.values()) if (send(c, f)) n++;
        return n;
    }

    public void sendText(Connection c, String text) {
        if (c == null || c.closed || c.mode != Connection.Mode.TEXT) return;
        ByteBuffer b = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        c.pendingBytes.addAndGet(b.remaining());
        c.out.add(b);
        submit(() -> enableWrite(c));
    }

    public Collection<Connection> textSessions() {
        List<Connection> l = new ArrayList<>();
        for (Connection c : conns) if (c.mode == Connection.Mode.TEXT && !c.closed) l.add(c);
        return l;
    }

    /** Blocks the calling (non-selector) thread while too much data is queued: backpressure for file sends. */
    public void awaitDrain(Connection c, long maxQueued) throws IOException {
        while (c.pendingBytes.get() > maxQueued) {
            if (c.closed) throw new IOException("connection closed");
            if (Thread.currentThread().isInterrupted()) throw new IOException("interrupted");
            LockSupport.parkNanos(2_000_000);
        }
    }

    public void close(Connection c) { submit(() -> closeQuietly(c)); }

    // ------------------------------------------------------------------ selector loop

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(1000);
                Runnable r;
                while ((r = pending.poll()) != null) {
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        Log.error("hub task failed", e);
                    }
                }
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) continue;
                    try {
                        if (k.isAcceptable()) accept((ServerSocketChannel) k.channel());
                        else {
                            Connection c = (Connection) k.attachment();
                            if (k.isConnectable()) finishConnect(c);
                            if (k.isValid() && k.isReadable()) read(c);
                            if (k.isValid() && k.isWritable()) write(c);
                        }
                    } catch (IOException | RuntimeException e) {
                        Object a = k.attachment();
                        if (a instanceof Connection c) {
                            Log.debug("connection " + c + " error: " + e);
                            closeQuietly(c);
                        } else Log.error("selector error", e);
                    }
                }
                housekeeping();
            } catch (IOException e) {
                Log.error("selector loop error", e);
            } catch (java.nio.channels.ClosedSelectorException e) {
                return;
            }
        }
    }

    private void accept(ServerSocketChannel ssc) throws IOException {
        SocketChannel ch = ssc.accept();
        if (ch == null) return;
        if (conns.size() >= MAX_CONNECTIONS) {
            ch.close();
            Log.warn("connection limit reached, refused one");
            return;
        }
        ch.configureBlocking(false);
        ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
        InetSocketAddress ra = (InetSocketAddress) ch.getRemoteAddress();
        String ip = ra.getAddress().getHostAddress();
        Connection c = new Connection(ch, false, ip, ra.getPort());
        c.connected = true;
        conns.add(c);
        c.key = ch.register(selector, SelectionKey.OP_READ, c);
        // Policy check may shell out to the tailscale CLI: never do that on the selector thread.
        ThreadPools.get().io().execute(() -> {
            AccessPolicy.Decision d = policy.check(ip);
            submit(() -> {
                if (c.closed) return;
                if (!d.allowed()) {
                    Log.warn("rejected connection from " + ip + ": " + d.reason());
                    closeQuietly(c);
                    return;
                }
                c.authorized = true;
                c.who = d.who();
                if (c.key.isValid()) c.key.interestOps(c.key.interestOps() | SelectionKey.OP_READ);
                try {
                    process(c);
                } catch (IOException e) {
                    closeQuietly(c);
                }
            });
        });
    }

    private void finishConnect(Connection c) throws IOException {
        if (c.channel.finishConnect()) {
            c.key.interestOps(SelectionKey.OP_READ);
            onConnected(c);
        }
    }

    private void onConnected(Connection c) {
        c.connected = true;
        c.lastRead = System.currentTimeMillis();
        String k = c.remoteIp + ":" + c.remotePort;
        dialing.remove(k);
        failures.remove(k);
        retryAt.remove(k);
        queueRaw(c, ByteBuffer.wrap(Frame.MAGIC));
        send(c, hello());
    }

    private Frame hello() {
        Map<String, Object> h = Json.o("type", "hello", "nodeId", nodeId, "version", VERSION);
        h.putAll(helloInfo.get());
        return Frame.of(h);
    }

    private void queueRaw(Connection c, ByteBuffer b) {
        c.pendingBytes.addAndGet(b.remaining());
        c.out.add(b);
        enableWrite(c);
    }

    private void enableWrite(Connection c) {
        if (c.closed || c.key == null || !c.key.isValid() || !c.connected) return;
        c.key.interestOps(c.key.interestOps() | SelectionKey.OP_WRITE);
    }

    private void read(Connection c) throws IOException {
        if (!c.in.hasRemaining()) {
            // buffer full: only possible while waiting for the access decision. Stop reading until then.
            if (!c.authorized) {
                c.key.interestOps(c.key.interestOps() & ~SelectionKey.OP_READ);
                return;
            }
            throw new IOException("input buffer overflow");
        }
        int n = c.channel.read(c.in);
        if (n < 0) {
            closeQuietly(c);
            return;
        }
        if (n > 0) c.lastRead = System.currentTimeMillis();
        if (c.authorized) process(c);
    }

    private void write(Connection c) throws IOException {
        ByteBuffer b;
        while ((b = c.out.peek()) != null) {
            int before = b.remaining();
            c.channel.write(b);
            c.pendingBytes.addAndGet(-(before - b.remaining()));
            if (b.hasRemaining()) return; // socket buffer full, wait for next OP_WRITE
            c.out.poll();
        }
        c.key.interestOps(c.key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    /** Interpret buffered input according to the connection mode. Selector thread only. */
    private void process(Connection c) throws IOException {
        c.in.flip();
        try {
            if (c.mode == Connection.Mode.DETECT) detect(c);
            if (c.mode == Connection.Mode.BINARY) frames(c);
            else if (c.mode == Connection.Mode.TEXT) lines(c);
        } finally {
            if (!c.closed) c.in.compact();
        }
    }

    private void detect(Connection c) {
        ByteBuffer in = c.in;
        int n = Math.min(in.remaining(), Frame.MAGIC.length);
        boolean prefix = true;
        for (int i = 0; i < n; i++) if (in.get(in.position() + i) != Frame.MAGIC[i]) prefix = false;
        if (prefix && n == Frame.MAGIC.length) {
            in.position(in.position() + n);
            c.mode = Connection.Mode.BINARY;
            if (!c.outbound) { // the dialer already sent its magic + hello in onConnected
                queueRaw(c, ByteBuffer.wrap(Frame.MAGIC));
                send(c, hello());
            }
            return;
        }
        if (c.outbound) {
            if (!prefix) closeQuietly(c); // whatever we dialed is not an ActiveDesk node
            return;
        }
        boolean newline = false;
        for (int i = in.position(); i < in.limit(); i++) if (in.get(i) == '\n') newline = true;
        if (!prefix || newline) {
            c.mode = Connection.Mode.TEXT;
            Log.info("text session opened by " + c.who + " (" + c.remoteIp + ")");
            safe(() -> listener.onTextOpen(c));
        }
    }

    private void frames(Connection c) throws IOException {
        Frame f;
        while (!c.closed && (f = Frame.decode(c.in)) != null) {
            String type = f.type();
            if ("hello".equals(type)) {
                onHello(c, f);
                continue;
            }
            if (!c.helloDone) throw new IOException("frame before hello");
            switch (type) {
                case "ping" -> send(c, Frame.of(Json.o("type", "pong")));
                case "pong" -> { }
                default -> {
                    Frame ff = f;
                    safe(() -> listener.onFrame(c, ff));
                }
            }
        }
    }

    private void onHello(Connection c, Frame f) throws IOException {
        String id = f.str("nodeId");
        if (id == null || !id.matches("[A-Za-z0-9-]{8,64}")) throw new IOException("bad node id");
        if (id.equals(nodeId)) {
            // dialed ourselves (e.g. our own Tailscale IP): drop and never retry
            retryAt.put(c.remoteIp + ":" + c.remotePort, Long.MAX_VALUE);
            closeQuietly(c);
            return;
        }
        if (c.helloDone) throw new IOException("duplicate hello");
        c.peerNodeId = id;
        c.peerName = sanitizeName(f.str("name"));
        c.peerOs = sanitizeName(f.str("os"));
        c.peerScreen = Json.bool(f.header(), "screen", false);
        c.peerWebPort = (int) Json.num(f.header(), "webPort", 0);
        c.peerHello = f.header();

        Connection existing = peers.get(id);
        if (existing != null && existing != c && !existing.closed) {
            // Both sides may dial each other at once. Both apply this same rule, so both keep the same socket.
            String a = initiator(existing);
            String b = initiator(c);
            if (a.compareTo(b) <= 0) {
                c.replaced = true;
                closeQuietly(c);
                return;
            }
            existing.replaced = true;
            peers.put(id, c);
            c.helloDone = true;
            closeQuietly(existing);
            Log.debug("replaced duplicate connection to " + c.peerName);
            return;
        }
        peers.put(id, c);
        c.helloDone = true;
        Log.info("peer up: " + c.peerName + " [" + c.peerOs + "] at " + c.remoteIp + " (" + c.who + ")");
        safe(() -> listener.onPeerUp(c));
    }

    private String initiator(Connection c) { return c.outbound ? nodeId : c.peerNodeId; }

    static String sanitizeName(String s) {
        if (s == null) return "?";
        String t = s.replaceAll("[^\\p{L}\\p{N} ._()'-]", "").trim();
        if (t.length() > 48) t = t.substring(0, 48);
        return t.isEmpty() ? "?" : t;
    }

    private void lines(Connection c) throws IOException {
        ByteBuffer in = c.in;
        while (true) {
            int nl = -1;
            for (int i = in.position(); i < in.limit(); i++) if (in.get(i) == '\n') { nl = i; break; }
            if (nl < 0) {
                if (in.remaining() > MAX_TEXT_LINE) throw new IOException("text line too long");
                return;
            }
            byte[] line = new byte[nl - in.position()];
            in.get(line);
            in.get(); // newline
            String s = new String(line, StandardCharsets.UTF_8);
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            String fs = s;
            safe(() -> listener.onTextLine(c, fs));
            if (c.closed) return;
        }
    }

    private void housekeeping() {
        long now = System.currentTimeMillis();
        if (now - lastHousekeeping < 5000) return;
        lastHousekeeping = now;
        for (Connection c : conns) {
            if (c.closed) continue;
            if (!c.connected && now - c.openedAt > CONNECT_TIMEOUT_MS) {
                closeQuietly(c); // backs off
            } else if (c.outbound && !c.helloDone && now - c.openedAt > HELLO_TIMEOUT_MS) {
                backoff(c.remoteIp + ":" + c.remotePort); // reachable but silent: not ActiveDesk
                closeQuietly(c);
            } else if (c.mode == Connection.Mode.BINARY && !c.helloDone && now - c.openedAt > HELLO_TIMEOUT_MS) {
                closeQuietly(c);
            } else if (c.mode == Connection.Mode.DETECT && now - c.openedAt > 10 * 60_000) {
                closeQuietly(c); // idle unidentified socket
            } else if (c.mode == Connection.Mode.BINARY && c.helloDone) {
                if (now - c.lastRead > IDLE_TIMEOUT_MS) {
                    Log.info("peer " + c.peerName + " timed out");
                    closeQuietly(c);
                } else if (now - c.lastRead > PING_EVERY_MS) {
                    send(c, Frame.of(Json.o("type", "ping")));
                }
            }
        }
    }

    private void closeQuietly(Connection c) {
        if (c.closed) return;
        c.closed = true;
        conns.remove(c);
        if (c.outbound && !c.connected) {
            String k = c.remoteIp + ":" + c.remotePort;
            dialing.remove(k);
            backoff(k);
        }
        if (c.key != null) c.key.cancel();
        try {
            c.channel.close();
        } catch (IOException ignored) {
        }
        if (c.peerNodeId != null && peers.remove(c.peerNodeId, c)) {
            Log.info("peer down: " + c.peerName);
            safe(() -> listener.onPeerDown(c));
        }
        if (c.mode == Connection.Mode.TEXT) safe(() -> listener.onTextClosed(c));
    }

    private void safe(Runnable r) {
        if (listener == null) return;
        try {
            r.run();
        } catch (RuntimeException e) {
            Log.error("listener error", e);
        }
    }
}
