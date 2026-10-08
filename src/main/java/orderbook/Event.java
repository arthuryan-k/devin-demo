package orderbook;

import java.util.Objects;

/** Outputs emitted by the {@link MatchingEngine}, in the order they occurred. */
public sealed interface Event permits Event.OrderPlaced, Event.OrderRejected, Event.OrderCancelled,
        Event.OrderAmended, Event.TradeExecuted {

    /**
     * The order passed validation and was accepted. Any {@link TradeExecuted} events for it follow. Market orders
     * report {@code price} 0.
     */
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
            /** Cancel or amend of an ID that was never accepted or is no longer resting (filled or cancelled). */
            UNKNOWN_ORDER_ID,
            /** Fill-or-kill order that could not be filled in full; no trades happened and the book is unchanged. */
            FOK_NOT_FILLABLE
        }
    }

    /** Some or all of an order's remaining quantity was removed from the market. */
    record OrderCancelled(long orderId, long cancelledQty, CancelReason reason) implements Event {
        public OrderCancelled {
            Objects.requireNonNull(reason, "reason");
        }

        /** A cancel requested by a {@link Command.Cancel}. */
        public OrderCancelled(long orderId, long cancelledQty) {
            this(orderId, cancelledQty, CancelReason.REQUESTED);
        }

        public enum CancelReason {
            /** Explicit {@link Command.Cancel}. */
            REQUESTED,
            /** Unfilled remainder of a market or IOC order, which never rests. */
            UNFILLED_REMAINDER,
            /** Remainder of the aggressive order, cancelled because its next match was against its own participant. */
            SELF_TRADE_PREVENTION
        }
    }

    /**
     * A resting order was amended. {@code priorityRetained} is true for a pure quantity decrease (same
     * {@code seqNum}); otherwise the order re-entered with the new {@code seqNum}, and any trades follow.
     */
    record OrderAmended(long orderId, long oldPrice, long newPrice, long oldQty, long newQty, long seqNum,
            boolean priorityRetained) implements Event {
    }

    record TradeExecuted(Trade trade) implements Event {
        public TradeExecuted {
            Objects.requireNonNull(trade, "trade");
        }
    }
}
