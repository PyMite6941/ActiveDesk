package dev.activedesk.remote;

import dev.activedesk.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Keeps the display (and the machine) awake, so a device you are controlling never blanks, sleeps or
 * drops into its idle lock under you.
 *
 * Modes:
 *   off      never interfere with the OS power settings
 *   session  (default) awake while someone is watching or controlling the screen remotely, plus a grace period
 *   always   awake for as long as ActiveDesk runs (kiosk / dashboard / headless Pi)
 *
 * How, per OS, without native code:
 *   Windows  a PowerShell child calls SetThreadExecutionState(ES_CONTINUOUS|ES_SYSTEM_REQUIRED|ES_DISPLAY_REQUIRED)
 *            then blocks reading stdin. Windows drops the request the moment that thread ends.
 *   macOS    caffeinate -d -i -w <our pid>
 *   Linux    systemd-inhibit --what=idle:sleep (logind / GNOME / KDE) and, on X11, `xset s reset` each tick
 *            (the Pi desktop's screen blanking is the X server's, which logind does not control).
 * Every helper reads our stdin pipe or watches our pid, so it exits by itself if the JVM is killed:
 * a crash can never leave a machine stuck awake.
 * On top of that, while holding, every 30 s it nudges input like a person would: the pointer moves 1 px and back and
 * Shift is tapped (no character, no click on whatever is under the cursor). That resets the idle timer even where the
 * OS request is ignored (group policy, some Linux desktops, "away" status in chat apps).
 */
public final class KeepAwake {
    public enum Mode { OFF, SESSION, ALWAYS }

    /** How long after the last remote screen/input request the session mode keeps the display on. */
    static final long SESSION_GRACE_MS = 3 * 60_000;
    private static final long NUDGE_EVERY_MS = 30_000;

    private final ScreenService screen;
    private final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private volatile Mode mode;
    private volatile long lastActivity;
    private Process helper;
    private boolean helperFailed;
    private volatile long lastNudge;
    private boolean xsetMissing;
    private String method = "";

    public KeepAwake(ScreenService screen, Mode mode) {
        this.screen = screen;
        this.mode = mode;
    }

    public static Mode parse(String s) {
        if (s == null) return Mode.SESSION;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false", "no", "never" -> Mode.OFF;
            case "always", "on", "true", "yes" -> Mode.ALWAYS;
            default -> Mode.SESSION;
        };
    }

    public Mode mode() { return mode; }

    public void setMode(Mode m) {
        this.mode = m;
        tick();
    }

    /** A remote viewer/controller did something. */
    public void activity() {
        boolean wasActive = active();
        lastActivity = System.currentTimeMillis();
        if (!wasActive) tick();
    }

    /** Is the display currently being held on? */
    public synchronized boolean holding() { return (helper != null && helper.isAlive()) || (helperFailed && active()); }

    public String method() { return method + (screen.available() ? " + input nudge every 30 s" : ""); }

    /** Epoch ms of the last input nudge (0 = none yet). */
    public long lastNudge() { return lastNudge; }

    private boolean active() {
        return switch (mode) {
            case OFF -> false;
            case ALWAYS -> true;
            case SESSION -> System.currentTimeMillis() - lastActivity < SESSION_GRACE_MS;
        };
    }

    /** Called every ~10 s by the node and whenever the state may have changed. */
    public synchronized void tick() {
        boolean want = active();
        if (want && helper != null && !helper.isAlive()) {
            // died after start-up (e.g. the API call failed): stop retrying and nudge instead
            Log.info("keep-awake helper exited (" + helper.exitValue() + "), using mouse nudge instead");
            helper = null;
            helperFailed = true;
            method = "mouse nudge";
        }
        boolean alive = helper != null && helper.isAlive();
        if (want && !alive && !helperFailed) start();
        if (!want && helper != null) stop();
        if (want && isLinux() && hasX11()) xsetReset();
        if (want) nudge();
        if (!want) helperFailed = false; // try the real helper again next time
    }

    public synchronized void close() { stop(); }

    // ------------------------------------------------------------------ helpers per OS

    private void start() {
        List<String> cmd = command();
        if (cmd.isEmpty()) {
            helperFailed = true;
            method = "mouse nudge";
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            drain(p);
            // give it a moment: a helper that dies instantly (missing binary, blocked policy) means fall back
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (!p.isAlive() && p.exitValue() != 0) {
                Log.info("keep-awake helper exited (" + p.exitValue() + "), using mouse nudge instead");
                helperFailed = true;
                method = "mouse nudge";
                return;
            }
            helper = p;
            method = cmd.get(0).contains("powershell") ? "SetThreadExecutionState" : cmd.get(0);
            Log.info("keep-awake ON (" + mode.name().toLowerCase(Locale.ROOT) + ", " + method + ")");
        } catch (IOException e) {
            Log.info("keep-awake helper unavailable (" + e.getMessage() + "), using mouse nudge instead");
            helperFailed = true;
            method = "mouse nudge";
        }
    }

    private void stop() {
        if (helper == null) return;
        Process p = helper;
        helper = null;
        try { p.getOutputStream().close(); } catch (IOException ignored) { } // Windows/Linux helpers exit on EOF
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        Log.info("keep-awake OFF (display can sleep normally again)");
    }

    private List<String> command() {
        long pid = ProcessHandle.current().pid();
        List<String> c = new ArrayList<>();
        if (os.contains("win")) {
            String script = String.join("\n",
                    "$ErrorActionPreference = 'Stop'",
                    "$s = '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);'",
                    "$k = Add-Type -MemberDefinition $s -Name P -Namespace AD -PassThru",
                    "$r = $k::SetThreadExecutionState([uint32]2147483651)", // ES_CONTINUOUS|ES_DISPLAY_REQUIRED|ES_SYSTEM_REQUIRED
                    "if ($r -eq 0) { exit 3 }",
                    "[Console]::Out.WriteLine('ADK-HOLDING'); [Console]::Out.Flush()",
                    "[void][Console]::In.ReadToEnd()");                    // hold until our pipe closes (or the JVM dies)
            // -EncodedCommand: Java does not escape embedded double quotes in Windows command lines
            String b64 = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            c.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", b64));
        } else if (os.contains("mac")) {
            c.addAll(List.of("/usr/bin/caffeinate", "-d", "-i", "-w", Long.toString(pid)));
        } else if (onPath("systemd-inhibit")) {
            c.addAll(List.of("systemd-inhibit", "--what=idle:sleep", "--who=ActiveDesk",
                    "--why=Remote session in progress", "--mode=block", "cat"));
        } else {
            return List.of(); // no logind: on X11 the per-tick xset reset does the job, the nudge covers the rest
        }
        return c;
    }

    private void xsetReset() {
        if (xsetMissing) return;
        try {
            Process p = new ProcessBuilder("xset", "s", "reset").redirectErrorStream(true).start();
            drain(p);
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            // also switch the monitor back on if DPMS already turned it off
            Process q = new ProcessBuilder("xset", "dpms", "force", "on").redirectErrorStream(true).start();
            drain(q);
            if (!q.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) q.destroyForcibly();
        } catch (IOException e) {
            xsetMissing = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void nudge() {
        long now = System.currentTimeMillis();
        if (now - lastNudge < NUDGE_EVERY_MS || !screen.available()) return;
        lastNudge = now;
        try {
            screen.nudgeInput();
        } catch (RuntimeException e) {
            Log.debug("keep-awake nudge failed: " + e.getMessage());
        }
    }

    private boolean isLinux() { return !os.contains("win") && !os.contains("mac"); }

    private static boolean hasX11() {
        String d = System.getenv("DISPLAY");
        return d != null && !d.isBlank();
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, exe))) return true;
        }
        return false;
    }

    /** Swallow helper output on a daemon thread so a full pipe can never block it. */
    private static void drain(Process p) {
        Consumer<Process> r = proc -> {
            try (var in = proc.getInputStream()) {
                byte[] buf = new byte[512];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String s = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
                    if (s.contains("ADK-HOLDING")) Log.info("keep-awake: Windows confirmed the display is held on");
                    else if (!s.isEmpty()) Log.debug("keep-awake: " + s);
                }
            } catch (IOException ignored) { }
        };
        Thread t = new Thread(() -> r.accept(p), "keepawake-drain");
        t.setDaemon(true);
        t.start();
    }
}
