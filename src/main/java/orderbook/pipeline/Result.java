package orderbook.pipeline;

import java.util.List;

import orderbook.Command;
import orderbook.Event;

/** Immutable copy of a {@link ResultEvent}. {@code command} is null for a reset. */
public record Result(long seq, long participantId, Command command, boolean reset, List<Event> events) {
}
