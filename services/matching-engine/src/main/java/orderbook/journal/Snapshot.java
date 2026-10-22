package orderbook.journal;

import java.util.Objects;

import orderbook.CommandLog;
import orderbook.EngineState;
import orderbook.MatchingEngine;

/**
 * The complete engine state after every journaled command with sequence number {@code <= seq} has been applied.
 * Recovery loads the newest snapshot and replays only journal entries with a higher sequence number.
 */
public record Snapshot(long seq, EngineState state) {

    public Snapshot {
        Objects.requireNonNull(state, "state");
        if (seq < 0) {
            throw new IllegalArgumentException("snapshot seq must be >= 0: " + seq);
        }
    }

    public static Snapshot capture(long seq, MatchingEngine engine) {
        return new Snapshot(seq, engine.exportState());
    }

    public static Snapshot empty(long seq) {
        return new Snapshot(seq, EngineState.EMPTY);
    }

    /** A fresh engine in this snapshot's state, journaling subsequent commands to {@code commandLog}. */
    public MatchingEngine restore(CommandLog commandLog) {
        return MatchingEngine.restore(state, commandLog);
    }
}
