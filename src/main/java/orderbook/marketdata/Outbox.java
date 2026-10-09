package orderbook.marketdata;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ordered, non-blocking delivery of stream frames to one client. At most one send is in flight and later frames queue
 * behind it. A client more than {@code maxQueued} frames behind has its backlog dropped and replaced by one fresh
 * snapshot, so a slow browser costs bounded memory and catches up in a single message.
 *
 * <p>{@link #enqueue} runs on the simulator thread; send completions arrive on transport threads.
 */
public final class Outbox {

    /** The underlying connection. {@code send} must call exactly one of its callbacks when the write finishes. */
    public interface Transport {
        void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure);

        void close();
    }

    private final Transport transport;
    private final int maxQueued;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean sending;
    private boolean closed;
    private long resyncs;

    public Outbox(Transport transport, int maxQueued) {
        this.transport = transport;
        this.maxQueued = maxQueued;
    }

    /** Queues {@code frame}, or, if the backlog is full, replaces the whole backlog with {@code snapshot.get()}. */
    public void enqueue(String frame, Supplier<String> snapshot) {
        synchronized (this) {
            if (closed) {
                return;
            }
            if (queue.size() >= maxQueued) {
                queue.clear();
                queue.add(snapshot.get());
                resyncs++;
            } else {
                queue.add(frame);
            }
        }
        drain();
    }

    private void drain() {
        String next;
        synchronized (this) {
            if (sending || closed || queue.isEmpty()) {
                return;
            }
            next = queue.poll();
            sending = true;
        }
        transport.send(next, this::sent, error -> close());
    }

    private void sent() {
        synchronized (this) {
            sending = false;
        }
        drain();
    }

    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
        }
        transport.close();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int queued() {
        return queue.size();
    }

    public synchronized long resyncs() {
        return resyncs;
    }
}
