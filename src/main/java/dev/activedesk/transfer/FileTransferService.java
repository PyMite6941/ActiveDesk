package dev.activedesk.transfer;

import dev.activedesk.net.Connection;
import dev.activedesk.net.Frame;
import dev.activedesk.net.Hub;
import dev.activedesk.sched.ThreadPools;
import dev.activedesk.util.Crypto;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Direct device-to-device file transfer over the peer socket.
 *
 *   sender                          receiver
 *   file_offer {id,name,size,sha256} ->
 *                                <- file_accept {id}   | file_reject {id,reason}
 *   file_chunk {id,seq} + bytes  -> (repeated, 64 KiB each, sender blocks while >1 MiB is queued)
 *   file_end {id}                ->
 *                                <- file_done {id,ok,error}
 *
 * The receiver checks size and SHA-256 before the file appears under its final name; partial data
 * lives in transfer.dir/.partial and is deleted on any failure. Names from the network are reduced to
 * a safe basename and never overwrite an existing file.
 */
public final class FileTransferService {
    private static final int CHUNK = 64 * 1024;
    private static final long MAX_QUEUED = 1024 * 1024;
    private static final int MAX_INCOMING = 8;

    public static final class Transfer {
        public final String id;
        public final boolean outgoing;
        public final String peer;
        public final String name;
        public final long size;
        public final long startedAt = System.currentTimeMillis();
        volatile long done;
        volatile String status = "offered";
        volatile String detail = "";

        Transfer(String id, boolean outgoing, String peer, String name, long size) {
            this.id = id;
            this.outgoing = outgoing;
            this.peer = peer;
            this.name = name;
            this.size = size;
        }

        public String status() { return status; }
        public String detail() { return detail; }

        public Map<String, Object> toJson() {
            return Json.o("id", id, "direction", outgoing ? "out" : "in", "peer", peer, "name", name, "size", size,
                    "done", done, "status", status, "detail", detail, "startedAt", startedAt);
        }
    }

    private static final class Incoming {
        final Transfer t;
        final Connection conn;
        final String sha256;
        final Path part;
        OutputStream os;
        final MessageDigest md = Crypto.sha256();
        long nextSeq;
        volatile long lastActivity = System.currentTimeMillis();

        Incoming(Transfer t, Connection conn, String sha256, Path part) {
            this.t = t;
            this.conn = conn;
            this.sha256 = sha256;
            this.part = part;
        }
    }

    private static final class Outgoing {
        final Transfer t;
        final Connection conn;
        final CompletableFuture<Boolean> accepted = new CompletableFuture<>();
        final CompletableFuture<Transfer> result = new CompletableFuture<>();

        Outgoing(Transfer t, Connection conn) {
            this.t = t;
            this.conn = conn;
        }
    }

    private final Hub hub;
    private final Path dir;
    private final Path partialDir;
    private final boolean autoAccept;
    private final long maxBytes;
    private final Map<String, Incoming> incoming = new ConcurrentHashMap<>();
    private final Map<String, Outgoing> outgoing = new ConcurrentHashMap<>();
    private final Deque<Transfer> history = new ArrayDeque<>();
    /** Single disk thread keeps chunk order and keeps file I/O off the selector thread. */
    private final ExecutorService disk = Executors.newSingleThreadExecutor(ThreadPools.named("ad-disk"));
    private volatile BiConsumer<String, String> notifier = (room, text) -> { };

    public FileTransferService(Hub hub, Path dir, boolean autoAccept, long maxBytes) throws IOException {
        this.hub = hub;
        this.dir = dir;
        this.partialDir = dir.resolve(".partial");
        this.autoAccept = autoAccept;
        this.maxBytes = maxBytes;
        Files.createDirectories(partialDir);
        ThreadPools.get().ticker().scheduleAtFixedRate(this::reapStale, 15, 15, TimeUnit.SECONDS);
    }

    /** Called with (room, text) to announce finished transfers in chat. */
    public void setNotifier(BiConsumer<String, String> n) { this.notifier = n; }

    public Path directory() { return dir; }

    // ---------------------------------------------------------------- sending

    public CompletableFuture<Transfer> send(String peerNodeId, Path file) {
        CompletableFuture<Transfer> res = new CompletableFuture<>();
        ThreadPools.get().io().execute(() -> {
            try {
                res.complete(sendBlocking(peerNodeId, file, file.getFileName().toString()));
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
        });
        return res;
    }

    public Transfer sendBlocking(String peerNodeId, Path file, String name) throws Exception {
        Connection c = hub.peer(peerNodeId);
        if (c == null) throw new IOException("device is not connected");
        if (!Files.isRegularFile(file)) throw new IOException("not a file: " + file);
        long size = Files.size(file);
        String sha = sha256(file);
        Transfer t = new Transfer(UUID.randomUUID().toString(), true, c.peerName(), name, size);
        Outgoing o = new Outgoing(t, c);
        outgoing.put(t.id, o);
        remember(t);
        try {
            hub.send(c, Frame.of(Json.o("type", "file_offer", "id", t.id, "name", name, "size", size, "sha256", sha)));
            if (!o.accepted.get(120, TimeUnit.SECONDS)) {
                throw new IOException("rejected: " + t.detail);
            }
            t.status = "sending";
            byte[] buf = new byte[CHUNK];
            long seq = 0;
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.readNBytes(buf, 0, CHUNK)) > 0) {
                    hub.awaitDrain(c, MAX_QUEUED);
                    byte[] payload = n == CHUNK ? buf.clone() : java.util.Arrays.copyOf(buf, n);
                    hub.send(c, new Frame(Json.o("type", "file_chunk", "id", t.id, "seq", seq++), payload));
                    t.done += n;
                    if (o.result.isDone()) break; // receiver aborted
                }
            }
            hub.send(c, Frame.of(Json.o("type", "file_end", "id", t.id)));
            Transfer done = o.result.get(120, TimeUnit.SECONDS);
            Log.info("sent " + name + " to " + c.peerName() + ": " + done.status);
            if (!"done".equals(done.status)) throw new IOException(done.detail);
            return done;
        } catch (Exception e) {
            if (!"failed".equals(t.status)) {
                t.status = "failed";
                t.detail = e.getMessage() == null ? e.toString() : e.getMessage();
                hub.send(c, Frame.of(Json.o("type", "file_cancel", "id", t.id)));
            }
            throw e;
        } finally {
            outgoing.remove(t.id);
        }
    }

    private static String sha256(Path f) throws IOException {
        MessageDigest md = Crypto.sha256();
        byte[] b = new byte[CHUNK];
        try (InputStream in = Files.newInputStream(f)) {
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return Crypto.hex(md.digest());
    }

    // ---------------------------------------------------------------- protocol

    /** Called on the selector thread. Keep it quick: disk work goes to the disk executor. */
    public void onFrame(Connection c, Frame f) {
        String id = f.str("id");
        if (id == null || !id.matches("[a-f0-9-]{8,64}")) return;
        switch (f.type()) {
            case "file_offer" -> onOffer(c, f, id);
            case "file_chunk" -> {
                Incoming in = incoming.get(id);
                if (in == null || in.conn != c) return;
                long seq = Json.num(f.header(), "seq", -1);
                byte[] data = f.payload();
                disk.execute(() -> writeChunk(in, seq, data));
            }
            case "file_end" -> {
                Incoming in = incoming.get(id);
                if (in != null && in.conn == c) disk.execute(() -> finishIncoming(in));
            }
            case "file_cancel" -> {
                Incoming in = incoming.get(id);
                if (in != null && in.conn == c) disk.execute(() -> fail(in, "cancelled by sender"));
                Outgoing o = outgoing.get(id);
                if (o != null && o.conn == c) {
                    o.t.status = "failed";
                    o.t.detail = "cancelled by receiver";
                    o.accepted.complete(false);
                    o.result.complete(o.t);
                }
            }
            case "file_accept", "file_reject", "file_done" -> {
                Outgoing o = outgoing.get(id);
                if (o == null || o.conn != c) return; // only the device we offered to may answer
                if ("file_accept".equals(f.type())) o.accepted.complete(true);
                else if ("file_reject".equals(f.type())) {
                    o.t.status = "failed";
                    o.t.detail = Json.str(f.header(), "reason", "rejected");
                    o.accepted.complete(false);
                    o.result.complete(o.t);
                } else {
                    boolean ok = Json.bool(f.header(), "ok", false);
                    o.t.status = ok ? "done" : "failed";
                    o.t.detail = Json.str(f.header(), "error", ok ? "verified" : "failed");
                    o.result.complete(o.t);
                }
            }
            default -> { }
        }
    }

    private void onOffer(Connection c, Frame f, String id) {
        String name = safeName(f.str("name"));
        long size = Json.num(f.header(), "size", -1);
        String sha = f.str("sha256");
        String reason = null;
        if (!autoAccept) reason = "receiver has auto-accept turned off";
        else if (size < 0 || size > maxBytes) reason = "file too large (limit " + maxBytes + " bytes)";
        else if (sha == null || !sha.matches("[a-f0-9]{64}")) reason = "missing checksum";
        else if (incoming.size() >= MAX_INCOMING) reason = "too many transfers in progress";
        else if (incoming.containsKey(id)) reason = "duplicate transfer id";
        else if (dir.toFile().getUsableSpace() < size + 64L * 1024 * 1024) reason = "not enough disk space";
        if (reason != null) {
            hub.send(c, Frame.of(Json.o("type", "file_reject", "id", id, "reason", reason)));
            Log.warn("rejected file " + name + " from " + c.peerName() + ": " + reason);
            return;
        }
        Transfer t = new Transfer(id, false, c.peerName(), name, size);
        t.status = "receiving";
        Incoming in = new Incoming(t, c, sha, partialDir.resolve(id + ".part"));
        incoming.put(id, in);
        remember(t);
        disk.execute(() -> {
            try {
                in.os = Files.newOutputStream(in.part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                hub.send(c, Frame.of(Json.o("type", "file_accept", "id", id)));
                Log.info("receiving " + name + " (" + size + " bytes) from " + c.peerName());
            } catch (IOException e) {
                fail(in, "cannot create file: " + e.getMessage());
            }
        });
    }

    private void writeChunk(Incoming in, long seq, byte[] data) {
        if (in.os == null || !incoming.containsKey(in.t.id)) return;
        try {
            if (seq != in.nextSeq) throw new IOException("chunk out of order");
            if (in.t.done + data.length > in.t.size) throw new IOException("more data than offered");
            in.os.write(data);
            in.md.update(data);
            in.t.done += data.length;
            in.nextSeq++;
            in.lastActivity = System.currentTimeMillis();
        } catch (IOException e) {
            fail(in, e.getMessage());
        }
    }

    private void finishIncoming(Incoming in) {
        if (!incoming.containsKey(in.t.id)) return;
        try {
            in.os.close();
            if (in.t.done != in.t.size) throw new IOException("size mismatch");
            String got = Crypto.hex(in.md.digest());
            if (!got.equals(in.sha256)) throw new IOException("checksum mismatch");
            Path target = uniqueTarget(in.t.name);
            Files.move(in.part, target, StandardCopyOption.ATOMIC_MOVE);
            incoming.remove(in.t.id);
            in.t.status = "done";
            in.t.detail = target.getFileName().toString();
            hub.send(in.conn, Frame.of(Json.o("type", "file_done", "id", in.t.id, "ok", true)));
            Log.info("received " + target + " from " + in.conn.peerName() + " (sha256 verified)");
            notifier.accept("files", "Received " + target.getFileName() + " (" + in.t.size + " bytes) from " + in.conn.peerName());
        } catch (IOException e) {
            fail(in, e.getMessage());
        }
    }

    private void fail(Incoming in, String why) {
        if (incoming.remove(in.t.id) == null) return;
        in.t.status = "failed";
        in.t.detail = why;
        try {
            if (in.os != null) in.os.close();
            Files.deleteIfExists(in.part);
        } catch (IOException ignored) {
        }
        hub.send(in.conn, Frame.of(Json.o("type", "file_done", "id", in.t.id, "ok", false, "error", why)));
        Log.warn("transfer " + in.t.name + " failed: " + why);
    }

    private void reapStale() {
        long now = System.currentTimeMillis();
        for (Incoming in : List.copyOf(incoming.values())) {
            if (in.conn.isClosed() || now - in.lastActivity > 120_000) disk.execute(() -> fail(in, "stalled"));
        }
    }

    /** Receives a file uploaded through the web UI straight into this device's transfer folder. */
    public Path storeLocal(String rawName, InputStream body, long limit) throws IOException {
        String name = safeName(rawName);
        Path part = partialDir.resolve(UUID.randomUUID() + ".part");
        try (OutputStream os = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
            byte[] b = new byte[CHUNK];
            long total = 0;
            int n;
            while ((n = body.read(b)) > 0) {
                total += n;
                if (total > limit) throw new IOException("upload too large");
                os.write(b, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Path target = uniqueTarget(name);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private synchronized Path uniqueTarget(String name) throws IOException {
        Files.createDirectories(dir);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        Path p = dir.resolve(name);
        for (int i = 1; Files.exists(p); i++) p = dir.resolve(base + " (" + i + ")" + ext);
        // final containment check (defence in depth; safeName already removed separators)
        if (!p.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize())) throw new IOException("bad path");
        return p;
    }

    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Reduce any network-supplied name to a harmless basename valid on Windows, macOS and Linux. */
    public static String safeName(String raw) {
        String n = raw == null ? "" : raw;
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\p{Cc}<>:\"|?*]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        if (n.isEmpty()) n = "file";
        String stem = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
        if (RESERVED.contains(stem.toLowerCase(Locale.ROOT))) n = "_" + n;
        return n;
    }

    // ---------------------------------------------------------------- listing

    private void remember(Transfer t) {
        synchronized (history) {
            history.addFirst(t);
            while (history.size() > 50) history.removeLast();
        }
    }

    public List<Transfer> recent() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public List<Map<String, Object>> receivedFiles() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile).sorted((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            }).limit(200).forEach(p -> {
                try {
                    out.add(Json.o("name", p.getFileName().toString(), "size", Files.size(p),
                            "modified", Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                }
            });
        }
        return out;
    }

    /** Resolve a name from the web UI to a file inside the transfer folder, or null. */
    public Path resolveReceived(String name) {
        if (name == null || !name.equals(safeName(name))) return null;
        Path p = dir.resolve(name).toAbsolutePath().normalize();
        if (!p.startsWith(dir.toAbsolutePath().normalize()) || !Files.isRegularFile(p)) return null;
        return p;
    }

    public void close() { disk.shutdownNow(); }
}
