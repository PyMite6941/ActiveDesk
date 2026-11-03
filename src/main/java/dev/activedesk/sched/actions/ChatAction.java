package dev.activedesk.sched.actions;

import dev.activedesk.sched.TaskAction;

/** Posts a message into a chat room on every connected device. params: room (general), text. */
public final class ChatAction implements TaskAction {
    @Override
    public String run(Context ctx) {
        String text = ctx.task().param("text");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("chat task needs params.text");
        if (ctx.chat() == null) throw new IllegalStateException("chat is not available on this node");
        String room = ctx.task().param("room", "general");
        ctx.chat().post(room, text);
        return "posted to #" + room;
    }
}
