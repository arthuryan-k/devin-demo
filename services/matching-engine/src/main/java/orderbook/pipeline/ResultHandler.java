package orderbook.pipeline;

/**
 * Consumer of the output ring. Each handler runs on its own thread with its own cursor (under
 * {@link DisruptorPipeline}), so a slow handler only falls behind; it never delays matching until it lags by a
 * whole output ring.
 */
@FunctionalInterface
public interface ResultHandler {

    void onResult(ResultEvent result, boolean endOfBatch) throws Exception;
}
