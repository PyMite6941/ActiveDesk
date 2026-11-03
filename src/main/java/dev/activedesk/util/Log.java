package dev.activedesk.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Tiny logger: console + ~/.activedesk/activedesk.log. */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile Path file;
    private static volatile boolean quiet;

    private Log() {}

    public static void init(Path logFile, boolean quietConsole) {
        file = logFile;
        quiet = quietConsole;
    }

    public static void info(String msg) { emit("INFO", msg, null); }
    public static void warn(String msg) { emit("WARN", msg, null); }
    public static void error(String msg, Throwable t) { emit("ERROR", msg, t); }
    public static void debug(String msg) { if (Boolean.getBoolean("activedesk.debug")) emit("DEBUG", msg, null); }

    private static synchronized void emit(String level, String msg, Throwable t) {
        String line = TS.format(LocalDateTime.now()) + " " + level + " " + msg;
        if (t != null) line += " :: " + t;
        if (!quiet || !"INFO".equals(level)) System.err.println(line);
        Path f = file;
        if (f == null) return;
        try {
            String body = line + System.lineSeparator();
            if (t != null && Boolean.getBoolean("activedesk.debug")) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                body += sw;
            }
            Files.writeString(f, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never take the node down
        }
    }
}
