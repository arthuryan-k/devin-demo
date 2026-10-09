package orderbook.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderBook;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.Trade;

/**
 * Cash and share balances for the interactive user ("YOU"), enforced in the server layer in front of the engine.
 * Amounts are in ticks (0.01). Neither cash nor shares can go negative: there is no short selling and no credit.
 *
 * <p>Protocol: call {@link #admit} for each user command before it reaches the engine. If it returns a rejection,
 * don't send the command; otherwise its reservation is in place. Then pass <em>every</em> batch of engine events
 * (from the user's and everyone else's commands) to {@link #onEvents}, in order. Fills settle at the trade price,
 * cancels release reservations, and an engine rejection of the admitted command rolls its reservation back.
 * Not thread-safe; use it from the same thread as the engine.
 *
 * <p>Reservations: a limit buy locks {@code price × qty}; a fill at a better price refunds the difference. A market
 * buy locks a conservative estimate: the larger of the cost of walking the current asks and {@code (best ask, else
 * last trade) × qty × (1 + 5%)}, and each fill releases exactly what it cost. A sell locks the shares. An amend
 * re-reserves for the new price/quantity.
 */
public final class Account {

    public enum RejectReason {
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_SHARES,
        /** Cancel or amend of an order that is not one of the user's resting orders. */
        NOT_OWN_OPEN_ORDER
    }

    public record Rejection(long orderId, RejectReason reason, String message) {
    }

    /** A resting (or in-flight) user order and what it has locked. */
    public record OpenOrder(long id, Side side, OrderType type, TimeInForce timeInForce, long price, long qty,
            long reservedCash) {
    }

    static final long MIN_RANDOM_CASH = 500_00;
    static final long MAX_RANDOM_CASH = 10_000_00;
    static final int MARKET_BUFFER_PERCENT = 5;

    private final long participantId;
    private long cash;
    private long reservedCash;
    private long shares;
    private long reservedShares;
    /** Session P&L baseline; starting shares are unpriced. */
    private final long startingCash;
    /** Cost of the shares held under the average-cost method. */
    private long costBasis;
    private long realizedPnl;
    private OptionalLong lastTradePrice = OptionalLong.empty();
    private final Map<Long, Open> open = new LinkedHashMap<>();
    /** Reservation made by the last admitted command, rolled back if the engine rejects it. */
    private Pending pending;

    private static final class Open {
        final long id;
        final Side side;
        final OrderType type;
        final TimeInForce tif;
        long price;
        long qty;
        long reserved;

        Open(long id, Side side, OrderType type, TimeInForce tif, long price, long qty, long reserved) {
            this.id = id;
            this.side = side;
            this.type = type;
            this.tif = tif;
            this.price = price;
            this.qty = qty;
            this.reserved = reserved;
        }

        Open copy() {
            return new Open(id, side, type, tif, price, qty, reserved);
        }
    }

    /** {@code before} is null for a place, or the order's state before an amend. */
    private record Pending(long orderId, Open before) {
    }

    public Account(long participantId, long cash, long shares) {
        if (cash < 0 || shares < 0) {
            throw new IllegalArgumentException("cash and shares must be non-negative");
        }
        this.participantId = participantId;
        this.cash = cash;
        this.shares = shares;
        this.startingCash = cash;
    }

    /**
     * Random starting cash in [{@value #MIN_RANDOM_CASH}, {@value #MAX_RANDOM_CASH}] ticks, but never less than
     * twice the starting reference price, so the user can always afford at least one share.
     */
    public static Account withRandomCash(long participantId, Random random, long referencePriceTicks) {
        long drawn = MIN_RANDOM_CASH + (long) (random.nextDouble() * (MAX_RANDOM_CASH - MIN_RANDOM_CASH + 1));
        return new Account(participantId, Math.max(drawn, 2 * referencePriceTicks), 0);
    }

    // ---- admission --------------------------------------------------------------------------------------------

    /** Checks and reserves for a user command; empty means it may go to the engine. */
    public Optional<Rejection> admit(Command command, OrderBook book) {
        pending = null;
        if (command instanceof Command.Place place) {
            return admitPlace(place.order(), book);
        }
        if (command instanceof Command.Cancel cancel) {
            return open.containsKey(cancel.orderId()) ? Optional.empty() : notOwn(cancel.orderId());
        }
        if (command instanceof Command.Amend amend) {
            return admitAmend(amend);
        }
        throw new IllegalArgumentException("unsupported command " + command);
    }

    private Optional<Rejection> admitPlace(Order order, OrderBook book) {
        if (order.participantId() != participantId) {
            throw new IllegalArgumentException("order belongs to participant " + order.participantId());
        }
        long qty = order.qtyRemaining();
        boolean limit = order.type() == OrderType.LIMIT;
        if (qty <= 0 || (limit && order.price() <= 0) || open.containsKey(order.id())) {
            return Optional.empty(); // the engine rejects it; nothing to reserve
        }
        long price = limit ? order.price() : 0;
        if (order.side() == Side.BUY) {
            long need = limit ? multiplyOrMax(price, qty) : marketBuyEstimate(book, qty, lastTradePrice);
            if (need > availableCash()) {
                return Optional.of(new Rejection(order.id(), RejectReason.INSUFFICIENT_FUNDS,
                        "needs " + Ticks.format(need) + " but only " + Ticks.format(availableCash()) + " available"));
            }
            reservedCash += need;
            open.put(order.id(), new Open(order.id(), Side.BUY, order.type(), order.timeInForce(), price, qty, need));
        } else {
            if (qty > availableShares()) {
                return Optional.of(new Rejection(order.id(), RejectReason.INSUFFICIENT_SHARES,
                        "selling " + qty + " but only " + availableShares() + " shares available"));
            }
            reservedShares += qty;
            open.put(order.id(), new Open(order.id(), Side.SELL, order.type(), order.timeInForce(), price, qty, 0));
        }
        pending = new Pending(order.id(), null);
        checkInvariants();
        return Optional.empty();
    }

    private Optional<Rejection> admitAmend(Command.Amend amend) {
        Open order = open.get(amend.orderId());
        if (order == null) {
            return notOwn(amend.orderId());
        }
        if ((amend.newQty() != null && amend.newQty() <= 0) || (amend.newPrice() != null && amend.newPrice() <= 0)) {
            return Optional.empty(); // the engine rejects it
        }
        long newPrice = amend.newPrice() == null ? order.price : amend.newPrice();
        long newQty = amend.newQty() == null ? order.qty : amend.newQty();
        Open before = order.copy();
        if (order.side == Side.BUY) {
            long newReserved = multiplyOrMax(newPrice, newQty);
            long extra = newReserved - order.reserved;
            if (extra > availableCash()) {
                return Optional.of(new Rejection(order.id, RejectReason.INSUFFICIENT_FUNDS,
                        "amend needs " + Ticks.format(extra) + " more but only " + Ticks.format(availableCash())
                                + " available"));
            }
            reservedCash += extra;
            order.reserved = newReserved;
        } else {
            long extra = newQty - order.qty;
            if (extra > availableShares()) {
                return Optional.of(new Rejection(order.id, RejectReason.INSUFFICIENT_SHARES,
                        "amend needs " + extra + " more shares but only " + availableShares() + " available"));
            }
            reservedShares += extra;
        }
        order.price = newPrice;
        order.qty = newQty;
        pending = new Pending(order.id, before);
        checkInvariants();
        return Optional.empty();
    }

    private static Optional<Rejection> notOwn(long orderId) {
        return Optional.of(new Rejection(orderId, RejectReason.NOT_OWN_OPEN_ORDER,
                "order " + orderId + " is not one of your open orders"));
    }

    /**
     * Upper bound on what a market buy of {@code qty} can cost right now: the cost of sweeping the current asks
     * (it can't trade at worse prices, and an unfilled remainder is cancelled), or {@code anchor × qty} plus a
     * {@value #MARKET_BUFFER_PERCENT}% buffer, whichever is larger. The anchor is the best ask, else the last trade.
     */
    static long marketBuyEstimate(OrderBook book, long qty, OptionalLong lastTradePrice) {
        long sweep = 0;
        long remaining = qty;
        for (OrderBook.Level level : book.depth(Side.SELL, Integer.MAX_VALUE)) {
            long take = Math.min(remaining, level.totalQty());
            sweep = addOrMax(sweep, multiplyOrMax(take, level.price()));
            remaining -= take;
            if (remaining == 0) {
                break;
            }
        }
        OptionalLong anchor = book.bestAsk().isPresent() ? book.bestAsk() : lastTradePrice;
        long buffered = 0;
        if (anchor.isPresent()) {
            long base = multiplyOrMax(anchor.getAsLong(), qty);
            buffered = addOrMax(base, (multiplyOrMax(base, MARKET_BUFFER_PERCENT) + 99) / 100);
        }
        return Math.max(sweep, buffered);
    }

    // ---- settlement ---------------------------------------------------------------------------------------------

    /** Applies one batch of engine events (from any participant's command). */
    public void onEvents(List<Event> events) {
        Pending admitted = pending;
        pending = null;
        for (Event event : events) {
            if (event instanceof Event.TradeExecuted executed) {
                Trade trade = executed.trade();
                lastTradePrice = OptionalLong.of(trade.price());
                fill(trade.makerOrderId(), trade);
                fill(trade.takerOrderId(), trade);
            } else if (event instanceof Event.OrderCancelled cancelled) {
                Open order = open.get(cancelled.orderId());
                if (order != null) {
                    close(order);
                }
            } else if (event instanceof Event.OrderRejected rejected && admitted != null
                    && rejected.orderId() == admitted.orderId()) {
                rollBack(admitted);
            }
        }
        checkInvariants();
    }

    private void fill(long orderId, Trade trade) {
        Open order = open.get(orderId);
        if (order == null) {
            return;
        }
        long qty = trade.qty();
        long value = Math.multiplyExact(trade.price(), qty);
        if (order.side == Side.BUY) {
            long release = order.type == OrderType.LIMIT ? Math.multiplyExact(order.price, qty) : value;
            if (value > release || release > order.reserved) {
                throw new IllegalStateException("fill of order " + orderId + " exceeds its reservation");
            }
            cash -= value;
            reservedCash -= release;
            order.reserved -= release;
            shares += qty;
            costBasis += value;
        } else {
            long soldCost = Math.multiplyExact(costBasis, qty) / shares;
            costBasis -= soldCost;
            realizedPnl += value - soldCost;
            shares -= qty;
            reservedShares -= qty;
            cash += value;
        }
        order.qty -= qty;
        if (order.qty == 0) {
            close(order);
        }
    }

    /** Removes a finished order and releases whatever it still has locked. */
    private void close(Open order) {
        open.remove(order.id);
        if (order.side == Side.BUY) {
            reservedCash -= order.reserved;
        } else {
            reservedShares -= order.qty;
        }
    }

    private void rollBack(Pending admitted) {
        Open current = open.get(admitted.orderId());
        if (current == null) {
            return;
        }
        if (admitted.before() == null) {
            close(current);
            return;
        }
        Open before = admitted.before();
        if (current.side == Side.BUY) {
            reservedCash += before.reserved - current.reserved;
        } else {
            reservedShares += before.qty - current.qty;
        }
        open.put(before.id, before);
    }

    private void checkInvariants() {
        if (cash < 0 || reservedCash < 0 || reservedCash > cash || shares < 0 || reservedShares < 0
                || reservedShares > shares) {
            throw new IllegalStateException("account invariant violated: " + this);
        }
    }

    private static long multiplyOrMax(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long addOrMax(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    // ---- queries ------------------------------------------------------------------------------------------------

    public long participantId() {
        return participantId;
    }

    /** Total cash, including the reserved part. */
    public long cash() {
        return cash;
    }

    public long reservedCash() {
        return reservedCash;
    }

    public long availableCash() {
        return cash - reservedCash;
    }

    public long sharesOwned() {
        return shares;
    }

    public long reservedShares() {
        return reservedShares;
    }

    public long availableShares() {
        return shares - reservedShares;
    }

    public OptionalLong lastTradePrice() {
        return lastTradePrice;
    }

    /** Cash plus shares marked at the last trade price (shares count as 0 before any trade). */
    public long equity() {
        return cash + (lastTradePrice.isPresent() ? shares * lastTradePrice.getAsLong() : 0);
    }

    public long startingCash() {
        return startingCash;
    }

    public long costBasis() {
        return costBasis;
    }

    /** Average cost per held share in ticks, rounded; empty when flat. */
    public OptionalLong averageCost() {
        return shares == 0 ? OptionalLong.empty() : OptionalLong.of(Math.round((double) costBasis / shares));
    }

    /** Proceeds of sells minus the average cost of the shares sold. */
    public long realizedPnl() {
        return realizedPnl;
    }

    /** Held shares marked at the last trade price minus their cost; 0 before any trade. */
    public long unrealizedPnl() {
        return lastTradePrice.isPresent() ? shares * lastTradePrice.getAsLong() - costBasis : 0;
    }

    /** Gain or loss since the account opened: realized plus unrealized. */
    public long sessionPnl() {
        return realizedPnl + unrealizedPnl();
    }

    public boolean owns(long orderId) {
        return open.containsKey(orderId);
    }

    public List<OpenOrder> openOrders() {
        List<OpenOrder> result = new ArrayList<>();
        for (Open o : open.values()) {
            result.add(new OpenOrder(o.id, o.side, o.type, o.tif, o.price, o.qty, o.reserved));
        }
        return List.copyOf(result);
    }

    @Override
    public String toString() {
        return "Account[cash=" + cash + ", reservedCash=" + reservedCash + ", shares=" + shares
                + ", reservedShares=" + reservedShares + ", open=" + open.size() + "]";
    }
}
