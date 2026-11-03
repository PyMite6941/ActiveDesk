package dev.activedesk.sched;

import dev.activedesk.Config;
import java.time.Duration;

/**
 * The work a task performs. Implementations must be thread-safe (a single instance may run
 * concurrently in different workflows) and should honour {@link Context#timeout()} themselves
 * where they can (e.g. kill a child process), because a CompletableFuture timeout cannot
 * interrupt blocking I/O.
 *
 * Return a short human-readable output on success; throw on failure (the message becomes the run's error).
 */
@FunctionalInterface
public interface TaskAction {
    String run(Context ctx) throws Exception;

    /** Where actions can post a message into a chat room (used by the "chat" action and notifications). */
    @FunctionalInterface
    interface ChatSink {
        void post(String room, String text);
    }

    record Context(TaskDefinition task, String runId, int attempt, Duration timeout, Config config, ChatSink chat) {}
}
