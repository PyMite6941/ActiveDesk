package dev.activedesk.sched;

import dev.activedesk.util.Json;
import dev.activedesk.util.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Default run log: append-only JSON lines file plus an in-memory ring of the last 500 runs. */
public final class FileRunLog implements RunLog {
    private static final int RING = 500;
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final Path file;
    private final ArrayDeque<TaskRun> ring = new ArrayDeque<>();

    public FileRunLog(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = Math.max(0, lines.size() - RING); i < lines.size(); i++) {
                    String l = lines.get(i).trim();
                    if (l.isEmpty()) continue;
                    try {
                        push(TaskRun.fromJson(Json.obj(l)));
                    } catch (RuntimeException ignored) {
                        // skip malformed line
                    }
                }
            }
        } catch (IOException e) {
            Log.error("could not read run log " + file, e);
        }
    }

    private void push(TaskRun r) {
        ring.addLast(r);
        while (ring.size() > RING) ring.removeFirst();
    }

    @Override
    public synchronized void record(TaskRun run) {
        push(run);
        try {
            Files.writeString(file, Json.write(run.toJson()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(file) > MAX_BYTES) compact();
        } catch (IOException e) {
            Log.error("could not write run log", e);
        }
    }

    private void compact() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        StringBuilder sb = new StringBuilder();
        for (TaskRun r : ring) sb.append(Json.write(r.toJson())).append('\n');
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public synchronized List<TaskRun> recent(int limit) {
        return collect(null, limit);
    }

    @Override
    public synchronized List<TaskRun> recentFor(String taskId, int limit) {
        return collect(taskId, limit);
    }

    private List<TaskRun> collect(String taskId, int limit) {
        List<TaskRun> out = new ArrayList<>();
        Iterator<TaskRun> it = ring.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            TaskRun r = it.next();
            if (taskId == null || taskId.equals(r.taskId())) out.add(r);
        }
        return out;
    }

    @Override
    public void close() {}
}
