package dev.activedesk;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** `activedesk install-service`: start ActiveDesk automatically, per user (no admin rights needed). */
final class Installer {
    private Installer() {}

    static void install() throws IOException, URISyntaxException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = launchCommand();
        if (os.contains("win")) windows(cmd);
        else if (os.contains("mac")) mac(cmd);
        else linux(cmd);
    }

    /** {executable, args...} that starts this same build in the background. */
    private static String[] launchCommand() throws URISyntaxException {
        String app = System.getProperty("jpackage.app-path");
        if (app != null && !app.isBlank()) return new String[]{app, "run"};
        String java = ProcessHandle.current().info().command().orElse("java");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Path javaw = Path.of(java).resolveSibling("javaw.exe");
            if (Files.exists(javaw)) java = javaw.toString();
        }
        Path jar = Path.of(ActiveDesk.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new String[]{java, "-jar", jar.toString(), "run"};
    }

    private static void windows(String[] cmd) throws IOException {
        Path startup = Path.of(System.getenv("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        Path vbs = startup.resolve("ActiveDesk.vbs");
        StringBuilder line = new StringBuilder();
        for (String c : cmd) line.append(line.length() == 0 ? "" : " ").append("\"\"").append(c).append("\"\"");
        // window style 0 = hidden; no console window at login
        String body = "' Starts ActiveDesk at login (created by 'activedesk install-service')\r\n"
                + "CreateObject(\"Wscript.Shell\").Run \"" + line + "\", 0, False\r\n";
        Files.writeString(vbs, body, StandardCharsets.UTF_8);
        System.out.println("Installed: " + vbs);
        System.out.println("ActiveDesk will start hidden every time you sign in. Remove that file to undo.");
        System.out.println("Tip: allow Java through Windows Firewall on the Tailscale network if asked.");
    }

    private static void mac(String[] cmd) throws IOException {
        Path plist = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "dev.activedesk.plist");
        Files.createDirectories(plist.getParent());
        StringBuilder args = new StringBuilder();
        for (String c : cmd) args.append("    <string>").append(xml(c)).append("</string>\n");
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>Label</key><string>dev.activedesk</string>
                  <key>ProgramArguments</key><array>
                %s  </array>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><true/>
                </dict></plist>
                """.formatted(args);
        Files.writeString(plist, body, StandardCharsets.UTF_8);
        System.out.println("Installed: " + plist);
        System.out.println("Enable now:  launchctl load -w " + plist);
        System.out.println("For screen control grant Java 'Screen Recording' and 'Accessibility' in System Settings > Privacy & Security.");
    }

    private static void linux(String[] cmd) throws IOException {
        String home = System.getProperty("user.home");
        StringBuilder exec = new StringBuilder();
        for (String c : cmd) exec.append(exec.length() == 0 ? "" : " ").append('"').append(c.replace("\"", "\\\"")).append('"');
        boolean desktop = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        if (desktop) {
            Path f = Path.of(home, ".config", "autostart", "activedesk.desktop");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "[Desktop Entry]\nType=Application\nName=ActiveDesk\nExec=" + exec
                    + "\nX-GNOME-Autostart-enabled=true\nNoDisplay=true\n", StandardCharsets.UTF_8);
            System.out.println("Installed desktop autostart: " + f + " (starts when you log in to the desktop; screen control works)");
            if (System.getenv("WAYLAND_DISPLAY") != null)
                System.out.println("Note: Wayland blocks screen capture for apps. On Raspberry Pi OS pick X11 in raspi-config > Advanced > Wayland.");
        }
        Path unit = Path.of(home, ".config", "systemd", "user", "activedesk.service");
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, "[Unit]\nDescription=ActiveDesk node\nAfter=network-online.target tailscaled.service\n\n"
                + "[Service]\nExecStart=" + exec + "\nRestart=on-failure\nRestartSec=5\n"
                + "NoNewPrivileges=true\nPrivateTmp=true\n\n[Install]\nWantedBy=default.target\n", StandardCharsets.UTF_8);
        System.out.println("Wrote systemd user unit: " + unit);
        if (!desktop) {
            System.out.println("Headless device (e.g. a Pi without a desktop). Enable at boot with:");
            System.out.println("  systemctl --user daemon-reload && systemctl --user enable --now activedesk");
            System.out.println("  sudo loginctl enable-linger $USER   # start at boot without logging in");
        } else {
            System.out.println("(Use the systemd unit instead only if this device runs without a desktop.)");
        }
    }

    private static String xml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
