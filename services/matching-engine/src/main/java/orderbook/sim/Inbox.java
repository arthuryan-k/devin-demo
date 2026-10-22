package orderbook.sim;

import java.util.ArrayDeque;

import orderbook.pipeline.Result;

/** Results handed from the output-ring consumer thread to the simulator thread, in sequence order. */
final class Inbox {

    private final ArrayDeque<Result> queue = new ArrayDeque<>();

    synchronized void add(Result result) {
        queue.addLast(result);
        notifyAll();
    }

    synchronized Result poll() {
        return queue.pollFirst();
    }

    /** Waits up to {@code timeoutMillis} for the next result; null on timeout. */
    synchronized Result take(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (queue.isEmpty()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return queue.pollFirst();
    }
}
