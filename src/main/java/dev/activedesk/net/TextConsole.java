package dev.activedesk.net;

import dev.activedesk.Node;
import dev.activedesk.chat.ChatMessage;
import dev.activedesk.chat.ChatService;
import dev.activedesk.sched.TaskDefinition;
import dev.activedesk.transfer.FileTransferService;
import dev.activedesk.util.Crypto;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Line-based console for `nc <device> 47800` (any tailnet device, no Java needed) and for the local
 * `activedesk chat|send|lock|run-task` commands.
 *
 * Chat commands are open to anyone the AccessPolicy already let in. Commands that touch local files or
 * the node itself (/send, /run, /lock) additionally require `/auth <local.token>`, which only the CLI on
 * this machine can read from the owner-only config file - so another account on a shared machine can't
 * use the node to read your files.
 */
public final class TextConsole {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private final Node node;

    public TextConsole(Node node) { this.node = node; }

    public void open(Connection c) {
        c.attrs.put("room", "general");
        say(c, "ActiveDesk on " + node.config().deviceName() + " - you are " + c.who() + ". Type /help. Room: #general");
        history(c, 15);
    }

    public void line(Connection c, String raw) {
        String line = raw.trim();
        if (line.isEmpty()) return;
        if (!line.startsWith("/")) {
            String room = (String) c.attrs.get("room");
            try {
                String text = c.isLoopback() ? line : "(" + c.who() + ") " + line;
                ChatMessage m = node.chat().post(room, text);
                c.attrs.put("lastId", m.id());
            } catch (IllegalArgumentException e) {
                say(c, "! " + e.getMessage());
            }
            return;
        }
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();
        String a1 = parts.length > 1 ? parts[1] : "";
        String a2 = parts.length > 2 ? parts[2] : "";
        switch (cmd) {
            case "/help" -> say(c, String.join("\n",
                    "/join <room>     switch room (created on first message)",
                    "/rooms           list rooms",
                    "/who             connected ActiveDesk devices",
                    "/history [n]     last n messages in this room",
                    "/files           recent transfers",
                    "/tasks           scheduled tasks",
                    "/quit            leave",
                    "local only (after /auth): /send <device> <path>, /run <task>, /lock, /awake off|session|always, /battery [on|off|<min> <max>], /power [mode <id>|brightness <n>|saver off|saver <below> [dim]]"));
            case "/join" -> {
                String r = ChatService.validRoom(a1);
                if (r == null) { say(c, "! room names: a-z 0-9 - _ (max 32)"); return; }
                c.attrs.put("room", r);
                say(c, "joined #" + r);
                history(c, 15);
            }
            case "/rooms" -> say(c, "rooms: #" + String.join(" #", node.chat().rooms()));
            case "/who" -> {
                StringBuilder sb = new StringBuilder("this device: " + node.config().deviceName());
                for (Connection p : node.hub().peers())
                    sb.append("\n  ").append(p.peerName()).append("  ").append(p.remoteIp()).append("  ").append(p.peerOs())
                            .append(p.peerScreen() ? "  [screen]" : "");
                if (node.hub().peers().isEmpty()) sb.append("\n  (no other devices connected yet)");
                say(c, sb.toString());
            }
            case "/history" -> history(c, a1.isEmpty() ? 30 : Math.max(1, Math.min(200, parseInt(a1, 30))));
            case "/files" -> {
                List<FileTransferService.Transfer> l = node.transfers().recent();
                if (l.isEmpty()) say(c, "no transfers yet; files land in " + node.transfers().directory());
                for (FileTransferService.Transfer t : l)
                    say(c, (t.outgoing ? "-> " : "<- ") + t.peer + "  " + t.name + "  " + t.size + "B  " + t.status() + " " + t.detail());
            }
            case "/tasks" -> {
                for (TaskDefinition t : node.scheduler().tasks().values())
                    say(c, t.id() + "  [" + t.type() + "]  " + t.schedule() + (t.enabled() ? "" : "  (disabled)")
                            + (t.dependsOn().isEmpty() ? "" : "  after " + t.dependsOn()));
                node.scheduler().invalidTasks().forEach((k, v) -> say(c, k + "  INVALID: " + v));
            }
            case "/auth" -> {
                if (c.isLoopback() && Crypto.constantTimeEquals(a1, node.config().get("local.token"))) {
                    c.attrs.put("auth", Boolean.TRUE);
                    say(c, "OK authenticated");
                } else {
                    node.audit("console /auth FAILED from " + c.who() + " " + c.remoteIp());
                    say(c, "ERR auth failed");
                }
            }
            case "/send" -> {
                if (!privileged(c)) return;
                Connection target = findPeer(a1);
                if (target == null) { say(c, "ERR no connected device named '" + a1 + "' (see /who)"); return; }
                Path p = Path.of(a2.replaceAll("^\"|\"$", ""));
                node.audit("console send " + p + " to " + target.peerName());
                say(c, "sending " + p.getFileName() + " to " + target.peerName() + "...");
                node.transfers().send(target.peerNodeId(), p).whenComplete((t, e) -> {
                    if (e != null) say(c, "ERR send failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                    else say(c, "OK sent " + t.name + " (" + t.size + " bytes, sha256 verified)");
                });
            }
            case "/run" -> {
                if (!privileged(c)) return;
                say(c, node.scheduler().trigger(a1, "cli") ? "OK queued " + a1 : "ERR unknown task " + a1);
            }
            case "/lock" -> {
                if (!privileged(c)) return;
                say(c, "OK revoked " + node.web().revokeAll() + " web session(s)");
            }
            case "/awake" -> {
                if (!privileged(c)) return;
                if (a1 == null || !a1.matches("off|session|always")) {
                    say(c, "OK keep-awake is " + node.keepAwake().mode().name().toLowerCase(java.util.Locale.ROOT)
                            + (node.keepAwake().holding() ? " (holding the display on now via " + node.keepAwake().method() + ")" : ""));
                    return;
                }
                node.setKeepAwake(dev.activedesk.remote.KeepAwake.parse(a1), "local CLI");
                say(c, "OK keep-awake set to " + a1);
            }
            case "/battery" -> {
                if (!privileged(c)) return;
                var bs = node.battery();
                try {
                    if (a1.equals("on") || a1.equals("off")) node.setBattery(a1.equals("on"), bs.min(), bs.max(), "local CLI");
                    else if (!a1.isEmpty()) node.setBattery(true, Integer.parseInt(a1), Integer.parseInt(a2.trim()), "local CLI");
                } catch (NumberFormatException e) {
                    say(c, "ERR usage: /battery on | off | <min> <max>");
                    return;
                } catch (IllegalArgumentException | java.io.IOException e) {
                    say(c, "ERR " + e.getMessage());
                    return;
                }
                var st = bs.state();
                say(c, "OK battery range " + (bs.enabled() ? bs.min() + "-" + bs.max() + "%" : "off")
                        + " | " + (Boolean.TRUE.equals(st.get("supported")) ? st.get("backend") : "unsupported here")
                        + (((Number) st.get("percent")).intValue() >= 0 ? " | now " + st.get("percent") + "%" : "")
                        + " | " + st.get("message"));
            }
            case "/power" -> {
                if (!privileged(c)) return;
                var ps = node.power();
                try {
                    switch (a1) {
                        case "" -> { }
                        case "mode" -> node.setPowerProfile(a2.trim(), "local CLI");
                        case "brightness" -> node.setBrightness(Integer.parseInt(a2.trim()), "local CLI");
                        case "saver" -> {
                            String[] p = a2.trim().split("\\s+");
                            if (p[0].equals("off")) node.setPowerSaver(false, ps.saverBelow(), ps.saverProfileSetting(), ps.saverDim(), "local CLI");
                            else if (p[0].matches("[0-9]{1,2}")) node.setPowerSaver(true, Integer.parseInt(p[0]), ps.saverProfileSetting(),
                                    p.length > 1 ? Integer.parseInt(p[1]) : ps.saverDim(), "local CLI");
                            else throw new NumberFormatException();
                        }
                        default -> throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    say(c, "ERR usage: /power [mode <id> | brightness <1-100> | saver off | saver <below%> [dim%]]");
                    return;
                } catch (IllegalArgumentException | java.io.IOException e) {
                    say(c, "ERR " + e.getMessage());
                    return;
                }
                var st = ps.state();
                StringBuilder sb = new StringBuilder("OK power");
                sb.append(ps.hasBattery() ? " | battery " + ps.percent() + "%" + (ps.onBattery() ? " on battery" : " plugged in") : " | no battery");
                sb.append(" | mode ").append(st.get("profile").toString().isEmpty() ? "n/a" : st.get("profile"));
                List<?> profiles = (List<?>) st.get("profiles");
                if (!profiles.isEmpty()) {
                    sb.append(" (choices:");
                    for (Object o : profiles) sb.append(' ').append(((java.util.Map<?, ?>) o).get("id"));
                    sb.append(')');
                }
                sb.append(" | brightness ").append(((Number) st.get("brightness")).intValue() >= 0 ? st.get("brightness") + "%" : "n/a");
                sb.append(" | auto saver ").append(Boolean.TRUE.equals(st.get("saverEnabled")) ? "below " + st.get("saverBelow") + "%" : "off");
                if (Boolean.TRUE.equals(st.get("needsSetup"))) sb.append(" | run 'activedesk power install' once for full control");
                say(c, sb.toString());
            }
            case "/quit", "/exit" -> {
                say(c, "bye");
                node.hub().close(c);
            }
            default -> say(c, "! unknown command, try /help");
        }
    }

    private boolean privileged(Connection c) {
        if (c.isLoopback() && Boolean.TRUE.equals(c.attrs.get("auth"))) return true;
        say(c, "ERR this command is only available to the local CLI");
        return false;
    }

    private Connection findPeer(String name) {
        for (Connection p : node.hub().peers()) {
            if (p.peerName().equalsIgnoreCase(name) || p.peerNodeId().equals(name) || p.remoteIp().equals(name)) return p;
        }
        return null;
    }

    private void history(Connection c, int n) {
        String room = (String) c.attrs.get("room");
        for (ChatMessage m : node.chat().since(room, 0, n)) say(c, format(m));
    }

    /** Fan-out of new chat messages to every text session that is in that room. */
    public void onChat(ChatMessage m) {
        for (Connection c : node.hub().textSessions()) {
            if (!m.room().equals(c.attrs.get("room"))) continue;
            if (m.id().equals(c.attrs.get("lastId"))) continue; // don't echo the sender's own line
            say(c, format(m));
        }
    }

    private static String format(ChatMessage m) {
        String who = m.system() ? "*" : m.fromName() + (m.via() != null ? " (via " + m.via() + ")" : "");
        return "[" + HM.format(Instant.ofEpochMilli(m.ts())) + "] " + who + ": " + m.text();
    }

    private void say(Connection c, String text) { node.hub().sendText(c, text.replace("\n", "\r\n") + "\r\n"); }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
