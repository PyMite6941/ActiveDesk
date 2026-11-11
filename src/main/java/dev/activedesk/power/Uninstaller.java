package dev.activedesk.power;

import dev.activedesk.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * `activedesk uninstall [--yes] [--purge]`: removes everything the installers, `install-service` and
 * `power install` put on this computer, in an order that can't strand the laptop:
 *
 *   1. autostart services that would restart the node (systemd --user, launchd)
 *   2. the running node
 *   3. charge limits back to normal FIRST, then the admin-level battery/power setup (one OS prompt)
 *   4. autostart files, Start-menu entry, PATH entry, the `activedesk` command, the program folder
 *   5. with --purge only: the data folder (config, PIN, chat history, logs)
 *
 * Received files in Downloads/ActiveDesk are never touched. Every step runs even if an earlier one
 * failed; the exit code is non-zero if anything could not be removed, and the output says what.
 */
public final class Uninstaller {
    private Uninstaller() { }

    public static int run(List<String> args) throws IOException, InterruptedException {
        boolean yes = args.contains("--yes") || args.contains("-y");
        boolean purge = args.contains("--purge");
        for (String a : args) {
            if (!List.of("--yes", "-y", "--purge").contains(a)) {
                System.out.println("Unknown option '" + a + "'. Usage: activedesk uninstall [--yes] [--purge]");
                return 2;
            }
        }
        Path home = Config.defaultHome();
        System.out.println("This removes ActiveDesk from this computer: the autostart entry, the 'activedesk' command,");
        System.out.println("the program files and the battery/power setup (normal charging is restored first).");
        if (Files.isDirectory(home)) System.out.println(purge ? "--purge: your data folder is deleted too: " + home
                : "Your data folder is kept (add --purge to delete it): " + home);
        if (!yes && !confirm()) {
            System.out.println("Cancelled. Nothing was changed.");
            return 1;
        }
        Consumer<String> out = l -> System.out.println("  " + l);
        List<String> failed = new ArrayList<>();
        String user = System.getProperty("user.home");

        // 1 + 2: nothing may restart the node while we take it apart
        if (Proc.linux() && Files.exists(systemdUnit(user))) {
            Proc.run(30, "systemctl", "--user", "disable", "--now", "activedesk");
            out.accept("Stopped and disabled the systemd user service.");
        }
        if (Proc.mac() && Files.exists(launchAgent(user))) {
            Proc.run(30, "launchctl", "unload", "-w", launchAgent(user).toString());
            out.accept("Unloaded the launch agent.");
        }
        stopNode(home, out);

        // 3: battery + power (only when it was set up, so a plain uninstall never asks for admin rights)
        Config config = Files.isDirectory(home) ? Config.load() : null;
        Path scratch = null;
        if (config == null) { // no data folder: work against a throw-away one instead of creating it
            scratch = Files.createTempDirectory("activedesk-uninstall");
            config = Config.loadFrom(scratch);
        }
        try {
            if (powerSetUp(config)) {
                if (PowerSetup.uninstall(config, true, out) != 0) failed.add("battery/power setup");
            } else if (Files.exists(config.home().resolve("battery-original.properties"))) {
                if (BatteryService.reset(config, out) != 0) failed.add("battery thresholds");
            } else {
                out.accept("No battery/power setup to remove.");
            }
        } finally {
            if (scratch != null) deleteTree(scratch);
        }

        // 4: files
        List<Path> later = new ArrayList<>(); // Windows: locked by this running JVM, deleted after it exits
        if (Proc.windows()) {
            String appData = System.getenv("APPDATA"), local = System.getenv("LOCALAPPDATA");
            if (appData != null) {
                Path programs = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs");
                remove(programs.resolve("Startup").resolve("ActiveDesk.vbs"), "autostart entry", out, failed);
                remove(programs.resolve("ActiveDesk.lnk"), "Start-menu entry", out, failed);
                remove(programs.resolve("ActiveDesk.url"), "Start-menu entry", out, failed);
            }
            if (local != null) {
                Path dir = Path.of(local, "ActiveDesk");
                Proc.Result r = Proc.powershell(30, removeFromUserPath(dir.toString()));
                if (r.out().contains("removed")) out.accept("Removed " + dir + " from your PATH.");
                if (Files.exists(dir.resolve("activedesk.jar")) || Files.exists(dir.resolve("activedesk.cmd"))) later.add(dir);
            }
        } else {
            if (Proc.mac()) remove(launchAgent(user), "launch agent", out, failed);
            remove(Path.of(user, ".config", "autostart", "activedesk.desktop"), "desktop autostart entry", out, failed);
            if (Files.exists(systemdUnit(user))) {
                remove(systemdUnit(user), "systemd user service", out, failed);
                Proc.run(30, "systemctl", "--user", "daemon-reload");
            }
            Path cmd = Path.of(user, ".local", "bin", "activedesk");
            if (Files.isRegularFile(cmd) && Proc.read(cmd).contains("created by the ActiveDesk installer"))
                remove(cmd, "'activedesk' command", out, failed);
            String xdg = System.getenv("XDG_DATA_HOME");
            Path app = (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(user, ".local", "share")).resolve("activedesk");
            if (Files.exists(app.resolve("activedesk.jar"))) removeTree(app, "program files", out, failed);
        }

        // 5: data
        if (purge && Files.isDirectory(home)) {
            if (Proc.windows()) later.add(home);
            else removeTree(home, "data folder", out, failed);
        } else if (Files.isDirectory(home)) {
            out.accept("Kept your data folder: " + home);
        }

        if (!later.isEmpty()) {
            deleteAfterExit(later);
            for (Path p : later) out.accept("Removing " + p + " (finishes a few seconds after this command exits).");
        }
        System.out.println();
        if (failed.isEmpty()) {
            System.out.println("ActiveDesk is uninstalled." + (Proc.windows() ? " Open a new terminal for the PATH change." : ""));
            return 0;
        }
        System.out.println("Uninstalled, except: " + String.join(", ", failed) + ". See the messages above.");
        return 1;
    }

    // ------------------------------------------------------------------ steps

    private static boolean confirm() throws IOException {
        System.out.print("Uninstall ActiveDesk? [y/N] ");
        System.out.flush();
        String a = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return a != null && a.trim().toLowerCase(java.util.Locale.ROOT).startsWith("y");
    }

    /** Stops the node of this data folder (node.pid), never anything else. */
    private static void stopNode(Path home, Consumer<String> out) throws InterruptedException {
        Path pidFile = home.resolve("node.pid");
        String id = Proc.read(pidFile);
        if (!id.matches("\\d{1,10}")) { out.accept("ActiveDesk is not running."); return; }
        Optional<ProcessHandle> ph = ProcessHandle.of(Long.parseLong(id));
        if (ph.isEmpty() || !ph.get().isAlive() || ph.get().pid() == ProcessHandle.current().pid() || !looksLikeNode(ph.get(), pidFile)) {
            out.accept("ActiveDesk is not running.");
            return;
        }
        ProcessHandle p = ph.get();
        p.destroy(); // SIGTERM on Linux/macOS: the node's shutdown hook restores battery thresholds itself
        for (int i = 0; i < 50 && p.isAlive(); i++) Thread.sleep(100);
        if (p.isAlive()) p.destroyForcibly();
        try { Files.deleteIfExists(pidFile); } catch (IOException ignored) { }
        out.accept("Stopped the running ActiveDesk (process " + id + ").");
    }

    /** A java/ActiveDesk process that was already running when node.pid was written (not a reused PID). */
    private static boolean looksLikeNode(ProcessHandle p, Path pidFile) {
        String exe = p.info().command().map(c -> Path.of(c).getFileName().toString().toLowerCase(java.util.Locale.ROOT)).orElse("");
        if (!exe.matches("javaw?(\\.exe)?|activedesk(\\.exe)?")) return false;
        try {
            long written = Files.getLastModifiedTime(pidFile).toMillis();
            return p.info().startInstant().map(s -> s.toEpochMilli() <= written + 5_000).orElse(true);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean powerSetUp(Config config) {
        if (Proc.windows()) {
            String pf = System.getenv("ProgramFiles"), pd = System.getenv("ProgramData");
            return Proc.run(20, "schtasks", "/query", "/tn", BatteryService.TASK).ok()
                    || (pf != null && Files.exists(Path.of(pf, "ActiveDesk")))
                    || (pd != null && Files.exists(Path.of(pd, "ActiveDesk")));
        }
        if (Proc.mac()) return Files.exists(Path.of(PowerSetup.SUDOERS));
        return Files.exists(Path.of(PowerSetup.UDEV_RULE));
    }

    /** Takes exactly that folder out of the user PATH, keeping the value's registry type (REG_EXPAND_SZ stays). */
    private static String removeFromUserPath(String dir) {
        return String.join("\n",
                "$d = (" + Proc.psq(dir) + ").TrimEnd('\\')",
                "$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)",
                "if ($k -and $k.GetValueNames() -contains 'Path') {",
                "  $old = $k.GetValue('Path', '', 'DoNotExpandEnvironmentNames')",
                "  $parts = @($old -split ';')",
                "  $keep = @($parts | Where-Object { $_.TrimEnd('\\') -ne $d })",
                "  if ($keep.Count -lt $parts.Count) {",
                "    $new = $keep -join ';'",
                "    $k.SetValue('Path', $new, $k.GetValueKind('Path'))",
                "    [Environment]::SetEnvironmentVariable('ActiveDeskUninstall', $null, 'User')  # tells Explorer the environment changed",
                "    'removed'",
                "  }",
                "}");
    }

    /** Windows: delete folders the running JVM still holds open, from a detached helper that waits for it to exit. */
    private static void deleteAfterExit(List<Path> dirs) throws IOException {
        StringBuilder s = new StringBuilder("Wait-Process -Id " + ProcessHandle.current().pid() + " -Timeout 60 -ErrorAction SilentlyContinue\n");
        for (Path d : dirs) {
            s.append("for ($i = 0; $i -lt 15 -and (Test-Path -LiteralPath ").append(Proc.psq(d.toString())).append("); $i++) {")
             .append(" Remove-Item -LiteralPath ").append(Proc.psq(d.toString())).append(" -Recurse -Force -ErrorAction SilentlyContinue;")
             .append(" Start-Sleep -Seconds 1 }\n");
        }
        String inner = Base64.getEncoder().encodeToString(s.toString().getBytes(StandardCharsets.UTF_16LE));
        // Start-Process gives the helper its own (hidden) console, so closing this terminal doesn't kill it.
        Proc.Result r = Proc.powershell(20, "Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + inner + "'");
        if (!r.ok()) throw new IOException("could not start the clean-up helper: " + r.out().trim());
    }

    private static void remove(Path f, String what, Consumer<String> out, List<String> failed) {
        try {
            if (Files.deleteIfExists(f)) out.accept("Removed the " + what + ": " + f);
        } catch (IOException e) {
            out.accept("Could not remove " + f + ": " + e.getMessage());
            failed.add(what);
        }
    }

    private static void removeTree(Path dir, String what, Consumer<String> out, List<String> failed) {
        try {
            deleteTree(dir);
            out.accept("Removed the " + what + ": " + dir);
        } catch (IOException e) {
            out.accept("Could not remove " + dir + ": " + e.getMessage());
            failed.add(what);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static Path systemdUnit(String user) { return Path.of(user, ".config", "systemd", "user", "activedesk.service"); }

    private static Path launchAgent(String user) { return Path.of(user, "Library", "LaunchAgents", "dev.activedesk.plist"); }
}
