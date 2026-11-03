package dev.activedesk.sched;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton owner of every thread pool in the process, so pool sizing lives in one place and
 * shutdown is one call. Pools:
 *   ticker  - 1 thread, the scheduler clock and periodic housekeeping (discovery, session expiry)
 *   workers - bounded ThreadPoolExecutor that runs workflow steps
 *   io      - cached pool for blocking work (task attempts that may be interrupted, file sends, CLI calls)
 */
public final class ThreadPools {
    private static final ThreadPools INSTANCE = new ThreadPools();

    public static ThreadPools get() { return INSTANCE; }

    private final ScheduledExecutorService ticker;
    private final ThreadPoolExecutor workers;
    private final ExecutorService io;

    private ThreadPools() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        ticker = Executors.newSingleThreadScheduledExecutor(named("ad-ticker"));
        workers = new ThreadPoolExecutor(cores, cores * 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000), named("ad-worker"), new ThreadPoolExecutor.CallerRunsPolicy());
        workers.allowCoreThreadTimeOut(true);
        io = Executors.newCachedThreadPool(named("ad-io"));
    }

    public ScheduledExecutorService ticker() { return ticker; }
    public ThreadPoolExecutor workers() { return workers; }
    public ExecutorService io() { return io; }

    public void shutdown() {
        ticker.shutdownNow();
        workers.shutdownNow();
        io.shutdownNow();
    }

    /** Factory for named daemon threads (daemon so a stuck task never blocks JVM exit). */
    public static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
