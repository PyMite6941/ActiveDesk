package dev.activedesk.sched;

import dev.activedesk.util.Json;
import java.util.Map;

/** One attempt-chain of one task inside one workflow trigger. Persisted by a {@link RunLog}. */
public record TaskRun(String runId, String workflowId, String taskId, Status status, int attempts,
                      long startedAt, long finishedAt, String output, String error, String trigger) {

    public enum Status { SUCCEEDED, FAILED, TIMED_OUT, SKIPPED }

    public static final int MAX_TEXT = 4000;

    public TaskRun {
        output = clip(output);
        error = clip(error);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "\n...[truncated]";
    }

    public long durationMs() { return Math.max(0, finishedAt - startedAt); }

    public Map<String, Object> toJson() {
        return Json.o("runId", runId, "workflowId", workflowId, "taskId", taskId, "status", status.name(),
                "attempts", attempts, "startedAt", startedAt, "finishedAt", finishedAt,
                "output", output, "error", error, "trigger", trigger);
    }

    public static TaskRun fromJson(Map<String, Object> m) {
        return new TaskRun(Json.str(m, "runId", ""), Json.str(m, "workflowId", ""), Json.str(m, "taskId", ""),
                Status.valueOf(Json.str(m, "status", "FAILED")), (int) Json.num(m, "attempts", 0),
                Json.num(m, "startedAt", 0), Json.num(m, "finishedAt", 0),
                Json.str(m, "output", ""), Json.str(m, "error", ""), Json.str(m, "trigger", ""));
    }
}
