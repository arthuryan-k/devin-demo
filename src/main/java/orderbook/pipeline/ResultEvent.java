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
    boolean awaited;
    List<Event> events = List.of();

    public long seq() {
        return seq;
    }

    public long participantId() {
        return participantId;
    }

    /** The processed command, or null for a reset. */
    public Command command() {
        return command;
    }

    /** True if the engine was replaced by an empty one at this sequence. */
    public boolean isReset() {
        return reset;
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
