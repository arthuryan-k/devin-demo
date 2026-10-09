package orderbook.sim;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** {@link Scheduler} backed by a single daemon thread. */
public final class ExecutorScheduler implements Scheduler {

    private final ScheduledExecutorService executor;
    private volatile Thread thread;

    public ExecutorScheduler(String threadName) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            thread = t;
            return t;
        });
    }

    @Override
    public <T> T execute(Supplier<T> task) {
        if (onLoop()) {
            return task.get();
        }
        Future<T> future = executor.submit(task::get);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Override
    public Task schedule(Runnable task, long delayNanos) {
        ScheduledFuture<?> future = executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public boolean onLoop() {
        return Thread.currentThread() == thread;
    }

    @Override
    public boolean isClosed() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
