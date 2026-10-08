package orderbook;

import java.util.Objects;

/** Inputs accepted by {@link MatchingEngine#process(Command)}. */
public sealed interface Command permits Command.Place, Command.Cancel {

    record Place(Order order) implements Command {
        public Place {
            Objects.requireNonNull(order, "order");
        }
    }

    record Cancel(long orderId) implements Command {
    }
}
