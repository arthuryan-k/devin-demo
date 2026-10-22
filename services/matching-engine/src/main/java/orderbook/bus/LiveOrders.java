package orderbook.bus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import orderbook.Command;
import orderbook.EngineState;
import orderbook.Event;
import orderbook.Side;
import orderbook.Trade;

/**
 * Owner, side and remaining quantity of every live order, maintained from the event stream (seeded from the recovered
 * book), so {@code TradeExecuted} records can name both participants. Used by the events publisher thread only.
 */
final class LiveOrders implements BusCodec.Parties {

    private static final class Live {
        final long participantId;
        final Side side;
        long remaining;

        Live(long participantId, Side side, long remaining) {
            this.participantId = participantId;
            this.side = side;
            this.remaining = remaining;
        }
    }

    private final Map<Long, Live> orders = new HashMap<>();

    LiveOrders(EngineState state) {
        for (List<EngineState.RestingOrder> side : List.of(state.bids(), state.asks())) {
            for (EngineState.RestingOrder r : side) {
                orders.put(r.id(), new Live(r.participantId(), r.side(), r.qtyRemaining()));
            }
        }
    }

    /** Registers the order a Place accepted; call before encoding that command's events. */
    void place(Command command, List<Event> events) {
        if (!(command instanceof Command.Place place)) {
            return;
        }
        for (Event e : events) {
            if (e instanceof Event.OrderPlaced p) {
                orders.put(p.orderId(), new Live(place.order().participantId(), p.side(), p.qty()));
            }
        }
    }

    /** Applies fills, cancels and amends; call after encoding. */
    void apply(List<Event> events) {
        for (Event e : events) {
            if (e instanceof Event.TradeExecuted t) {
                Trade trade = t.trade();
                fill(trade.makerOrderId(), trade.qty());
                fill(trade.takerOrderId(), trade.qty());
            } else if (e instanceof Event.OrderCancelled c) {
                orders.remove(c.orderId());
            } else if (e instanceof Event.OrderAmended a) {
                Live live = orders.get(a.orderId());
                if (live != null) {
                    live.remaining = a.newQty();
                }
            }
        }
    }

    private void fill(long orderId, long qty) {
        Live live = orders.get(orderId);
        if (live != null && (live.remaining -= qty) <= 0) {
            orders.remove(orderId);
        }
    }

    int size() {
        return orders.size();
    }

    @Override
    public long participantOf(long orderId) {
        return live(orderId).participantId;
    }

    @Override
    public Side sideOf(long orderId) {
        return live(orderId).side;
    }

    private Live live(long orderId) {
        Live live = orders.get(orderId);
        if (live == null) {
            throw new IllegalStateException("trade on unknown order " + orderId);
        }
        return live;
    }
}
