package orderbook.pipeline;

import java.util.List;

import orderbook.Command;
import orderbook.Event;

/**
 * Preallocated slot of the output ring: one engine result per input command, carrying the same global sequence.
 * Slots are reused; handlers must copy ({@link #toResult()}) anything they keep beyond {@code onResult}.
 */
public final class ResultEvent {

    long seq = -1;
    long participantId;
    Command command;
    boolean reset;
    boolean snapshotMarker;
    boolean awaited;
    List<Event> events = List.of();
    byte[] snapshot;

    public long seq() {
        return seq;
    }

    public long participantId() {
        return participantId;
    }

    /** The processed command, or null for a reset or snapshot marker. */
    public Command command() {
        return command;
    }

    /** True if the engine was replaced by an empty one at this sequence. */
    public boolean isReset() {
        return reset;
    }

    /** True for a snapshot marker: no command, no events, {@link #snapshot()} holds the engine state. */
    public boolean isSnapshotMarker() {
        return snapshotMarker;
    }

    /**
     * The engine state right after this sequence ({@code SnapshotCodec.encodeState}, UTF-8), captured on the engine
     * thread; present at snapshot markers, resets and every {@code snapshotInterval}-th command, otherwise null.
     * Shared and read-only.
     */
    public byte[] snapshot() {
        return snapshot;
    }

    /** True if a caller is waiting for this result through the response router. */
    public boolean awaited() {
        return awaited;
    }

    public List<Event> events() {
        return events;
    }

    public Result toResult() {
        return new Result(seq, participantId, command, reset, events);
    }
}
