package dev.activedesk.sched;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * When a task fires on its own. Text forms (as written in tasks.json):
 *   "manual"          only when triggered (web UI, CLI, or as a dependency)
 *   "startup"         once, when the node starts
 *   "every 30s|15m|2h|1d"
 *   "daily 09:30"     every day at local time
 */
public final class Schedule {
    public enum Kind { MANUAL, STARTUP, EVERY, DAILY }

    private final Kind kind;
    private final Duration every;
    private final LocalTime at;
    private final String text;

    private Schedule(Kind kind, Duration every, LocalTime at, String text) {
        this.kind = kind;
        this.every = every;
        this.at = at;
        this.text = text;
    }

    public static Schedule manual() { return new Schedule(Kind.MANUAL, null, null, "manual"); }

    public static Schedule parse(String s) {
        if (s == null || s.isBlank()) return manual();
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.equals("manual")) return manual();
        if (t.equals("startup")) return new Schedule(Kind.STARTUP, null, null, "startup");
        if (t.startsWith("every ")) {
            Duration d = parseDuration(t.substring(6).trim());
            if (d.compareTo(Duration.ofSeconds(5)) < 0) throw new IllegalArgumentException("interval must be >= 5s: " + s);
            return new Schedule(Kind.EVERY, d, null, t);
        }
        if (t.startsWith("daily ")) {
            return new Schedule(Kind.DAILY, null, LocalTime.parse(t.substring(6).trim()), t);
        }
        throw new IllegalArgumentException("bad schedule '" + s + "' (use manual | startup | every 15m | daily 09:30)");
    }

    public static Duration parseDuration(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) throw new IllegalArgumentException("empty duration");
        char unit = t.charAt(t.length() - 1);
        long n = Long.parseLong(Character.isDigit(unit) ? t : t.substring(0, t.length() - 1).trim());
        return switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> Character.isDigit(unit) ? Duration.ofSeconds(n) : badUnit(s);
        };
    }

    private static Duration badUnit(String s) { throw new IllegalArgumentException("bad duration " + s); }

    /** Next fire time strictly after {@code after}, or null if the schedule never fires by itself. */
    public ZonedDateTime nextAfter(ZonedDateTime after, ZonedDateTime lastRun) {
        return switch (kind) {
            case MANUAL, STARTUP -> null;
            case EVERY -> lastRun == null ? after : lastRun.plus(every);
            case DAILY -> {
                ZonedDateTime today = ZonedDateTime.of(after.toLocalDate(), at, after.getZone());
                yield today.isAfter(after) ? today
                        : ZonedDateTime.of(after.toLocalDate().plusDays(1), at, after.getZone());
            }
        };
    }

    /** First fire time for a fresh process: EVERY fires immediately-ish, DAILY at the next wall-clock slot. */
    public ZonedDateTime first(ZonedDateTime now) {
        return switch (kind) {
            case MANUAL, STARTUP -> null;
            case EVERY -> now.plusSeconds(2);
            case DAILY -> nextAfter(now, null);
        };
    }

    public Kind kind() { return kind; }
    public boolean isStartup() { return kind == Kind.STARTUP; }
    @Override public String toString() { return text; }

    static ZonedDateTime now() { return ZonedDateTime.now(ZoneId.systemDefault()); }

    @SuppressWarnings("unused")
    private static LocalDate today() { return LocalDate.now(); }
}
