package dev.activedesk.sched.actions;

import dev.activedesk.Config;
import dev.activedesk.sched.TaskAction;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Sends a plain-text email over SMTP with implicit TLS (port 465), no JavaMail.
 * Config: smtp.host, smtp.port, smtp.user, smtp.password, smtp.from. params: to, subject, body.
 * Gmail: use an App Password, host smtp.gmail.com, port 465.
 */
public final class EmailAction implements TaskAction {

    @Override
    public String run(Context ctx) throws Exception {
        Config c = ctx.config();
        String host = c.get("smtp.host").trim();
        if (host.isEmpty()) throw new IllegalStateException("set smtp.host in config.properties");
        int port = c.getInt("smtp.port", 465);
        String user = c.get("smtp.user");
        String from = c.get("smtp.from").isBlank() ? user : c.get("smtp.from");
        String to = ctx.task().param("to", "");
        String subject = ctx.task().param("subject", "ActiveDesk");
        String body = ctx.task().param("body", "");
        if (subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0) throw new IllegalArgumentException("subject may not contain line breaks");
        List<String> rcpts = new ArrayList<>();
        for (String r : to.split(",")) {
            String t = r.trim();
            if (t.isEmpty()) continue;
            if (!t.matches("[^\\s<>@,]+@[^\\s<>@,]+")) throw new IllegalArgumentException("bad recipient: " + t);
            rcpts.add(t);
        }
        if (rcpts.isEmpty()) throw new IllegalArgumentException("email task needs params.to");
        if (!from.matches("[^\\s<>@,]+@[^\\s<>@,]+")) throw new IllegalArgumentException("bad smtp.from: " + from);

        SSLSocket sock = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        sock.setSoTimeout((int) Math.min(Integer.MAX_VALUE, ctx.timeout().toMillis()));
        // verify the server certificate matches the host name
        javax.net.ssl.SSLParameters params = sock.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        sock.setSSLParameters(params);
        try (Socket s = sock;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            expect(in, 220);
            cmd(out, "EHLO activedesk");
            expect(in, 250);
            if (!user.isBlank()) {
                cmd(out, "AUTH LOGIN");
                expect(in, 334);
                cmd(out, b64(user));
                expect(in, 334);
                cmd(out, b64(c.get("smtp.password")));
                expect(in, 235);
            }
            cmd(out, "MAIL FROM:<" + from + ">");
            expect(in, 250);
            for (String r : rcpts) {
                cmd(out, "RCPT TO:<" + r + ">");
                expect(in, 250, 251);
            }
            cmd(out, "DATA");
            expect(in, 354);
            StringBuilder msg = new StringBuilder();
            msg.append("From: ").append(from).append("\r\n");
            msg.append("To: ").append(String.join(", ", rcpts)).append("\r\n");
            msg.append("Subject: ").append(subject).append("\r\n");
            msg.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
            msg.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n");
            for (String line : body.split("\r?\n", -1)) {
                msg.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            msg.append(".\r\n");
            out.write(msg.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(in, 250);
            cmd(out, "QUIT");
        }
        return "sent to " + rcpts.size() + " recipient(s)";
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private static void cmd(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads a (possibly multi-line) reply and checks its code. Never echoes what we sent (could be credentials). */
    private static void expect(BufferedReader in, int... codes) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) throw new IOException("SMTP server closed the connection");
        } while (line.length() > 3 && line.charAt(3) == '-');
        int code;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (RuntimeException e) {
            throw new IOException("bad SMTP reply: " + line);
        }
        for (int c : codes) if (c == code) return;
        throw new IOException("SMTP error: " + line);
    }
}
