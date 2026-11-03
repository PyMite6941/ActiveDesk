package dev.activedesk.sched;

import dev.activedesk.Config;
import dev.activedesk.util.Log;
import java.util.List;

/** Persistence for task execution history. Task *definitions* live in tasks.json; this stores runs. */
public interface RunLog extends AutoCloseable {
    void record(TaskRun run);

    /** Most recent first. */
    List<TaskRun> recent(int limit);

    /** Most recent first, only runs of one task. */
    List<TaskRun> recentFor(String taskId, int limit);

    @Override
    void close();

    /** Factory: JDBC (SQLite/H2/Postgres - driver jar in lib/) when store.jdbc.url is set, else JSON-lines file. */
    static RunLog open(Config config) {
        String url = config.get("store.jdbc.url").trim();
        if (!url.isEmpty()) {
            try {
                return new JdbcRunLog(url, config.get("store.jdbc.user"), config.get("store.jdbc.password"));
            } catch (Exception e) {
                Log.error("JDBC run log unavailable (" + url + "), falling back to file store", e);
            }
        }
        return new FileRunLog(config.home().resolve("runs.jsonl"));
    }
}
