package dev.activedesk.sched;

import dev.activedesk.sched.actions.BackupAction;
import dev.activedesk.sched.actions.ChatAction;
import dev.activedesk.sched.actions.EmailAction;
import dev.activedesk.sched.actions.HttpAction;
import dev.activedesk.sched.actions.ShellAction;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Factory pattern: task "type" string -> shared, stateless action instance. */
public final class TaskActionFactory {
    private static final Map<String, Supplier<TaskAction>> REGISTRY = new ConcurrentHashMap<>(Map.of(
            "shell", ShellAction::new,
            "http", HttpAction::new,
            "backup", BackupAction::new,
            "email", EmailAction::new,
            "chat", ChatAction::new));
    private static final Map<String, TaskAction> INSTANCES = new ConcurrentHashMap<>();

    private TaskActionFactory() {}

    public static TaskAction forType(String type) {
        Supplier<TaskAction> s = REGISTRY.get(type);
        if (s == null) throw new IllegalArgumentException("unknown task type '" + type + "', known: " + REGISTRY.keySet());
        return INSTANCES.computeIfAbsent(type, t -> s.get());
    }

    /** Extension point for plugins. */
    public static void register(String type, Supplier<TaskAction> supplier) {
        REGISTRY.put(type, supplier);
        INSTANCES.remove(type);
    }

    public static Set<String> types() { return REGISTRY.keySet(); }
}
