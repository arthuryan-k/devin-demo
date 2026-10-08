package orderbook;

import java.util.Objects;

/** Outputs emitted by the {@link MatchingEngine}, in the order they occurred. */
public sealed interface Event
        permits Event.OrderPlaced, Event.OrderRejected, Event.OrderCancelled, Event.TradeExecuted {

    /** The order passed validation and was accepted. Any {@link TradeExecuted} events for it follow. */
    record OrderPlaced(long orderId, Side side, long price, long qty, long seqNum) implements Event {
    }

    record OrderRejected(long orderId, Reason reason) implements Event {
        public OrderRejected {
            Objects.requireNonNull(reason, "reason");
        }

        public enum Reason {
            NON_POSITIVE_QUANTITY,
            NON_POSITIVE_PRICE,
            DUPLICATE_ORDER_ID,
            /** Cancel of an ID that was never accepted or is no longer resting (filled or already cancelled). */
            UNKNOWN_ORDER_ID
        }
    }

    record OrderCancelled(long orderId, long cancelledQty) implements Event {
    }

    record TradeExecuted(Trade trade) implements Event {
        public TradeExecuted {
            Objects.requireNonNull(trade, "trade");
        }
    }
}
