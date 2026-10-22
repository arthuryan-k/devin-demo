package orderbook.pipeline;

import java.util.Objects;

import orderbook.CommandLog;
import orderbook.journal.Journal;

/**
 * Output consumer that appends every sequenced command to a journal, in sequence order, off the engine thread.
 * Commands rejected with {@code BUSY} never reach the ring and are not journaled.
 *
 * <p>With a {@link Journal} it also writes the snapshots the engine captured ({@link ResultEvent#snapshot()}: at
 * snapshot markers, every {@code snapshotInterval}-th command, and resets, which snapshot an empty engine), each
 * tagged with exactly the sequence it covers, and the journal rotates to a new segment. A new pipeline always
 * starts from an empty engine, so if the journal already has history, attaching records an empty snapshot first and
 * this pipeline's sequences are journaled after it ({@code journalSeq = offset + pipelineSeq}). Attach before
 * publishing anything.
 *
 * <p>With a plain {@link CommandLog}, snapshots and markers are ignored and {@code onReset} runs at resets.
 */
public final class JournalHandler implements ResultHandler {

    private final CommandLog log;
    private final Journal journal;
    private final Runnable onReset;
    private final long offset;

    public JournalHandler(CommandLog log, Runnable onReset) {
        this.log = Objects.requireNonNull(log, "log");
        this.journal = null;
        this.onReset = onReset != null ? onReset : () -> { };
        this.offset = 0;
    }

    public JournalHandler(Journal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.log = journal;
        this.onReset = () -> { };
        long last = journal.lastSeq();
        if (last >= 0) {
            journal.writeSnapshot(last + 1, Journal.emptyState());
            offset = last + 2;
        } else {
            offset = 0;
        }
    }

    /** Journal sequence of pipeline sequence 0. */
    public long offset() {
        return offset;
    }

    @Override
    public void onResult(ResultEvent result, boolean endOfBatch) {
        if (journal == null) {
            if (result.reset) {
                onReset.run();
            } else if (result.command != null) {
                log.append(result.command);
            }
            return;
        }
        long seq = offset + result.seq;
        if (result.command != null) {
            journal.append(seq, result.command);
        }
        if (result.snapshot != null) {
            journal.writeSnapshot(seq, result.snapshot);
        }
    }
}
