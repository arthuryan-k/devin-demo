package orderbook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderPlaced;
import orderbook.Event.OrderRejected;
import orderbook.Event.OrderRejected.Reason;
import orderbook.Event.TradeExecuted;

/**
 * Price-time priority matching for a single instrument. Single-threaded by design; no I/O.
 *
 * <p>Invalid input never throws: it produces an {@link OrderRejected} event.
 *
 * <p>Known gap (Phase 1): there is no participant/account tracking, so self-trades are not prevented.
 */
public final class MatchingEngine {

    private final OrderBook book = new OrderBook();
    /** Every order ID ever accepted, so a filled or cancelled ID cannot be reused. Grows unbounded. */
    private final Set<Long> acceptedIds = new HashSet<>();
    private long nextSeqNum = 0;

    public OrderBook book() {
        return book;
    }

    public List<Event> process(Command command) {
        if (command instanceof Command.Place place) {
            return placeLimit(place.order());
        }
        if (command instanceof Command.Cancel cancel) {
            return cancel(cancel.orderId());
        }
        throw new IllegalArgumentException("unsupported command " + command);
    }

    public List<Event> placeLimit(Order request) {
        Optional<Reason> rejection = validate(request);
        if (rejection.isPresent()) {
            return List.of(new OrderRejected(request.id(), rejection.get()));
        }

        Order taker = new Order(request.id(), request.side(), request.price(), request.qtyRemaining(), nextSeqNum++);
        acceptedIds.add(taker.id());

        List<Event> events = new ArrayList<>();
        events.add(new OrderPlaced(taker.id(), taker.side(), taker.price(), taker.qtyRemaining(), taker.seqNum()));

        Side makerSide = taker.side().opposite();
        while (!taker.isFilled()) {
            Optional<Order> best = book.peekBest(makerSide);
            if (best.isEmpty() || !crosses(taker, best.get().price())) {
                break;
            }
            Order maker = best.get();
            long qty = Math.min(taker.qtyRemaining(), maker.qtyRemaining());
            taker.fill(qty);
            maker.fill(qty);
            events.add(new TradeExecuted(new Trade(maker.id(), taker.id(), maker.price(), qty)));
            if (maker.isFilled()) {
                book.pollBest(makerSide);
            }
        }

        if (!taker.isFilled()) {
            book.add(taker);
        }
        return List.copyOf(events);
    }

    public List<Event> cancel(long orderId) {
        return book.remove(orderId)
                .<List<Event>>map(order -> List.of(new OrderCancelled(order.id(), order.qtyRemaining())))
                .orElseGet(() -> List.of(new OrderRejected(orderId, Reason.UNKNOWN_ORDER_ID)));
    }

    private Optional<Reason> validate(Order order) {
        if (order.qtyRemaining() <= 0) {
            return Optional.of(Reason.NON_POSITIVE_QUANTITY);
        }
        if (order.price() <= 0) {
            return Optional.of(Reason.NON_POSITIVE_PRICE);
        }
        if (acceptedIds.contains(order.id())) {
            return Optional.of(Reason.DUPLICATE_ORDER_ID);
        }
        return Optional.empty();
    }

    private static boolean crosses(Order taker, long makerPrice) {
        return taker.side() == Side.BUY ? taker.price() >= makerPrice : taker.price() <= makerPrice;
    }
}
