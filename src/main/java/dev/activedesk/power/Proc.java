package dev.activedesk.power;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Small process helpers shared by the power features. Never builds shell strings from untrusted input. */
final class Proc {
    private Proc() { }

    static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static boolean windows() { return OS.contains("win"); }
    static boolean mac() { return OS.contains("mac"); }
    static boolean linux() { return !windows() && !mac(); }

    record Result(int code, String out) {
        boolean ok() { return code == 0; }
    }

    static Result run(int timeoutSec, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            // read on this thread with a watchdog so a hung tool can't block forever
            Thread killer = new Thread(() -> {
                try {
                    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException ignored) { }
            }, "proc-timeout");
            killer.setDaemon(true);
            killer.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(5, TimeUnit.SECONDS);
            return new Result(p.isAlive() ? -1 : p.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }

    /**
     * PowerShell with -EncodedCommand. Java does not escape embedded double quotes in Windows command lines,
     * so passing scripts with -Command silently mangles them.
     */
    static Result powershell(int timeoutSec, String script) {
        String b64 = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return run(timeoutSec, "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", b64);
    }

    /** Run a PowerShell script elevated (UAC prompt on this device) and wait for it. */
    static int elevate(String script, int timeoutSec) {
        String inner = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        String outer = "try { $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
                + "-ArgumentList '-NoProfile -ExecutionPolicy Bypass -EncodedCommand " + inner + "'; exit $p.ExitCode } catch { exit 2 }";
        return powershell(timeoutSec, outer).code();
    }

    /**
     * Starts PowerShell running the -EncodedCommand payload `b64`, detached (it outlives this process, which
     * matters because it waits for us to exit) and with its console hidden from the first frame. Start-Process
     * -WindowStyle Hidden creates the window visible and hides it once PowerShell is up, so it flashes;
     * Win32_Process.Create with ShowWindow=0 never shows it.
     */
    static Result detachedHidden(String b64) {
        return powershell(30, "$si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }\n"
                + "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "
                + psq("powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + b64)
                + "; ProcessStartupInformation = $si }\n"
                + "exit [int]$r.ReturnValue");
    }

    /** Single-quoted PowerShell literal. */
    static String psq(String s) { return "'" + s.replace("'", "''") + "'"; }

    static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, exe))) return true;
        }
        return false;
    }

    static String read(Path f) {
        try { return Files.readString(f).trim(); } catch (IOException e) { return ""; }
    }

    static int readInt(Path f, int def) {
        try { return Integer.parseInt(read(f)); } catch (NumberFormatException e) { return def; }
    }
}
