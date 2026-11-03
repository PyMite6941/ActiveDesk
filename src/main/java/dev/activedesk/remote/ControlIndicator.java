package dev.activedesk.remote;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * A small always-on-top red banner shown on the controlled machine while anyone is viewing or
 * controlling it, so remote access is never silent. Hidden a few seconds after the last viewer leaves.
 */
public final class ControlIndicator {
    private final boolean enabled;
    private JWindow window;
    private JLabel label;
    private volatile long lastSeen;
    private volatile String text = "";

    public ControlIndicator(boolean enabled) {
        this.enabled = enabled && !GraphicsEnvironment.isHeadless();
    }

    /** Call on every screen/input request. */
    public void touch(String who, boolean controlling) {
        if (!enabled) return;
        lastSeen = System.currentTimeMillis();
        String t = (controlling ? "● Remote control: " : "● Screen shared with: ") + who
                + "   (run 'activedesk lock' to end)";
        if (!t.equals(text) || window == null || !window.isVisible()) {
            text = t;
            SwingUtilities.invokeLater(this::show);
        }
    }

    /** Called periodically: hide once no request arrived for 5 s. */
    public void tick() {
        if (!enabled || window == null) return;
        if (System.currentTimeMillis() - lastSeen > 5000) SwingUtilities.invokeLater(() -> window.setVisible(false));
    }

    public void hideNow() {
        lastSeen = 0;
        if (enabled && window != null) SwingUtilities.invokeLater(() -> window.setVisible(false));
    }

    private void show() {
        if (window == null) {
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            label = new JLabel();
            label.setOpaque(true);
            label.setBackground(new Color(200, 30, 30));
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
            label.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
            window.add(label);
        }
        label.setText(text);
        window.pack();
        Rectangle s = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setLocation(s.x + (s.width - window.getWidth()) / 2, s.y);
        window.setVisible(true);
    }
}
