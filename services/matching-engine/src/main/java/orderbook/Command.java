package orderbook;

import java.util.Objects;

/**
 * Inputs accepted by {@link MatchingEngine#process(Command)}. Every command names the {@link #symbol()} it is for,
 * which is what {@code ExchangeCore} routes on; constructors without a symbol use {@link Symbols#DEFAULT}.
 */
public sealed interface Command permits Command.Place, Command.Cancel, Command.Amend {

    /** The instrument this command targets. */
    String symbol();

    /** The symbol is the order's. */
    record Place(Order order) implements Command {
        public Place {
            Objects.requireNonNull(order, "order");
        }

        @Override
        public String symbol() {
            return order.symbol();
        }
    }

    record Cancel(String symbol, long orderId) implements Command {
        public Cancel {
            Objects.requireNonNull(symbol, "symbol");
        }

        public Cancel(long orderId) {
            this(Symbols.DEFAULT, orderId);
        }
    }

    /**
     * Changes the price and/or open quantity of a resting order. A {@code null} field is left unchanged.
     * {@code newQty} is the new <em>remaining</em> quantity, not the original total.
     *
     * <p>Priority: a pure quantity decrease keeps the order's FIFO position. A price change or a quantity increase
     * removes the order and re-enters it at the back of its (new) level with the same ID and a new {@code seqNum};
     * a re-entered order that crosses the book matches like an incoming order.
     */
    record Amend(String symbol, long orderId, Long newPrice, Long newQty) implements Command {

        public Amend {
            Objects.requireNonNull(symbol, "symbol");
        }

        public Amend(long orderId, Long newPrice, Long newQty) {
            this(Symbols.DEFAULT, orderId, newPrice, newQty);
        }

        public static Amend price(long orderId, long newPrice) {
            return new Amend(orderId, newPrice, null);
        }

        public static Amend qty(long orderId, long newQty) {
            return new Amend(orderId, null, newQty);
        }
    }
}
