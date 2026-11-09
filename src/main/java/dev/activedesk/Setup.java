package dev.activedesk;

import dev.activedesk.net.Hub;
import dev.activedesk.tailscale.Tailscale;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * `activedesk setup`: a step-by-step first-run wizard. Every step explains itself in one line, has a safe default
 * (press Enter), and can be re-run later. `setup --yes` accepts all defaults without asking (for scripts).
 */
final class Setup {
    private Setup() { }

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static BufferedReader in;
    private static boolean yes;

    static void run(List<String> args) throws Exception {
        yes = args.contains("--yes") || args.contains("-y");
        in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Config c = Config.load();
        String freshPin = c.freshPin();

        title("ActiveDesk " + Hub.VERSION + " setup");
        say("This takes about a minute. Press Enter to accept the [default] answer.");

        // 1. Tailscale --------------------------------------------------------------------------------
        step(1, "Tailscale (the free private network ActiveDesk uses)");
        Tailscale ts = new Tailscale(c.get("tailscale.path"));
        Tailscale.Status st = ts.status(true);
        if ("Unavailable".equals(st.backendState())) {
            say("Tailscale is not installed on this device.");
            say("Install it (free for personal use):  https://tailscale.com/download");
            if (OS.contains("linux")) say("  Linux / Raspberry Pi:  curl -fsSL https://tailscale.com/install.sh | sh  &&  sudo tailscale up");
            say("Then sign in with the SAME account on every device (laptop, Pi, phone). You can keep going now.");
        } else if (!st.running()) {
            say("Tailscale is installed but not connected (" + st.backendState() + ").");
            say("Open the Tailscale app and sign in" + (OS.contains("linux") ? ", or run:  sudo tailscale up" : "") + ".");
        } else {
            say("Connected as " + st.self().displayName() + " (" + st.self().ipv4() + "), signed in as " + st.self().login() + ".");
            say(st.peers().size() + " other device(s) on your tailnet.");
        }

        // 2. Screen or no screen ------------------------------------------------------------------------
        step(2, "Screen");
        boolean screen = !GraphicsEnvironment.isHeadless() && hasDisplay();
        if (screen) {
            say("This device has a screen: you can view and control it from your phone or another computer.");
        } else {
            say("No screen found (headless device, e.g. a Pi without a monitor).");
            say("Remote screen is switched off here; use Tailscale SSH for a terminal instead (free, no keys to copy).");
            if (!OS.contains("win") && ask("Turn on Tailscale SSH for this device now?", true)) {
                int rc = exec(List.of("sudo", ts.binary(), "set", "--ssh"));
                say(rc == 0 ? "Done. From another device:  tailscale ssh " + System.getProperty("user.name") + "@" + c.deviceName()
                        : "Could not change it (exit " + rc + "). Run:  sudo tailscale set --ssh");
            }
        }

        // 3. Device name -----------------------------------------------------------------------------
        step(3, "Device name (shown on your other devices)");
        String name = askText("Name", c.deviceName());
        if (!name.equals(c.deviceName())) {
            if (name.matches("[A-Za-z0-9 ._-]{1,40}")) {
                c.update("device.name", name);
                say("Saved.");
            } else {
                say("Kept '" + c.deviceName() + "' (use letters, numbers, spaces, . _ - only).");
            }
        }

        // 4. PIN ------------------------------------------------------------------------------------
        step(4, "Web PIN (needed to open ActiveDesk from your phone or browser)");
        if (freshPin != null) {
            say("Your PIN is:  " + freshPin + "   <- write it down, it is shown only once.");
        } else if (ask("A PIN is already set. Make a new one?", false)) {
            say("Your new PIN is:  " + c.resetPin() + "   <- write it down.");
        } else {
            say("Keeping your current PIN. (Forgot it? Run 'activedesk pin'.)");
        }

        // 5. Keep the screen on ----------------------------------------------------------------------
        if (screen) {
            step(5, "Keep the screen awake");
            say("  session = only while you control it remotely (recommended)");
            say("  always  = never let it sleep while ActiveDesk runs");
            say("  off     = normal sleep");
            String mode = askText("Mode", c.get("power.keepAwake").isBlank() ? "session" : c.get("power.keepAwake")).toLowerCase(Locale.ROOT);
            if (mode.matches("off|session|always")) c.update("power.keepAwake", mode);
            else say("Unknown choice, kept '" + c.get("power.keepAwake") + "'.");
        }

        // 6. Power + battery -------------------------------------------------------------------------
        step(6, "Power mode, brightness and battery charge limit (optional)");
        say("Needs your admin password ONCE on this device. You can skip it and do it later from the app.");
        if (ask("Set it up now?", false)) {
            int rc = dev.activedesk.power.PowerSetup.install(c, true, Setup::say);
            if (rc != 0) say("Skipped. Try again any time:  activedesk power install");
        }

        // 7. Start automatically ---------------------------------------------------------------------
        step(7, "Start automatically");
        if (ask("Start ActiveDesk every time this device starts?", true)) {
            try {
                Installer.install();
            } catch (Exception e) {
                say("Could not set up autostart (" + e.getMessage() + "). Try later:  activedesk install-service");
            }
        }

        // Done ---------------------------------------------------------------------------------------
        title("All set");
        st = ts.status(true);
        if (st.running() && st.self().ipv4() != null)
            say("On your phone (with Tailscale on) open:  http://" + st.self().ipv4() + ":" + c.webPort() + "/");
        say("On this device:  http://127.0.0.1:" + c.webPort() + "/");
        say("Open the app:    activedesk   (starts it if needed)");
        say("Help:            https://pymite6941.github.io/ActiveDesk/");
    }

    private static boolean hasDisplay() {
        if (OS.contains("win") || OS.contains("mac")) return true;
        return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    private static int exec(List<String> cmd) {
        try {
            return new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (IOException e) {
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    // ------------------------------------------------------------------ prompts

    private static void title(String t) {
        System.out.println();
        System.out.println("  == " + t + " ==");
        System.out.println();
    }

    private static void step(int n, String t) {
        System.out.println();
        System.out.println("  [" + n + "/7] " + t);
    }

    static void say(String s) { System.out.println("        " + s); }

    private static boolean ask(String q, boolean def) throws IOException {
        if (yes) {
            say(q + " -> " + (def ? "yes" : "no"));
            return def;
        }
        System.out.print("        " + q + (def ? " [Y/n] " : " [y/N] "));
        System.out.flush();
        String a = in.readLine();
        if (a == null || a.isBlank()) return def;
        return a.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    private static String askText(String q, String def) throws IOException {
        if (yes) return def;
        System.out.print("        " + q + " [" + def + "]: ");
        System.out.flush();
        String a = in.readLine();
        return a == null || a.isBlank() ? def : a.trim();
    }
}
