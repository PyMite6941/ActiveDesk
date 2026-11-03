package dev.activedesk.net;

import dev.activedesk.util.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Wire format between ActiveDesk nodes, after the 5-byte magic "ADSK\1":
 *
 *   int32 length        (bytes that follow: 4 + headerLength + payloadLength)
 *   int32 headerLength
 *   byte[headerLength]  UTF-8 JSON object, always has "type"
 *   byte[...]           raw payload (file chunks); empty for control messages
 *
 * Length-prefixing lets the NIO loop reassemble frames from arbitrary TCP segments, and keeps
 * binary file data out of JSON (no base64 bloat).
 */
public record Frame(Map<String, Object> header, byte[] payload) {
    public static final byte[] MAGIC = {'A', 'D', 'S', 'K', 1};
    public static final int MAX_HEADER = 64 * 1024;
    public static final int MAX_PAYLOAD = 256 * 1024;
    public static final int MAX_FRAME = 8 + MAX_HEADER + MAX_PAYLOAD;

    public static Frame of(Map<String, Object> header) { return new Frame(header, new byte[0]); }

    public String type() { return Json.str(header, "type", ""); }

    public String str(String k) { return Json.str(header, k, null); }

    public ByteBuffer encode() {
        byte[] h = Json.write(header).getBytes(StandardCharsets.UTF_8);
        if (h.length > MAX_HEADER) throw new IllegalArgumentException("frame header too large");
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("frame payload too large");
        ByteBuffer b = ByteBuffer.allocate(8 + h.length + payload.length);
        b.putInt(4 + h.length + payload.length).putInt(h.length).put(h).put(payload);
        b.flip();
        return b;
    }

    /**
     * Decode one frame from a buffer in read mode. Returns null (position unchanged) if the frame is
     * incomplete. Throws on anything malformed so the caller drops the connection.
     */
    public static Frame decode(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 4) return null;
        int start = buf.position();
        int len = buf.getInt(start);
        if (len < 4 + 2 || len > 4 + MAX_HEADER + MAX_PAYLOAD) throw new IOException("bad frame length " + len);
        if (buf.remaining() < 4 + len) return null;
        buf.position(start + 4);
        int hl = buf.getInt();
        if (hl < 2 || hl > MAX_HEADER || hl > len - 4) throw new IOException("bad header length " + hl);
        byte[] h = new byte[hl];
        buf.get(h);
        byte[] p = new byte[len - 4 - hl];
        buf.get(p);
        Map<String, Object> header;
        try {
            header = Json.obj(new String(h, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("bad frame header: " + e.getMessage());
        }
        return new Frame(header, p);
    }
}
