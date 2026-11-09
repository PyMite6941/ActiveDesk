package dev.activedesk.remote;

import dev.activedesk.util.Log;
import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Screen capture + input injection with java.awt.Robot. Unavailable (but harmless) on headless devices
 * such as a Pi without a desktop, or a Linux Wayland session without XWayland capture support.
 */
public final class ScreenService {
    private final Robot robot;
    private final String unavailableReason;
    private final boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /**
     * Gaps between synthetic key events. With none, apps that read modifier state asynchronously
     * (Windows 11 Notepad, Electron/WinUI apps, browsers under load) see the key after Shift/Ctrl was
     * already released, so "A" arrives as "a", "!" as "1" and ctrl+v as a plain "v".
     */
    private static final int MOD_HOLD_MS = 35, KEY_GAP_MS = 10;

    private volatile boolean pasteMode;
    /** Windows: types text as Unicode characters (no Shift races, any layout). Null elsewhere. */
    private final WinTyper winTyper = System.getProperty("os.name", "").startsWith("Windows") ? new WinTyper() : null;

    /** typeMode "keys" (default: Unicode input on Windows, else real key presses, US layout, falls back to paste) or "paste". */
    public void setTypeMode(String mode) { pasteMode = "paste".equalsIgnoreCase(mode); }

    public ScreenService(boolean enabled) {
        Robot r = null;
        String why = null;
        if (!enabled) why = "disabled in config (control.enabled=false)";
        else if (GraphicsEnvironment.isHeadless()) why = "no display (headless)";
        else {
            try {
                r = new Robot();
                r.setAutoDelay(0);
            } catch (AWTException | SecurityException | UnsupportedOperationException e) {
                why = "screen access unavailable: " + e.getMessage();
            }
        }
        robot = r;
        unavailableReason = why;
        if (why != null) Log.info("remote screen: " + why);
    }

    public boolean available() { return robot != null; }
    public String unavailableReason() { return unavailableReason; }

    /** Union of all monitors, in the same coordinate space Robot uses for the mouse. */
    public Rectangle bounds() {
        Rectangle all = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            all = all.union(gd.getDefaultConfiguration().getBounds());
        }
        return all;
    }

    /** Keep-awake "someone is here": pointer 1 px and back + a Shift tap. Never clicks, never types a character. */
    public synchronized void nudgeInput() {
        if (robot == null) return;
        robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT);
        robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT);
        nudgePointer();
    }

    /** Move the pointer 1 px and back. */
    public synchronized void nudgePointer() {
        if (robot == null) return;
        java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
        if (pi == null) return;
        java.awt.Point p = pi.getLocation();
        Rectangle b = bounds();
        int dx = p.x + 1 < b.x + b.width ? 1 : -1;
        robot.mouseMove(p.x + dx, p.y);
        robot.mouseMove(p.x, p.y);
    }

    /** JPEG of the whole desktop, scaled down to maxWidth. quality 0.1..0.95. */
    public synchronized byte[] captureJpeg(int maxWidth, float quality) throws IOException {
        if (robot == null) throw new IOException(unavailableReason);
        Rectangle b = bounds();
        BufferedImage shot = robot.createScreenCapture(b);
        int w = shot.getWidth(), h = shot.getHeight();
        int tw = Math.max(320, Math.min(maxWidth, w));
        int th = (int) Math.round(h * (tw / (double) w));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(shot, 0, 0, tw, th, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(tw * th / 4);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(Math.max(0.1f, Math.min(0.95f, quality)));
            writer.write(null, new IIOImage(out, null, null), p);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /**
     * Apply one input event. Coordinates are normalised 0..1 over the captured image, so the client
     * never needs to know the real resolution. Event map keys:
     *   kind: move | click | dblclick | down | up | scroll | key | type
     *   x, y (0..1), button (left|right|middle), amount (scroll notches), key ("enter", "ctrl+c"...), text
     */
    public synchronized void input(Map<String, Object> e) throws IOException {
        if (robot == null) throw new IOException(unavailableReason);
        String kind = String.valueOf(e.getOrDefault("kind", ""));
        Rectangle b = bounds();
        if (e.get("x") instanceof Number nx && e.get("y") instanceof Number ny) {
            double x = clamp(nx.doubleValue()), y = clamp(ny.doubleValue());
            robot.mouseMove(b.x + (int) Math.round(x * (b.width - 1)), b.y + (int) Math.round(y * (b.height - 1)));
        }
        int mask = switch (String.valueOf(e.getOrDefault("button", "left"))) {
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
        switch (kind) {
            case "move" -> { }
            case "click" -> { robot.mousePress(mask); robot.mouseRelease(mask); }
            case "dblclick" -> {
                robot.mousePress(mask); robot.mouseRelease(mask);
                robot.delay(40);
                robot.mousePress(mask); robot.mouseRelease(mask);
            }
            case "down" -> robot.mousePress(mask);
            case "up" -> robot.mouseRelease(mask);
            case "scroll" -> {
                int amt = e.get("amount") instanceof Number n ? n.intValue() : 0;
                robot.mouseWheel(Math.max(-30, Math.min(30, amt)));
            }
            case "key" -> key(String.valueOf(e.getOrDefault("key", "")));
            case "type" -> {
                String t = String.valueOf(e.getOrDefault("text", ""));
                if (pasteMode) type(t);
                else if (winTyper == null || t.length() > 2000 || !winTyper.type(t)) typeKeys(t);
            }
            case "wakeDisplay" -> {
                // nudge: tiny mouse move + a harmless key, wakes a dimmed screen / screensaver
                java.awt.Point p = java.awt.MouseInfo.getPointerInfo() == null ? new java.awt.Point(b.x + 10, b.y + 10)
                        : java.awt.MouseInfo.getPointerInfo().getLocation();
                robot.mouseMove(p.x + 1, p.y + 1);
                robot.mouseMove(p.x, p.y);
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyRelease(KeyEvent.VK_SHIFT);
            }
            default -> throw new IOException("unknown input kind " + kind);
        }
    }

    private static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }

    /** Key chord like "ctrl+alt+t", "enter", "shift+tab", "f5", "a". */
    private void key(String chord) throws IOException {
        if (chord.length() > 40) throw new IOException("key chord too long");
        List<Integer> codes = new ArrayList<>();
        for (String part : chord.toLowerCase(Locale.ROOT).split("\\+")) codes.add(code(part.trim()));
        try {
            for (int c : codes) { robot.keyPress(c); if (codes.size() > 1) robot.delay(MOD_HOLD_MS); }
        } finally {
            for (int i = codes.size() - 1; i >= 0; i--) { robot.keyRelease(codes.get(i)); if (codes.size() > 1) robot.delay(MOD_HOLD_MS); }
        }
    }

    private int code(String k) throws IOException {
        switch (k) {
            case "ctrl", "control": return KeyEvent.VK_CONTROL;
            case "alt", "option": return KeyEvent.VK_ALT;
            case "shift": return KeyEvent.VK_SHIFT;
            case "meta", "cmd", "win", "super": return mac ? KeyEvent.VK_META : KeyEvent.VK_WINDOWS;
            case "enter", "return": return KeyEvent.VK_ENTER;
            case "esc", "escape": return KeyEvent.VK_ESCAPE;
            case "tab": return KeyEvent.VK_TAB;
            case "space": return KeyEvent.VK_SPACE;
            case "backspace": return KeyEvent.VK_BACK_SPACE;
            case "delete", "del": return KeyEvent.VK_DELETE;
            case "up": return KeyEvent.VK_UP;
            case "down": return KeyEvent.VK_DOWN;
            case "left": return KeyEvent.VK_LEFT;
            case "right": return KeyEvent.VK_RIGHT;
            case "home": return KeyEvent.VK_HOME;
            case "insert": return KeyEvent.VK_INSERT;
            case "capslock": return KeyEvent.VK_CAPS_LOCK;
            case "printscreen": return KeyEvent.VK_PRINTSCREEN;
            case "end": return KeyEvent.VK_END;
            case "pageup": return KeyEvent.VK_PAGE_UP;
            case "pagedown": return KeyEvent.VK_PAGE_DOWN;
            default:
        }
        if (k.matches("f([1-9]|1[0-2])")) return KeyEvent.VK_F1 + Integer.parseInt(k.substring(1)) - 1;
        if (k.length() == 1) {
            char c = Character.toUpperCase(k.charAt(0));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return c;
        }
        throw new IOException("unknown key '" + k + "'");
    }

    private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";
    // same positions as SHIFTED: '~' is shift+'`', '!' is shift+'1', ... '?' is shift+'/'
    private static final String UNSHIFTED = "`1234567890-=[]\\;',./";

    /**
     * Types text as real key presses (US layout), so it also works where paste is blocked, e.g. a
     * Linux lock screen. Characters with no key on a US keyboard are pasted one at a time.
     */
    private void typeKeys(String text) throws IOException {
        if (text.length() > 2000) throw new IOException("text too long");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int code;
            boolean shift = false;
            if (ch >= 'a' && ch <= 'z') code = KeyEvent.VK_A + (ch - 'a');
            else if (ch >= 'A' && ch <= 'Z') { code = KeyEvent.VK_A + (ch - 'A'); shift = true; }
            else if (ch >= '0' && ch <= '9') code = KeyEvent.VK_0 + (ch - '0');
            else if (ch == ' ') code = KeyEvent.VK_SPACE;
            else if (ch == '\n') code = KeyEvent.VK_ENTER;
            else if (ch == '\t') code = KeyEvent.VK_TAB;
            else if (SHIFTED.indexOf(ch) >= 0) { code = punct(UNSHIFTED.charAt(SHIFTED.indexOf(ch))); shift = true; }
            else if (UNSHIFTED.indexOf(ch) >= 0) code = punct(ch);
            else { // no US key: paste this whole run of such characters in one go
                int j = i + 1;
                while (j < text.length() && !usKey(text.charAt(j))) j++;
                type(text.substring(i, j));
                i = j - 1;
                continue;
            }
            if (shift) { robot.keyPress(KeyEvent.VK_SHIFT); robot.delay(MOD_HOLD_MS); }
            try {
                robot.keyPress(code);
                robot.keyRelease(code);
            } finally {
                if (shift) { robot.delay(MOD_HOLD_MS); robot.keyRelease(KeyEvent.VK_SHIFT); }
            }
            robot.delay(KEY_GAP_MS);
        }
    }

    private static boolean usKey(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == ' '
                || ch == '\n' || ch == '\t' || SHIFTED.indexOf(ch) >= 0 || UNSHIFTED.indexOf(ch) >= 0;
    }

    private static int punct(char c) {
        return switch (c) {
            case '`' -> KeyEvent.VK_BACK_QUOTE;
            case '-' -> KeyEvent.VK_MINUS;
            case '=' -> KeyEvent.VK_EQUALS;
            case '[' -> KeyEvent.VK_OPEN_BRACKET;
            case ']' -> KeyEvent.VK_CLOSE_BRACKET;
            case '\\' -> KeyEvent.VK_BACK_SLASH;
            case ';' -> KeyEvent.VK_SEMICOLON;
            case '\'' -> KeyEvent.VK_QUOTE;
            case ',' -> KeyEvent.VK_COMMA;
            case '.' -> KeyEvent.VK_PERIOD;
            case '/' -> KeyEvent.VK_SLASH;
            default -> KeyEvent.VK_0 + (c - '0');
        };
    }

    /**
     * Type arbitrary text (any language) by placing it on the clipboard and pasting. Robot can only press
     * keys that exist on the local keyboard layout, so this is the only reliable cross-platform approach.
     */
    private void type(String text) throws IOException {
        if (text.isEmpty()) return;
        if (text.length() > 2000) throw new IOException("text too long");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        robot.delay(30);
        key(mac ? "meta+v" : "ctrl+v");
        robot.delay(60); // apps read the clipboard asynchronously: don't let the next paste replace it first
    }
}
