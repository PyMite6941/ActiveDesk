package dev.activedesk.power;

import dev.activedesk.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * One-time privileged setup for power + battery control on this computer. Run from the CLI
 * (`activedesk power install`) or from the web app's Set up button: either way the OS shows its own
 * admin prompt ON THIS DEVICE (UAC / polkit / macOS password dialog), so a remote session can never
 * grant itself rights.
 *
 *   Windows  adds a "Power saver" plan if Windows hides it; if the laptop's charge limit is supported (ASUS/Dell),
 *            copies the battery guard to admin-only Program Files and registers the on-demand task.
 *   Linux    one udev rule letting the user's group write ONLY: battery charge thresholds, CPU governors,
 *            backlight brightness. ActiveDesk itself never runs as root.
 *   macOS    a sudoers drop-in allowing exactly `/usr/bin/pmset -a lowpowermode 0|1`, checked with visudo first.
 */
public final class PowerSetup {
    private PowerSetup() { }

    static final String UDEV_RULE = "/etc/udev/rules.d/90-activedesk-power.rules";
    static final String SUDOERS = "/etc/sudoers.d/activedesk";
    private static final String SAVER_PLAN = "a1841308-3541-4fab-bc81-f71556f20b4a"; // Windows' built-in Power saver template

    /** @param terminal true when a person is at a terminal (use sudo); false from the web app (use GUI prompts) */
    public static int install(Config config, boolean terminal, Consumer<String> out) throws IOException {
        if (Proc.windows()) return installWindows(config, out);
        if (Proc.mac()) return installMac(terminal, out);
        return installLinux(terminal, out);
    }

    public static int uninstall(Config config, boolean terminal, Consumer<String> out) throws IOException, InterruptedException {
        if (Proc.windows()) {
            // Normal charging FIRST, while the guard that can restore it still exists. If its task can't do it
            // (missing, broken), the restore rides along in the same elevated script: still one admin prompt.
            config.update("battery.enabled", "false");
            boolean restored = BatteryService.restoreViaTask(out);
            out.accept("Windows will ask for admin permission once to remove the battery guard"
                    + (restored ? "." : " (and restore normal charging)."));
            int rc = Proc.elevate(windowsRemoveScript(restored), 300);
            out.accept(switch (rc) {
                case 0 -> "Removed the battery guard and its task (the Power saver plan stays; delete it in Control Panel if you like).";
                case 2 -> "Cancelled at the admin prompt. Nothing was removed.";
                case 4 -> "Removed the battery guard, but could NOT restore normal charging. Restart the laptop to clear the limit.";
                default -> "Removing the battery guard failed (exit " + rc + ").";
            });
            return rc;
        }
        // Linux: write the saved thresholds back while the udev rule still lets us.
        BatteryService.reset(config, out);
        String cmd = Proc.mac() ? "rm -f " + SUDOERS : "rm -f " + UDEV_RULE + " && udevadm control --reload";
        int rc = asRoot(cmd, null, terminal);
        out.accept(rc == 0 ? "Removed the one-time setup." : "Uninstall failed or was cancelled (exit " + rc + ").");
        return rc;
    }

    /** Elevated: restore normal charging unless the task already did, then remove task + folders. Exit 0 ok, 4 restore failed, 1 leftovers. */
    static String windowsRemoveScript(boolean restored) {
        return (restored ? "$restoreRc = 0\n" : BatteryService.RESTORE_BODY)
                    + "$removeRc = 1\n"
                    + "try {\n"
                    + "  $ErrorActionPreference = 'Continue'\n"
                    + "  Get-ScheduledTask -TaskPath '\\ActiveDesk\\' -ErrorAction SilentlyContinue | Stop-ScheduledTask -ErrorAction SilentlyContinue\n"
                    + "  Unregister-ScheduledTask -TaskName 'Battery' -TaskPath '\\ActiveDesk\\' -Confirm:$false -ErrorAction SilentlyContinue\n"
                    + "  Remove-Item -LiteralPath (Join-Path $env:ProgramFiles 'ActiveDesk') -Recurse -Force -ErrorAction SilentlyContinue\n"
                    + "  Remove-Item -LiteralPath (Join-Path $env:ProgramData 'ActiveDesk') -Recurse -Force -ErrorAction SilentlyContinue\n"
                    + "  $left = (Get-ScheduledTask -TaskName 'Battery' -TaskPath '\\ActiveDesk\\' -ErrorAction SilentlyContinue) -or (Test-Path -LiteralPath (Join-Path $env:ProgramFiles 'ActiveDesk'))\n"
                    + "  if (-not $left) { $removeRc = 0 }\n"
                    + "} catch { }\n"
                    + "if ($restoreRc -eq 1) { exit 4 }\n"
                    + "exit $removeRc\n";
    }

    // ------------------------------------------------------------------ Windows

    private static int installWindows(Config config, Consumer<String> out) throws IOException {
        boolean guard = Proc.powershell(20, "'asus=' + [bool](Get-CimClass -Namespace root/wmi -ClassName AsusAtkWmi_WMNB -ErrorAction SilentlyContinue)")
                .out().contains("asus=True") || BatteryService.dellCctk() != null;
        Path tmp = Files.createTempFile("activedesk-battery-guard", ".ps1");
        try {
            try (InputStream in = PowerSetup.class.getResourceAsStream("/power/battery-guard.ps1")) {
                if (in == null) throw new IOException("guard script missing from the jar");
                Files.write(tmp, in.readAllBytes());
            }
            String user = System.getenv().getOrDefault("USERDOMAIN", ".") + "\\" + System.getenv().getOrDefault("USERNAME", "");
            String home = config.home().toAbsolutePath().toString();
            String script = String.join("\n",
                    "$ErrorActionPreference = 'Stop'",
                    "try {",
                    "  # 1. a Power saver plan, if Windows hides it (non-fatal: some PCs lack the template)",
                    "  try {",
                    "    $plans = powercfg /list | Out-String",
                    "    if ($plans -notmatch '(?i)saver|efficien|" + SAVER_PLAN + "') { powercfg /duplicatescheme " + SAVER_PLAN + " | Out-Null }",
                    "  } catch { }",
                    guard ? String.join("\n",
                            "  # 2. battery guard: admin-only folder so user-level code can't swap the elevated script",
                            "  $dir = Join-Path $env:ProgramFiles 'ActiveDesk'",
                            "  New-Item -ItemType Directory -Force -Path $dir | Out-Null",
                            "  $dest = Join-Path $dir 'battery-guard.ps1'",
                            "  Copy-Item -LiteralPath " + Proc.psq(tmp.toString()) + " -Destination $dest -Force",
                            "  $arg = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File \"' + $dest + '\" -UserHome \"' + " + Proc.psq(home) + " + '\"'",
                            "  $a = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg",
                            "  $p = New-ScheduledTaskPrincipal -UserId " + Proc.psq(user) + " -LogonType Interactive -RunLevel Highest",
                            "  $s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew",
                            "  Register-ScheduledTask -TaskName 'Battery' -TaskPath '\\ActiveDesk\\' -Action $a -Principal $p -Settings $s -Force | Out-Null")
                          : "  # no supported charge-limit interface on this laptop: nothing else to install",
                    "  exit 0",
                    "} catch { exit 1 }");
            out.accept("Windows will ask for admin permission once on this computer...");
            int rc = Proc.elevate(script, 300);
            if (rc != 0) {
                out.accept("Setup failed or was cancelled (exit " + rc + ").");
                return rc;
            }
            if (guard && !Proc.run(20, "schtasks", "/query", "/tn", BatteryService.TASK).ok()) {
                out.accept("The battery task was not registered.");
                return 1;
            }
            out.accept(guard ? "Done: Power saver plan + battery charge-range guard installed." : "Done: Power saver plan available. (This laptop's charge limit can't be controlled from software.)");
            return 0;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------ Linux

    private static int installLinux(boolean terminal, Consumer<String> out) throws IOException {
        String group = Proc.run(10, "id", "-gn").out().trim();
        if (!group.matches("[a-z_][a-z0-9_-]{0,31}")) {
            out.accept("Unexpected group name '" + group + "'.");
            return 1;
        }
        String bat = "/sys%p/charge_control_start_threshold /sys%p/charge_control_end_threshold /sys%p/charge_start_threshold /sys%p/charge_stop_threshold /sys%p/charge_behaviour";
        String rules = String.join("\n",
                "# ActiveDesk: let group '" + group + "' change battery charge limits, CPU governor and screen brightness (nothing else)",
                rule("SUBSYSTEM==\"power_supply\", ATTR{type}==\"Battery\"", group, bat),
                rule("SUBSYSTEM==\"backlight\"", group, "/sys%p/brightness"),
                rule("SUBSYSTEM==\"cpu\"", group, "/sys%p/cpufreq/scaling_governor"),
                "");
        Path tmp = Files.createTempFile("activedesk", ".rules");
        Files.writeString(tmp, rules);
        out.accept("Installing " + UDEV_RULE + " (group '" + group + "' may write only battery limits, CPU governor and brightness). Your password is asked once.");
        String cmd = "install -m 0644 \"$1\" " + UDEV_RULE + " && udevadm control --reload"
                + " && for s in power_supply backlight cpu; do udevadm trigger --subsystem-match=$s --action=change; done && udevadm settle";
        int rc = asRoot(cmd, tmp.toString(), terminal);
        Files.deleteIfExists(tmp);
        out.accept(rc == 0 ? "Done." : terminal ? "Setup failed (exit " + rc + ")."
                : "Setup failed or was cancelled (exit " + rc + "). If no password dialog appeared, run 'activedesk power install' in a terminal on this computer.");
        return rc;
    }

    private static String rule(String match, String group, String files) {
        return match + ", ACTION!=\"remove\", RUN+=\"/bin/sh -c 'chgrp " + group + " " + files + " 2>/dev/null; chmod g+w " + files + " 2>/dev/null; true'\"";
    }

    // ------------------------------------------------------------------ macOS

    private static int installMac(boolean terminal, Consumer<String> out) throws IOException {
        String user = System.getProperty("user.name", "");
        if (!user.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")) {
            out.accept("Unexpected user name '" + user + "'.");
            return 1;
        }
        Path tmp = Files.createTempFile("activedesk", ".sudoers");
        Files.writeString(tmp, "# ActiveDesk: allow switching Low Power Mode, nothing else\n"
                + user + " ALL=(root) NOPASSWD: /usr/bin/pmset -a lowpowermode 0, /usr/bin/pmset -a lowpowermode 1\n");
        out.accept("Allowing Low Power Mode switching (sudoers rule for exactly 'pmset -a lowpowermode 0|1'). macOS asks for your password once.");
        int rc = asRoot("/usr/sbin/visudo -cf \"$1\" && /usr/bin/install -m 0440 -o root -g wheel \"$1\" " + SUDOERS, tmp.toString(), terminal);
        Files.deleteIfExists(tmp);
        out.accept(rc == 0 ? "Done. (Battery charge limits are not available on macOS.)" : "Setup failed or was cancelled (exit " + rc + ").");
        return rc;
    }

    // ------------------------------------------------------------------ run as root with the platform's own prompt

    /** Runs `sh -c cmd sh arg`. The command text is fixed in this class; only `arg` (a temp path) varies. */
    private static int asRoot(String cmd, String arg, boolean terminal) throws IOException {
        java.util.List<String> argv = new java.util.ArrayList<>();
        if (Proc.mac() && !terminal) {
            // macOS GUI password dialog; quoted form of keeps the temp path safe inside AppleScript
            String as = "do shell script \"/bin/sh -c \" & quoted form of " + appleString(cmd) + " & \" sh \" & quoted form of "
                    + appleString(arg == null ? "" : arg) + " with administrator privileges";
            argv.addAll(java.util.List.of("osascript", "-e", as));
        } else {
            if (terminal) argv.add("sudo");
            else if (Proc.onPath("pkexec")) argv.add("pkexec");
            else return 126; // no way to ask for a password without a terminal
            argv.addAll(java.util.List.of("/bin/sh", "-c", cmd, "sh"));
            if (arg != null) argv.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (terminal) pb.inheritIO();
        else pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            return pb.start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static String appleString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
