package orderbook.pipeline;

import orderbook.Command;

/**
 * Sequencer → engine → fan-out. Commands are stamped with a global sequence when they enter, matched by a single
 * engine stage, and the results are delivered to every {@link ResultHandler} in sequence order.
 */
public interface Pipeline extends AutoCloseable {

    /** Returned by the publish methods when the input ring has no free slot. */
    long BUSY = -1;

    /**
     * Claims the next input slot for {@code command} without blocking. Returns the command's global sequence, or
     * {@link #BUSY} if the input ring is full. If {@code awaitResponse}, the result can be collected with
     * {@link #awaitResponse}.
     */
    long tryPublish(long participantId, Command command, boolean awaitResponse);

    /** Publishes a reset marker: the engine starts over empty at the returned sequence. {@link #BUSY} if full. */
    long tryPublishReset();

    /**
     * Publishes a snapshot marker: at the returned sequence the engine serializes its state into the result (see
     * {@link ResultEvent#snapshot()}) without changing it. {@link #BUSY} if full.
     */
    long tryPublishSnapshot();

    /**
     * Also snapshot automatically after every {@code commands} processed commands (0, the default, disables). The
     * snapshot rides on that command's result, so it covers exactly that sequence.
     */
    void setSnapshotInterval(int commands);

    /** Waits for the result of an awaited command published at {@code seq}. */
    Result awaitResponse(long seq, long timeoutMillis);

    /** Adds an output-ring consumer; it sees every result published after this call. */
    void addConsumer(ResultHandler handler);

    /** Highest sequence claimed so far, or -1. */
    long lastSequence();

    @Override
    void close();
}
