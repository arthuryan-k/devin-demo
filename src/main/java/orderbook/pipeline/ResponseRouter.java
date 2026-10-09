package orderbook.pipeline;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Output consumer that completes the {@link CompletableFuture} of a waiting caller, keyed by global sequence. */
final class ResponseRouter implements ResultHandler {

    private final Map<Long, CompletableFuture<Result>> waiting = new ConcurrentHashMap<>();

    /** Registers interest in {@code seq}; called while the input slot is claimed, before it becomes visible. */
    void expect(long seq) {
        waiting.put(seq, new CompletableFuture<>());
    }

    @Override
    public void onResult(ResultEvent result, boolean endOfBatch) {
        if (result.awaited) {
            CompletableFuture<Result> future = waiting.get(result.seq);
            if (future != null) {
                future.complete(result.toResult());
            }
        }
    }

    CompletableFuture<Result> future(long seq) {
        CompletableFuture<Result> future = waiting.get(seq);
        if (future == null) {
            throw new IllegalStateException("no awaited command at seq " + seq);
        }
        return future;
    }

    Result await(long seq, long timeoutMillis) {
        try {
            return future(seq).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for seq " + seq, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("no result for seq " + seq + " within " + timeoutMillis + " ms", e);
        } finally {
            waiting.remove(seq);
        }
    }

    int pending() {
        return waiting.size();
    }
}
