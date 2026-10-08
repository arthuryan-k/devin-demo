package orderbook;

import java.util.Objects;

/** Inputs accepted by {@link MatchingEngine#process(Command)}. */
public sealed interface Command permits Command.Place, Command.Cancel, Command.Amend {

    record Place(Order order) implements Command {
        public Place {
            Objects.requireNonNull(order, "order");
        }
    }

    record Cancel(long orderId) implements Command {
    }

    /**
     * Changes the price and/or open quantity of a resting order. A {@code null} field is left unchanged.
     * {@code newQty} is the new <em>remaining</em> quantity, not the original total.
     *
     * <p>Priority: a pure quantity decrease keeps the order's FIFO position. A price change or a quantity increase
     * removes the order and re-enters it at the back of its (new) level with the same ID and a new {@code seqNum};
     * a re-entered order that crosses the book matches like an incoming order.
     */
    record Amend(long orderId, Long newPrice, Long newQty) implements Command {

        public static Amend price(long orderId, long newPrice) {
            return new Amend(orderId, newPrice, null);
        }

        public static Amend qty(long orderId, long newQty) {
            return new Amend(orderId, null, newQty);
        }
    }
}
