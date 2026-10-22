package orderbook.pipeline;

import java.util.List;

import orderbook.Command;
import orderbook.Event;

/** Immutable copy of a {@link ResultEvent}. {@code command} is null for a reset or a snapshot marker. */
public record Result(long seq, long participantId, Command command, boolean reset, List<Event> events) {

    /** A snapshot marker: it only consumed a sequence; the engine state is unchanged. */
    public boolean snapshotMarker() {
        return command == null && !reset;
    }
}
