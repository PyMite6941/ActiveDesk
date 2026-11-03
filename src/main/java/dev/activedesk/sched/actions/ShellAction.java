package dev.activedesk.sched.actions;

import dev.activedesk.sched.TaskAction;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a shell command (cmd.exe on Windows, /bin/sh elsewhere). params: command, workdir. */
public final class ShellAction implements TaskAction {
    private static final int CAP = 64 * 1024;

    @Override
    public String run(Context ctx) throws Exception {
        String command = ctx.task().param("command");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("shell task needs params.command");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = win ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-c", command);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        String wd = ctx.task().param("workdir");
        if (wd != null && !wd.isBlank()) pb.directory(new File(wd));

        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(p.getInputStream(), buf), "shell-output");
        reader.setDaemon(true);
        reader.start();

        if (!p.waitFor(ctx.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new TimeoutException("timed out after " + ctx.timeout().toSeconds() + "s");
        }
        reader.join(2000);
        String out;
        synchronized (buf) {
            out = buf.toString(StandardCharsets.UTF_8).trim();
        }
        int code = p.exitValue();
        if (code != 0) throw new RuntimeException("exit " + code + ": " + tail(out, 800));
        return out;
    }

    private static void drain(InputStream in, ByteArrayOutputStream buf) {
        byte[] b = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(b)) > 0) {
                synchronized (buf) {
                    int room = CAP - buf.size();
                    if (room > 0) buf.write(b, 0, Math.min(room, n));
                }
            }
        } catch (Exception ignored) {
            // process killed
        }
    }

    private static String tail(String s, int n) { return s.length() <= n ? s : "..." + s.substring(s.length() - n); }
}
