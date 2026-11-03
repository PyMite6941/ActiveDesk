package dev.activedesk.chat;

import dev.activedesk.util.Json;
import java.util.Map;

/**
 * A chat line. {@code seq} is local to this node (used by web clients to poll "since"); {@code id} is
 * global and used for de-duplication across the mesh. {@code via} is set when the message reached us
 * through history sync from a device other than its author.
 */
public record ChatMessage(long seq, String id, String room, String fromNode, String fromName,
                          String text, long ts, boolean system, String via) {

    public ChatMessage withSeq(long s) { return new ChatMessage(s, id, room, fromNode, fromName, text, ts, system, via); }

    public ChatMessage withVia(String v) { return new ChatMessage(seq, id, room, fromNode, fromName, text, ts, system, v); }

    public Map<String, Object> toJson() {
        return Json.o("seq", seq, "id", id, "room", room, "fromNode", fromNode, "from", fromName,
                "text", text, "ts", ts, "system", system, "via", via == null ? "" : via);
    }

    public static ChatMessage fromJson(Map<String, Object> m) {
        String via = Json.str(m, "via", "");
        return new ChatMessage(Json.num(m, "seq", 0), Json.str(m, "id", ""), Json.str(m, "room", "general"),
                Json.str(m, "fromNode", ""), Json.str(m, "from", "?"), Json.str(m, "text", ""),
                Json.num(m, "ts", 0), Json.bool(m, "system", false), via.isEmpty() ? null : via);
    }
}
