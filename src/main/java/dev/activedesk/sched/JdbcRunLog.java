package dev.activedesk.sched;

import dev.activedesk.util.Log;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Run log over plain JDBC. Works with SQLite, H2 or Postgres: drop the driver jar into lib/ and set
 * store.jdbc.url (e.g. jdbc:sqlite:C:/Users/me/.activedesk/activedesk.db or jdbc:h2:~/.activedesk/runs).
 */
public final class JdbcRunLog implements RunLog {
    private final Connection conn;

    public JdbcRunLog(String url, String user, String password) throws SQLException {
        conn = user == null || user.isBlank()
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ad_task_runs ("
                    + "run_id VARCHAR(64) PRIMARY KEY, workflow_id VARCHAR(64), task_id VARCHAR(64), "
                    + "status VARCHAR(16), attempts INT, started_at BIGINT, finished_at BIGINT, "
                    + "output VARCHAR(4100), error VARCHAR(4100), run_trigger VARCHAR(64))");
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS ad_task_runs_task ON ad_task_runs (task_id, started_at)");
        } catch (SQLException ignored) {
            // index already exists / dialect without IF NOT EXISTS
        }
    }

    @Override
    public synchronized void record(TaskRun r) {
        String sql = "INSERT INTO ad_task_runs (run_id, workflow_id, task_id, status, attempts, started_at, "
                + "finished_at, output, error, run_trigger) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.runId());
            ps.setString(2, r.workflowId());
            ps.setString(3, r.taskId());
            ps.setString(4, r.status().name());
            ps.setInt(5, r.attempts());
            ps.setLong(6, r.startedAt());
            ps.setLong(7, r.finishedAt());
            ps.setString(8, r.output());
            ps.setString(9, r.error());
            ps.setString(10, r.trigger());
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.error("JDBC run log write failed", e);
        }
    }

    @Override
    public synchronized List<TaskRun> recent(int limit) {
        return query("SELECT * FROM ad_task_runs ORDER BY started_at DESC", null, limit);
    }

    @Override
    public synchronized List<TaskRun> recentFor(String taskId, int limit) {
        return query("SELECT * FROM ad_task_runs WHERE task_id = ? ORDER BY started_at DESC", taskId, limit);
    }

    private List<TaskRun> query(String sql, String arg, int limit) {
        List<TaskRun> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(Math.max(1, limit));
            if (arg != null) ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TaskRun(rs.getString("run_id"), rs.getString("workflow_id"), rs.getString("task_id"),
                            TaskRun.Status.valueOf(rs.getString("status")), rs.getInt("attempts"),
                            rs.getLong("started_at"), rs.getLong("finished_at"), rs.getString("output"),
                            rs.getString("error"), rs.getString("run_trigger")));
                }
            }
        } catch (SQLException e) {
            Log.error("JDBC run log query failed", e);
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
