package orderbook.bus;

import java.util.Objects;

import orderbook.Command;
import orderbook.Order;

/**
 * A decoded {@code commands.<symbol>} record. A Place without an {@code orderId} carries id 0 and
 * {@link #needsOrderId()}; the engine service assigns one before sequencing it.
 */
public record BusCommand(String correlationId, long participantId, Command command, boolean needsOrderId) {

    public BusCommand {
        Objects.requireNonNull(command, "command");
    }

    public BusCommand withOrderId(long orderId) {
        Order o = ((Command.Place) command).order();
        return new BusCommand(correlationId, participantId, new Command.Place(new Order(o.symbol(), orderId, o.participantId(),
                o.side(), o.type(), o.timeInForce(), o.price(), o.qtyRemaining())), false);
    }
}
