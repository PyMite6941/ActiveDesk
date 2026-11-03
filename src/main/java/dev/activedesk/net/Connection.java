package dev.activedesk.net;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** State for one TCP connection owned by the {@link Hub} selector thread. */
public final class Connection {
    public enum Mode { DETECT, BINARY, TEXT }

    private static final AtomicLong IDS = new AtomicLong();

    final long id = IDS.incrementAndGet();
    final SocketChannel channel;
    final boolean outbound;
    final String remoteIp;
    final int remotePort;
    final long openedAt = System.currentTimeMillis();
    final ByteBuffer in = ByteBuffer.allocate(Frame.MAX_FRAME + 1024);
    final ConcurrentLinkedQueue<ByteBuffer> out = new ConcurrentLinkedQueue<>();
    final AtomicLong pendingBytes = new AtomicLong();
    SelectionKey key;
    volatile Mode mode;
    volatile boolean authorized;
    volatile boolean connected;
    volatile boolean closed;
    volatile boolean helloDone;
    volatile boolean replaced;
    volatile long lastRead = System.currentTimeMillis();
    volatile String who = "?";

    // filled in from the peer's hello
    volatile String peerNodeId;
    volatile String peerName;
    volatile String peerOs = "";
    volatile boolean peerScreen;
    volatile int peerWebPort;
    volatile Map<String, Object> peerHello = Map.of();

    /** Free-form per-connection state for higher layers (e.g. the text session's current room). */
    public final Map<String, Object> attrs = new ConcurrentHashMap<>();

    Connection(SocketChannel channel, boolean outbound, String remoteIp, int remotePort) {
        this.channel = channel;
        this.outbound = outbound;
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.mode = Mode.DETECT; // both directions start by reading the peer's magic
    }

    public long id() { return id; }
    public String remoteIp() { return remoteIp; }
    public boolean isLoopback() { return dev.activedesk.tailscale.Tailscale.isLoopback(remoteIp); }
    public String who() { return who; }
    public String peerNodeId() { return peerNodeId; }
    public String peerName() { return peerName; }
    public String peerOs() { return peerOs; }
    public boolean peerScreen() { return peerScreen; }
    public int peerWebPort() { return peerWebPort; }
    public Map<String, Object> peerHello() { return peerHello; }
    public long pendingBytes() { return pendingBytes.get(); }
    public boolean isClosed() { return closed; }
    public Mode mode() { return mode; }

    @Override
    public String toString() {
        return (peerName != null ? peerName : who) + "@" + remoteIp + (outbound ? " (out)" : " (in)");
    }
}
