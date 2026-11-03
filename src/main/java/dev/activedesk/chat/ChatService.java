package dev.activedesk.chat;

import dev.activedesk.net.Connection;
import dev.activedesk.net.Frame;
import dev.activedesk.net.Hub;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Serverless group chat. Every node sends its own messages straight to every connected peer (the tailnet
 * is a full mesh), so no relay or server is needed. When a peer connects we push recent history so a
 * device that was asleep catches up; message ids make that idempotent.
 */
public final class ChatService {
    public static final int MAX_TEXT = 4000;
    private static final int PER_ROOM = 300;
    private static final int SYNC_PER_ROOM = 50;

    private final Hub hub;
    private final String nodeId;
    private final String deviceName;
    private final Path logFile;
    private final Map<String, Deque<ChatMessage>> rooms = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seen = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 20_000; }
    });
    private final AtomicLong seq = new AtomicLong();
    private final List<Consumer<ChatMessage>> listeners = new CopyOnWriteArrayList<>();

    public ChatService(Hub hub, String nodeId, String deviceName, Path logFile) {
        this.hub = hub;
        this.nodeId = nodeId;
        this.deviceName = deviceName;
        this.logFile = logFile;
        rooms.put("general", new ArrayDeque<>());
        load();
    }

    public void addListener(Consumer<ChatMessage> l) { listeners.add(l); }
    public void removeListener(Consumer<ChatMessage> l) { listeners.remove(l); }

    /** Room names: lower-case letters, digits, '-' and '_', up to 32 chars. Returns null if invalid. */
    public static String validRoom(String room) {
        if (room == null) return null;
        String r = room.trim().toLowerCase().replaceFirst("^#", "");
        return r.matches("[a-z0-9_-]{1,32}") ? r : null;
    }

    /** A message typed by a local user (CLI, nc session on this box, web UI on this box). */
    public ChatMessage post(String room, String text) { return post(room, text, deviceName); }

    public ChatMessage post(String room, String text, String author) {
        String r = validRoom(room);
        if (r == null) throw new IllegalArgumentException("bad room name");
        String t = cleanText(text);
        if (t.isEmpty()) throw new IllegalArgumentException("empty message");
        ChatMessage m = new ChatMessage(0, UUID.randomUUID().toString(), r, nodeId, author, t,
                System.currentTimeMillis(), false, null);
        accept(m, true);
        hub.broadcast(Frame.of(frame("chat", m)));
        return m;
    }

    /** Local-only notice (file received, task finished...). Not sent to peers. */
    public void system(String room, String text) {
        String r = validRoom(room);
        if (r == null) r = "general";
        accept(new ChatMessage(0, UUID.randomUUID().toString(), r, nodeId, "ActiveDesk", cleanText(text),
                System.currentTimeMillis(), true, null), false);
    }

    static String cleanText(String text) {
        if (text == null) return "";
        // strip control chars except tab/newline; terminals (nc) must not receive escape sequences
        String t = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").trim();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    private static Map<String, Object> frame(String type, ChatMessage m) {
        Map<String, Object> h = Json.o("type", type, "id", m.id(), "room", m.room(), "fromNode", m.fromNode(),
                "from", m.fromName(), "text", m.text(), "ts", m.ts());
        return h;
    }

    public void onPeerUp(Connection c) {
        // push recent history (only messages that are not local system notices)
        for (Map.Entry<String, Deque<ChatMessage>> e : rooms.entrySet()) {
            List<ChatMessage> recent;
            synchronized (e.getValue()) {
                recent = new ArrayList<>(e.getValue());
            }
            int from = Math.max(0, recent.size() - SYNC_PER_ROOM);
            for (ChatMessage m : recent.subList(from, recent.size())) {
                if (!m.system()) hub.send(c, Frame.of(frame("chat_sync", m)));
            }
        }
    }

    public void onFrame(Connection c, Frame f) {
        boolean sync = "chat_sync".equals(f.type());
        String id = f.str("id");
        String room = validRoom(f.str("room"));
        String fromNode = f.str("fromNode");
        if (id == null || !id.matches("[A-Za-z0-9-]{8,64}") || room == null || fromNode == null) return;
        // Anti-spoofing: a live message must come from its author's own connection.
        if (!sync && !fromNode.equals(c.peerNodeId())) {
            Log.warn("dropped chat message claiming to be from " + fromNode + " sent by " + c);
            return;
        }
        String name = sync && !fromNode.equals(c.peerNodeId()) ? Json.str(f.header(), "from", "?") : c.peerName();
        ChatMessage m = new ChatMessage(0, id, room, fromNode, sanitizeName(name), cleanText(f.str("text")),
                Json.num(f.header(), "ts", System.currentTimeMillis()), false,
                sync && !fromNode.equals(c.peerNodeId()) ? c.peerName() : null);
        if (m.text().isEmpty()) return;
        accept(m, true);
    }

    private static String sanitizeName(String s) {
        String t = s == null ? "?" : s.replaceAll("[^\\p{L}\\p{N} ._()'-]", "").trim();
        return t.isEmpty() ? "?" : (t.length() > 48 ? t.substring(0, 48) : t);
    }

    private void accept(ChatMessage m, boolean persist) {
        if (seen.putIfAbsent(m.id(), Boolean.TRUE) != null) return;
        ChatMessage s = m.withSeq(seq.incrementAndGet());
        Deque<ChatMessage> q = rooms.computeIfAbsent(s.room(), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(s);
            while (q.size() > PER_ROOM) q.removeFirst();
        }
        if (persist) persist(s);
        for (Consumer<ChatMessage> l : listeners) {
            try {
                l.accept(s);
            } catch (RuntimeException e) {
                Log.error("chat listener failed", e);
            }
        }
    }

    private synchronized void persist(ChatMessage m) {
        try {
            Files.writeString(logFile, Json.write(m.toJson()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.error("chat log write failed", e);
        }
    }

    private void load() {
        try {
            if (!Files.exists(logFile)) return;
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 3000);
            for (String l : lines.subList(from, lines.size())) {
                try {
                    accept(ChatMessage.fromJson(Json.obj(l)), false);
                } catch (RuntimeException ignored) {
                }
            }
            if (lines.size() > 6000) { // keep the file bounded
                Files.write(logFile, lines.subList(lines.size() - 3000, lines.size()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.error("chat log read failed", e);
        }
    }

    public List<ChatMessage> since(String room, long afterSeq, int limit) {
        Deque<ChatMessage> q = rooms.get(room);
        List<ChatMessage> out = new ArrayList<>();
        if (q == null) return out;
        synchronized (q) {
            for (ChatMessage m : q) if (m.seq() > afterSeq) out.add(m);
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    public List<String> rooms() { return new ArrayList<>(new TreeSet<>(rooms.keySet())); }

    public long lastSeq() { return seq.get(); }
}
