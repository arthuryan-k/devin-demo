package orderbook.pipeline;

import java.util.Objects;

import orderbook.CommandLog;

/**
 * Output consumer that appends every sequenced command to a {@link CommandLog}, in sequence order, off the engine
 * thread. Commands rejected with {@code BUSY} never reach the ring and are not journaled. {@code onReset} runs at
 * a reset marker (e.g. to truncate the journal).
 */
public final class JournalHandler implements ResultHandler {

    private final CommandLog log;
    private final Runnable onReset;

    public JournalHandler(CommandLog log, Runnable onReset) {
        this.log = Objects.requireNonNull(log, "log");
        this.onReset = onReset != null ? onReset : () -> { };
    }

    @Override
    public void onResult(ResultEvent result, boolean endOfBatch) {
        if (result.reset) {
            onReset.run();
        } else {
            log.append(result.command);
        }
    }
}
