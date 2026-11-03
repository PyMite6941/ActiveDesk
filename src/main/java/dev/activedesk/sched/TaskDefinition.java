package dev.activedesk.sched;

import dev.activedesk.util.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable task definition. Build with {@link #builder(String)} (Builder pattern). */
public final class TaskDefinition {
    private final String id;
    private final String name;
    private final String type;
    private final Map<String, String> params;
    private final Schedule schedule;
    private final List<String> dependsOn;
    private final int retries;
    private final Duration retryDelay;
    private final Duration timeout;
    private final boolean enabled;
    private final String node;

    private TaskDefinition(Builder b) {
        this.id = b.id;
        this.name = b.name == null ? b.id : b.name;
        this.type = b.type;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(b.params));
        this.schedule = b.schedule;
        this.dependsOn = List.copyOf(b.dependsOn);
        this.retries = b.retries;
        this.retryDelay = b.retryDelay;
        this.timeout = b.timeout;
        this.enabled = b.enabled;
        this.node = b.node;
    }

    public static Builder builder(String id) { return new Builder(id); }

    public Builder toBuilder() {
        Builder b = new Builder(id).name(name).type(type).schedule(schedule).retries(retries)
                .retryDelay(retryDelay).timeout(timeout).enabled(enabled).node(node);
        params.forEach(b::param);
        dependsOn.forEach(b::dependsOn);
        return b;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String type() { return type; }
    public Map<String, String> params() { return params; }
    public String param(String k) { return params.get(k); }
    public String param(String k, String def) { return params.getOrDefault(k, def); }
    public Schedule schedule() { return schedule; }
    public List<String> dependsOn() { return dependsOn; }
    public int retries() { return retries; }
    public Duration retryDelay() { return retryDelay; }
    public Duration timeout() { return timeout; }
    public boolean enabled() { return enabled; }
    /** Device name this task is pinned to; blank means "any device that loads this file". */
    public String node() { return node; }

    public Map<String, Object> toJson() {
        return Json.o("id", id, "name", name, "type", type, "schedule", schedule.toString(),
                "dependsOn", dependsOn, "retries", retries, "retryDelay", fmt(retryDelay),
                "timeout", fmt(timeout), "enabled", enabled, "node", node, "params", params);
    }

    private static String fmt(Duration d) { return d.toSeconds() + "s"; }

    public static TaskDefinition fromJson(Map<String, Object> m) {
        Builder b = builder(Json.str(m, "id", null))
                .name(Json.str(m, "name", null))
                .type(Json.str(m, "type", null))
                .schedule(Schedule.parse(Json.str(m, "schedule", "manual")))
                .retries((int) Json.num(m, "retries", 0))
                .retryDelay(Schedule.parseDuration(Json.str(m, "retryDelay", "10s")))
                .timeout(Schedule.parseDuration(Json.str(m, "timeout", "5m")))
                .enabled(Json.bool(m, "enabled", true))
                .node(Json.str(m, "node", ""));
        for (Object d : Json.list(m, "dependsOn")) b.dependsOn(String.valueOf(d));
        Json.map(m, "params").forEach((k, v) -> b.param(k, v == null ? "" : String.valueOf(v)));
        return b.build();
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String type;
        private final Map<String, String> params = new LinkedHashMap<>();
        private Schedule schedule = Schedule.manual();
        private final List<String> dependsOn = new ArrayList<>();
        private int retries = 0;
        private Duration retryDelay = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofMinutes(5);
        private boolean enabled = true;
        private String node = "";

        private Builder(String id) { this.id = id; }

        public Builder name(String v) { name = v; return this; }
        public Builder type(String v) { type = v; return this; }
        public Builder param(String k, String v) { params.put(k, v); return this; }
        public Builder schedule(Schedule v) { schedule = v; return this; }
        public Builder schedule(String v) { schedule = Schedule.parse(v); return this; }
        public Builder dependsOn(String v) { if (!dependsOn.contains(v)) dependsOn.add(v); return this; }
        public Builder retries(int v) { retries = v; return this; }
        public Builder retryDelay(Duration v) { retryDelay = v; return this; }
        public Builder timeout(Duration v) { timeout = v; return this; }
        public Builder enabled(boolean v) { enabled = v; return this; }
        public Builder node(String v) { node = v == null ? "" : v; return this; }

        public TaskDefinition build() {
            if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}"))
                throw new IllegalArgumentException("task id must match [A-Za-z0-9_.-]{1,64}: " + id);
            if (type == null || type.isBlank()) throw new IllegalArgumentException("task " + id + " needs a type");
            if (retries < 0 || retries > 20) throw new IllegalArgumentException("retries must be 0..20");
            if (dependsOn.contains(id)) throw new IllegalArgumentException("task " + id + " depends on itself");
            if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be > 0");
            return new TaskDefinition(this);
        }
    }
}
