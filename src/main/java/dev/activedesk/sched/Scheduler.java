package dev.activedesk.sched;

import dev.activedesk.Config;
import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Workflow scheduler.
 *
 * Producer-consumer: the ticker thread (producer) and manual triggers put {@link Trigger}s on a bounded
 * BlockingQueue; dispatcher threads (consumers) take them and start a workflow. A workflow is the
 * requested task plus its transitive dependencies, wired as a CompletableFuture DAG: each step starts
 * when all of its dependencies finished, and is SKIPPED if any dependency did not succeed.
 * Each attempt runs on the io pool and is cancelled (interrupted / process killed) at its timeout.
 */
public final class Scheduler implements AutoCloseable {

    public record Trigger(String taskId, String reason) {}

    private final Config config;
    private final Path tasksFile;
    private final RunLog runLog;
    private final TaskAction.ChatSink chat;
    private final ThreadPools pools = ThreadPools.get();
    private final BlockingQueue<Trigger> queue = new LinkedBlockingQueue<>(256);
    private final Map<String, ZonedDateTime> nextFire = new ConcurrentHashMap<>();
    private final Set<String> activeRoots = ConcurrentHashMap.newKeySet();
    private final List<Thread> dispatchers = new ArrayList<>();
    private volatile Map<String, TaskDefinition> tasks = Map.of();
    private volatile Map<String, String> invalid = Map.of();
    private volatile long tasksMtime = -1;
    private volatile boolean running;

    public Scheduler(Config config, RunLog runLog, TaskAction.ChatSink chat) {
        this.config = config;
        this.tasksFile = config.home().resolve("tasks.json");
        this.runLog = runLog;
        this.chat = chat;
    }

    public void start() {
        seedTasksFile();
        reloadIfChanged();
        running = true;
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(this::dispatchLoop, "ad-dispatch-" + i);
            t.setDaemon(true);
            t.start();
            dispatchers.add(t);
        }
        pools.ticker().scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        for (TaskDefinition t : tasks.values()) {
            if (t.enabled() && local(t) && t.schedule().isStartup()) trigger(t.id(), "startup");
        }
        Log.info("scheduler: " + tasks.size() + " task(s) loaded from " + tasksFile);
    }

    // ---------- producer side ----------

    private void tick() {
        try {
            reloadIfChanged();
            ZonedDateTime now = Schedule.now();
            for (TaskDefinition t : tasks.values()) {
                if (!t.enabled() || !local(t)) continue;
                ZonedDateTime due = nextFire.computeIfAbsent(t.id(), k -> orFar(t.schedule().first(now)));
                if (!now.isBefore(due)) {
                    nextFire.put(t.id(), orFar(t.schedule().nextAfter(now, now)));
                    trigger(t.id(), "schedule");
                }
            }
        } catch (RuntimeException e) {
            Log.error("scheduler tick failed", e);
        }
    }

    private static ZonedDateTime orFar(ZonedDateTime z) { return z == null ? ZonedDateTime.now().plusYears(100) : z; }

    /** Enqueue a workflow run. Returns false if the task is unknown or the queue is full. */
    public boolean trigger(String taskId, String reason) {
        if (!tasks.containsKey(taskId)) return false;
        boolean ok = queue.offer(new Trigger(taskId, reason));
        if (!ok) Log.warn("scheduler queue full, dropped trigger for " + taskId);
        return ok;
    }

    // ---------- consumer side ----------

    private void dispatchLoop() {
        while (running) {
            try {
                Trigger t = queue.take();
                if (!activeRoots.add(t.taskId())) {
                    Log.info("task " + t.taskId() + " is still running; skipping overlapping trigger (" + t.reason() + ")");
                    continue;
                }
                runWorkflow(t).whenComplete((r, e) -> activeRoots.remove(t.taskId()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                Log.error("dispatcher error", e);
            }
        }
    }

    /** Builds and starts the dependency DAG for one trigger. Completes with the root task's run. */
    public CompletableFuture<TaskRun> runWorkflow(Trigger trigger) {
        String workflowId = UUID.randomUUID().toString().substring(0, 8);
        Map<String, TaskDefinition> snapshot = tasks;
        Map<String, CompletableFuture<TaskRun>> memo = new HashMap<>();
        return step(trigger.taskId(), snapshot, memo, workflowId, trigger.reason());
    }

    private CompletableFuture<TaskRun> step(String id, Map<String, TaskDefinition> snap,
                                            Map<String, CompletableFuture<TaskRun>> memo, String wf, String reason) {
        CompletableFuture<TaskRun> existing = memo.get(id);
        if (existing != null) return existing; // diamond dependencies run once per workflow
        TaskDefinition def = snap.get(id);
        List<CompletableFuture<TaskRun>> deps = new ArrayList<>();
        if (def != null) for (String d : def.dependsOn()) deps.add(step(d, snap, memo, wf, reason));

        CompletableFuture<TaskRun> f = CompletableFuture.allOf(deps.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    long now = System.currentTimeMillis();
                    if (def == null) return finish(skipped(wf, id, now, "unknown task", reason));
                    if (!local(def)) return finish(skipped(wf, id, now, "pinned to device " + def.node(), reason));
                    for (CompletableFuture<TaskRun> d : deps) {
                        TaskRun dr = d.join();
                        if (dr.status() != TaskRun.Status.SUCCEEDED)
                            return finish(skipped(wf, id, now, "dependency " + dr.taskId() + " " + dr.status(), reason));
                    }
                    return finish(execute(def, wf, reason));
                }, pools.workers());
        memo.put(id, f);
        return f;
    }

    private TaskRun skipped(String wf, String id, long now, String why, String reason) {
        return new TaskRun(newRunId(), wf, id, TaskRun.Status.SKIPPED, 0, now, now, "", why, reason);
    }

    private TaskRun finish(TaskRun r) {
        runLog.record(r);
        String msg = "task " + r.taskId() + " " + r.status() + " in " + r.durationMs() + "ms"
                + (r.error().isEmpty() ? "" : " - " + firstLine(r.error()));
        if (r.status() == TaskRun.Status.SUCCEEDED) Log.info(msg); else Log.warn(msg);
        return r;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Runs one task with retries. Each attempt is cancelled at the task timeout. */
    private TaskRun execute(TaskDefinition def, String wf, String reason) {
        String runId = newRunId();
        long start = System.currentTimeMillis();
        TaskAction action;
        try {
            action = TaskActionFactory.forType(def.type());
        } catch (IllegalArgumentException e) {
            return new TaskRun(runId, wf, def.id(), TaskRun.Status.FAILED, 0, start, System.currentTimeMillis(), "", e.getMessage(), reason);
        }
        String lastError = "";
        TaskRun.Status lastStatus = TaskRun.Status.FAILED;
        int attempt = 0;
        while (attempt <= def.retries()) {
            attempt++;
            TaskAction.Context ctx = new TaskAction.Context(def, runId, attempt, def.timeout(), config, chat);
            Future<String> fut = pools.io().submit(() -> action.run(ctx));
            try {
                String out = fut.get(def.timeout().toMillis() + 2000, TimeUnit.MILLISECONDS);
                return new TaskRun(runId, wf, def.id(), TaskRun.Status.SUCCEEDED, attempt, start,
                        System.currentTimeMillis(), out, "", reason);
            } catch (TimeoutException e) {
                fut.cancel(true);
                lastStatus = TaskRun.Status.TIMED_OUT;
                lastError = "timed out after " + def.timeout().toSeconds() + "s";
            } catch (ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                lastStatus = c instanceof TimeoutException ? TaskRun.Status.TIMED_OUT : TaskRun.Status.FAILED;
                lastError = c.getClass().getSimpleName() + ": " + c.getMessage();
            } catch (InterruptedException e) {
                fut.cancel(true);
                Thread.currentThread().interrupt();
                return new TaskRun(runId, wf, def.id(), TaskRun.Status.FAILED, attempt, start,
                        System.currentTimeMillis(), "", "interrupted (shutdown)", reason);
            }
            if (attempt <= def.retries()) {
                long backoff = def.retryDelay().toMillis() * (1L << Math.min(5, attempt - 1)); // exponential
                Log.warn("task " + def.id() + " attempt " + attempt + " failed (" + lastError + "), retrying in " + backoff + "ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new TaskRun(runId, wf, def.id(), lastStatus, attempt, start, System.currentTimeMillis(), "", lastError, reason);
    }

    private static String newRunId() { return UUID.randomUUID().toString(); }

    private boolean local(TaskDefinition t) {
        return t.node().isBlank() || t.node().equalsIgnoreCase(config.deviceName());
    }

    // ---------- tasks.json ----------

    private void seedTasksFile() {
        if (Files.exists(tasksFile)) return;
        TaskDefinition hello = TaskDefinition.builder("hello")
                .name("Say hello").type("shell").param("command", "echo Hello from ActiveDesk").build();
        TaskDefinition announce = TaskDefinition.builder("announce")
                .name("Announce in #general").type("chat").param("room", "general")
                .param("text", "hello task finished").dependsOn("hello").build();
        try {
            writeTasks(List.of(hello, announce));
        } catch (IOException e) {
            Log.error("could not create " + tasksFile, e);
        }
    }

    private void writeTasks(List<TaskDefinition> list) throws IOException {
        List<Object> arr = new ArrayList<>();
        for (TaskDefinition t : list) arr.add(t.toJson());
        Files.writeString(tasksFile, Json.pretty(Json.o("tasks", arr)) + "\n", StandardCharsets.UTF_8);
    }

    /** Hot reload: edit tasks.json and the scheduler picks it up within a second. */
    public synchronized void reloadIfChanged() {
        try {
            if (!Files.exists(tasksFile)) return;
            long m = Files.getLastModifiedTime(tasksFile).toMillis();
            if (m == tasksMtime) return;
            tasksMtime = m;
            Map<String, Object> root = Json.obj(Files.readString(tasksFile, StandardCharsets.UTF_8));
            Map<String, TaskDefinition> loaded = new LinkedHashMap<>();
            Map<String, String> bad = new LinkedHashMap<>();
            for (Object o : Json.list(root, "tasks")) {
                if (!(o instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m2 = (Map<String, Object>) o;
                try {
                    TaskDefinition t = TaskDefinition.fromJson(m2);
                    TaskActionFactory.forType(t.type()); // validate type early
                    if (loaded.put(t.id(), t) != null) bad.put(t.id(), "duplicate id");
                } catch (RuntimeException e) {
                    bad.put(Json.str(m2, "id", "?"), e.getMessage());
                }
            }
            // remove tasks with missing deps or cycles (repeat until stable: removal can orphan others)
            boolean changed = true;
            while (changed) {
                changed = false;
                for (TaskDefinition t : List.copyOf(loaded.values())) {
                    for (String d : t.dependsOn()) {
                        if (!loaded.containsKey(d)) {
                            bad.put(t.id(), "missing dependency " + d);
                            loaded.remove(t.id());
                            changed = true;
                            break;
                        }
                    }
                }
            }
            for (String id : findCycle(loaded)) {
                bad.put(id, "dependency cycle");
                loaded.remove(id);
            }
            tasks = Map.copyOf(loaded);
            invalid = Map.copyOf(bad);
            nextFire.keySet().retainAll(loaded.keySet());
            nextFire.clear(); // schedules may have changed
            bad.forEach((k, v) -> Log.warn("tasks.json: task '" + k + "' ignored: " + v));
            if (running) Log.info("scheduler: reloaded " + loaded.size() + " task(s)");
        } catch (IOException | RuntimeException e) {
            Log.error("could not load " + tasksFile, e);
        }
    }

    /** Returns ids of every task that sits on a dependency cycle (DFS with colours). */
    static Set<String> findCycle(Map<String, TaskDefinition> g) {
        Set<String> onCycle = new HashSet<>();
        Map<String, Integer> colour = new HashMap<>();
        for (String id : g.keySet()) dfs(id, g, colour, new ArrayList<>(), onCycle);
        return onCycle;
    }

    private static void dfs(String id, Map<String, TaskDefinition> g, Map<String, Integer> colour,
                            List<String> stack, Set<String> onCycle) {
        Integer c = colour.get(id);
        if (c != null && c == 2) return;
        if (c != null && c == 1) {
            int i = stack.indexOf(id);
            if (i >= 0) onCycle.addAll(stack.subList(i, stack.size()));
            return;
        }
        colour.put(id, 1);
        stack.add(id);
        TaskDefinition t = g.get(id);
        if (t != null) for (String d : t.dependsOn()) dfs(d, g, colour, stack, onCycle);
        stack.remove(stack.size() - 1);
        colour.put(id, 2);
    }

    // ---------- queries for UI ----------

    public Map<String, TaskDefinition> tasks() { return tasks; }
    public Map<String, String> invalidTasks() { return invalid; }
    public RunLog runLog() { return runLog; }
    public Path tasksFile() { return tasksFile; }
    public boolean isRunning(String id) { return activeRoots.contains(id); }
    public ZonedDateTime nextFire(String id) {
        ZonedDateTime z = nextFire.get(id);
        return z == null || z.getYear() > ZonedDateTime.now().getYear() + 50 ? null : z;
    }

    /** Enable/disable a task by rewriting tasks.json (the file stays the single source of truth). */
    public synchronized boolean setEnabled(String id, boolean enabled) throws IOException {
        TaskDefinition t = tasks.get(id);
        if (t == null) return false;
        Map<String, Object> root = Json.obj(Files.readString(tasksFile, StandardCharsets.UTF_8));
        for (Object o : Json.list(root, "tasks")) {
            if (o instanceof Map<?, ?> m && id.equals(String.valueOf(m.get("id")))) {
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
                mm.put("enabled", enabled);
            }
        }
        Files.writeString(tasksFile, Json.pretty(root) + "\n", StandardCharsets.UTF_8);
        tasksMtime = -1;
        reloadIfChanged();
        return true;
    }

    @Override
    public void close() {
        running = false;
        dispatchers.forEach(Thread::interrupt);
        runLog.close();
    }
}
