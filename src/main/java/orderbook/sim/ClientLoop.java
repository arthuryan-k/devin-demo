package orderbook.sim;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Where one simulated client runs its API calls: its own thread (a real concurrent client) or inline on the
 * caller's thread (single-threaded browser build, deterministic tests). Persona state is confined to it.
 */
public interface ClientLoop {

    /** Creates the loop for a newly spawned participant. */
    interface Factory {
        ClientLoop create(String name);
    }

    Factory INLINE = name -> new Inline();

    /** One daemon thread per participant. */
    static Factory dedicatedThreads() {
        return Dedicated::new;
    }

    /** Runs a turn unless the previous one is still in flight; returns whether it was accepted. */
    boolean tryDispatch(Runnable turn);

    void post(Runnable task);

    <T> T call(Supplier<T> task);

    /** Runs {@code last} after everything already queued, then stops. */
    void close(Runnable last);

    boolean awaitClosed(long millis) throws InterruptedException;

    final class Inline implements ClientLoop {
        @Override
        public boolean tryDispatch(Runnable turn) {
            turn.run();
            return true;
        }

        @Override
        public void post(Runnable task) {
            task.run();
        }

        @Override
        public <T> T call(Supplier<T> task) {
            return task.get();
        }

        @Override
        public void close(Runnable last) {
            last.run();
        }

        @Override
        public boolean awaitClosed(long millis) {
            return true;
        }
    }

    final class Dedicated implements ClientLoop {
        private final ExecutorService executor;
        private final AtomicBoolean busy = new AtomicBoolean();

        Dedicated(String name) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "client-" + name);
                t.setDaemon(true);
                return t;
            });
        }

        @Override
        public boolean tryDispatch(Runnable turn) {
            if (!busy.compareAndSet(false, true)) {
                return false;
            }
            try {
                executor.execute(() -> {
                    try {
                        turn.run();
                    } finally {
                        busy.set(false);
                    }
                });
                return true;
            } catch (RejectedExecutionException e) {
                busy.set(false);
                return false;
            }
        }

        @Override
        public void post(Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // closed: the participant has left
            }
        }

        @Override
        public <T> T call(Supplier<T> task) {
            try {
                return executor.submit(task::get).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(e.getCause());
            }
        }

        @Override
        public void close(Runnable last) {
            post(last);
            executor.shutdown();
        }

        @Override
        public boolean awaitClosed(long millis) throws InterruptedException {
            return executor.awaitTermination(millis, TimeUnit.MILLISECONDS);
        }
    }
}
