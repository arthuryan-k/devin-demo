package orderbook.demo;

import java.util.function.Supplier;

import org.teavm.jso.browser.Window;

import orderbook.sim.Scheduler;

/** The browser's event loop as a {@link Scheduler}: JavaScript is single-threaded, so tasks run inline. */
final class BrowserScheduler implements Scheduler {

    private boolean closed;

    @Override
    public <T> T execute(Supplier<T> task) {
        return task.get();
    }

    @Override
    public Task schedule(Runnable task, long delayNanos) {
        int id = Window.setTimeout(() -> {
            if (!closed) {
                task.run();
            }
        }, delayNanos / 1e6);
        return () -> Window.clearTimeout(id);
    }

    @Override
    public boolean onLoop() {
        return true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
