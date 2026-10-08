package orderbook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import orderbook.Event.OrderAmended;
import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderCancelled.CancelReason;
import orderbook.Event.OrderPlaced;
import orderbook.Event.OrderRejected;
import orderbook.Event.OrderRejected.Reason;
import orderbook.Event.TradeExecuted;

/**
 * Price-time priority matching for a single instrument. Single-threaded by design; the engine itself does no I/O
 * (journaling goes through the injected {@link CommandLog}).
 *
 * <p>Invalid input never throws: it produces an {@link OrderRejected} event.
 *
 * <p>Self-trade prevention: when the next match would pair two orders with the same non-zero
 * {@code participantId}, the policy is {@code CANCEL_TAKER}: the aggressive order's remainder is cancelled
 * ({@link CancelReason#SELF_TRADE_PREVENTION}) and the resting order is left untouched. Real exchanges also offer
 * cancel-maker, cancel-both, and decrement-and-cancel; only cancel-taker is implemented.
 *
 * <p>Amend priority: a pure quantity decrease keeps FIFO position; a price change or quantity increase loses all
 * priority (cancel + re-enter with the same ID and a new {@code seqNum}). Split priority (e.g. keeping the original
 * slice's position on an increase) could be added later as an {@code AmendPolicy}.
 */
public final class MatchingEngine {

    private final OrderBook book = new OrderBook();
    /** Every order ID ever accepted, so a filled or cancelled ID cannot be reused. Grows unbounded. */
    private final Set<Long> acceptedIds = new HashSet<>();
    private CommandLog commandLog;
    private long nextSeqNum = 0;
    private long maxOrderIdSeen = 0;

    public MatchingEngine() {
        this(CommandLog.NONE);
    }

    /** An engine that appends every command to {@code commandLog} before processing it. */
    public MatchingEngine(CommandLog commandLog) {
        this.commandLog = Objects.requireNonNull(commandLog, "commandLog");
    }

    /**
     * Rebuilds an engine by processing {@code commands} in order (without re-logging them), then attaches
     * {@code commandLog} for subsequent commands. Processing is deterministic, so the book, accepted IDs, the
     * {@code seqNum} counter and the max-seen order ID all end up exactly as they were when the commands were
     * first processed.
     */
    public static MatchingEngine replay(Iterable<? extends Command> commands, CommandLog commandLog) {
        MatchingEngine engine = new MatchingEngine();
        for (Command command : commands) {
            engine.process(command);
        }
        engine.commandLog = Objects.requireNonNull(commandLog, "commandLog");
        return engine;
    }

    public OrderBook book() {
        return book;
    }

    /** The {@code seqNum} the next accepted (or priority-losing amended) order will get. */
    public long nextSeqNum() {
        return nextSeqNum;
    }

    /** One more than the largest order ID seen in any {@link Command.Place} (accepted or not), or 1 if none. */
    public long nextOrderId() {
        return maxOrderIdSeen + 1;
    }

    public List<Event> process(Command command) {
        Objects.requireNonNull(command, "command");
        commandLog.append(command);
        if (command instanceof Command.Place place) {
            maxOrderIdSeen = Math.max(maxOrderIdSeen, place.order().id());
            return place(place.order());
        }
        if (command instanceof Command.Cancel cancel) {
            return doCancel(cancel.orderId());
        }
        if (command instanceof Command.Amend amend) {
            return doAmend(amend);
        }
        throw new IllegalArgumentException("unsupported command " + command);
    }

    /** Shorthand for {@code process(new Command.Place(order))}; accepts any order type, not just limits. */
    public List<Event> placeLimit(Order order) {
        return process(new Command.Place(order));
    }

    public List<Event> cancel(long orderId) {
        return process(new Command.Cancel(orderId));
    }

    public List<Event> amend(long orderId, Long newPrice, Long newQty) {
        return process(new Command.Amend(orderId, newPrice, newQty));
    }

    private List<Event> place(Order request) {
        Optional<Reason> rejection = validate(request);
        if (rejection.isPresent()) {
            return List.of(new OrderRejected(request.id(), rejection.get()));
        }

        Order taker = request.sequenced(nextSeqNum++);
        acceptedIds.add(taker.id());

        List<Event> events = new ArrayList<>();
        events.add(new OrderPlaced(taker.id(), taker.side(), taker.price(), taker.qtyRemaining(), taker.seqNum()));
        matchAndFinish(taker, events);
        return List.copyOf(events);
    }

    private List<Event> doCancel(long orderId) {
        return book.remove(orderId)
                .<List<Event>>map(order -> List.of(new OrderCancelled(order.id(), order.qtyRemaining())))
                .orElseGet(() -> List.of(new OrderRejected(orderId, Reason.UNKNOWN_ORDER_ID)));
    }

    private List<Event> doAmend(Command.Amend amend) {
        long id = amend.orderId();
        Optional<Order> found = book.find(id);
        if (found.isEmpty()) {
            return List.of(new OrderRejected(id, Reason.UNKNOWN_ORDER_ID));
        }
        if (amend.newQty() != null && amend.newQty() <= 0) {
            return List.of(new OrderRejected(id, Reason.NON_POSITIVE_QUANTITY));
        }
        if (amend.newPrice() != null && amend.newPrice() <= 0) {
            return List.of(new OrderRejected(id, Reason.NON_POSITIVE_PRICE));
        }

        Order order = found.get();
        long oldPrice = order.price();
        long oldQty = order.qtyRemaining();
        long newPrice = amend.newPrice() == null ? oldPrice : amend.newPrice();
        long newQty = amend.newQty() == null ? oldQty : amend.newQty();

        if (newPrice == oldPrice && newQty <= oldQty) {
            order.reduceTo(newQty);
            return List.of(new OrderAmended(id, oldPrice, newPrice, oldQty, newQty, order.seqNum(), true));
        }

        book.remove(id);
        Order reentered = order.reentered(newPrice, newQty, nextSeqNum++);
        List<Event> events = new ArrayList<>();
        events.add(new OrderAmended(id, oldPrice, newPrice, oldQty, newQty, reentered.seqNum(), false));
        matchAndFinish(reentered, events);
        return List.copyOf(events);
    }

    /** Matches {@code taker} against the book, then rests or cancels whatever is left. */
    private void matchAndFinish(Order taker, List<Event> events) {
        boolean selfTrade = match(taker, events);
        if (taker.isFilled()) {
            return;
        }
        if (selfTrade) {
            events.add(new OrderCancelled(taker.id(), taker.qtyRemaining(), CancelReason.SELF_TRADE_PREVENTION));
        } else if (taker.restsRemainder()) {
            book.add(taker);
        } else {
            events.add(new OrderCancelled(taker.id(), taker.qtyRemaining(), CancelReason.UNFILLED_REMAINDER));
        }
    }

    /** Fills {@code taker} while it crosses. Returns true if matching stopped because of self-trade prevention. */
    private boolean match(Order taker, List<Event> events) {
        Side makerSide = taker.side().opposite();
        while (!taker.isFilled()) {
            Optional<Order> best = book.peekBest(makerSide);
            if (best.isEmpty() || !crosses(taker, best.get().price())) {
                return false;
            }
            Order maker = best.get();
            if (isSelfTrade(maker, taker)) {
                return true;
            }
            long qty = Math.min(taker.qtyRemaining(), maker.qtyRemaining());
            taker.fill(qty);
            maker.fill(qty);
            events.add(new TradeExecuted(new Trade(maker.id(), taker.id(), maker.price(), qty)));
            if (maker.isFilled()) {
                book.pollBest(makerSide);
            }
        }
        return false;
    }

    /** Dry run of {@link #match}: would {@code taker} fill completely? Mirrors its stopping rules; mutates nothing. */
    private boolean canFillCompletely(Order taker) {
        long available = 0;
        Iterator<Order> makers = book.priorityIterator(taker.side().opposite());
        while (makers.hasNext()) {
            Order maker = makers.next();
            if (!crosses(taker, maker.price()) || isSelfTrade(maker, taker)) {
                return false;
            }
            available += maker.qtyRemaining();
            if (available >= taker.qtyRemaining()) {
                return true;
            }
        }
        return false;
    }

    private Optional<Reason> validate(Order order) {
        if (order.qtyRemaining() <= 0) {
            return Optional.of(Reason.NON_POSITIVE_QUANTITY);
        }
        if (order.type() == OrderType.LIMIT && order.price() <= 0) {
            return Optional.of(Reason.NON_POSITIVE_PRICE);
        }
        if (acceptedIds.contains(order.id())) {
            return Optional.of(Reason.DUPLICATE_ORDER_ID);
        }
        if (order.timeInForce() == TimeInForce.FOK && !canFillCompletely(order)) {
            return Optional.of(Reason.FOK_NOT_FILLABLE);
        }
        return Optional.empty();
    }

    private static boolean isSelfTrade(Order maker, Order taker) {
        return taker.participantId() != Order.NO_PARTICIPANT && maker.participantId() == taker.participantId();
    }

    private static boolean crosses(Order taker, long makerPrice) {
        if (taker.type() == OrderType.MARKET) {
            return true;
        }
        return taker.side() == Side.BUY ? taker.price() >= makerPrice : taker.price() <= makerPrice;
    }
}
