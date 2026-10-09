package orderbook.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.List;

import orderbook.EngineState;
import orderbook.MatchingEngine;
import orderbook.journal.SnapshotCodec;

/**
 * The single matching stage: copies an input slot into an output slot and runs the engine. No I/O. At snapshot
 * markers, resets and every {@code snapshotInterval}-th command it serializes the engine state into the output slot
 * (cheap, in memory); writing it out is left to a consumer.
 */
final class EngineStage {

    private static final byte[] EMPTY_STATE =
            SnapshotCodec.encodeState(EngineState.EMPTY).getBytes(StandardCharsets.UTF_8);

    private MatchingEngine engine = new MatchingEngine();
    private volatile int snapshotInterval;
    private int sinceSnapshot;

    void process(CommandEvent in, ResultEvent out) {
        out.seq = in.seq;
        out.participantId = in.participantId;
        out.command = in.command;
        out.reset = in.reset;
        out.snapshotMarker = in.snapshotMarker;
        out.awaited = in.awaited;
        out.snapshot = null;
        if (in.reset) {
            engine = new MatchingEngine();
            out.events = List.of();
            out.snapshot = EMPTY_STATE;
            sinceSnapshot = 0;
        } else if (in.snapshotMarker) {
            out.events = List.of();
            out.snapshot = captureState();
        } else {
            out.events = engine.process(in.command);
            int every = snapshotInterval;
            if (every > 0 && ++sinceSnapshot >= every) {
                out.snapshot = captureState();
            }
        }
        in.command = null;
    }

    private byte[] captureState() {
        sinceSnapshot = 0;
        return SnapshotCodec.encodeState(engine.exportState()).getBytes(StandardCharsets.UTF_8);
    }

    /** Snapshot after every {@code commands} commands; 0 disables. Safe to call from any thread. */
    void setSnapshotInterval(int commands) {
        if (commands < 0) {
            throw new IllegalArgumentException("snapshot interval must be >= 0");
        }
        snapshotInterval = commands;
    }

    /** Engine-thread state; only safe to read once the pipeline is quiescent (tests). */
    MatchingEngine engine() {
        return engine;
    }
}
