package orderbook.sim;

import java.util.function.Supplier;

/**
 * The serial loop the simulator runs on: tasks never overlap, and delayed tasks run on the same loop. On the JVM this
 * is one thread ({@link ExecutorScheduler}); in the browser it is the page's event loop.
 */
public interface Scheduler {

    /** A delayed task that has not run yet. */
    interface Task {
        void cancel();
    }

    /** Runs {@code task} on the loop (inline when already on it) and returns its result. */
    <T> T execute(Supplier<T> task);

    /** Runs {@code task} on the loop after {@code delayNanos}. */
    Task schedule(Runnable task, long delayNanos);

    boolean onLoop();

    boolean isClosed();

    void close();
}
