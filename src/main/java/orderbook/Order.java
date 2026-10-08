package orderbook;

import java.util.Objects;

/**
 * An order. Price is in integer ticks and quantity in integer units; floating point is never used.
 *
 * <p>{@code qtyRemaining} mutates as the order fills, so equality and hashing are based on {@code id} only.
 *
 * <p>{@code seqNum} is a monotonic counter assigned by the {@link MatchingEngine} when the order is accepted
 * and determines FIFO priority within a price level. Orders built by callers carry {@link #UNASSIGNED_SEQ};
 * the engine works on its own sequenced copy.
 *
 * <p>{@code participantId} identifies the owner for self-trade prevention. {@link #NO_PARTICIPANT} (0) means
 * anonymous: such orders are never treated as self-trades.
 *
 * <p>Market orders have no price: whatever price the caller passes is ignored and the engine's copy stores 0.
 * They never rest, so {@link TimeInForce#GTC} and {@link TimeInForce#IOC} behave the same for them;
 * {@link TimeInForce#FOK} makes them all-or-nothing.
 */
public final class Order {

    public static final long UNASSIGNED_SEQ = -1L;
    public static final long NO_PARTICIPANT = 0L;

    private final long id;
    private final long participantId;
    private final Side side;
    private final OrderType type;
    private final TimeInForce timeInForce;
    private final long price;
    private long qtyRemaining;
    private final long seqNum;

    /** An anonymous GTC limit order. */
    public Order(long id, Side side, long price, long qty) {
        this(id, NO_PARTICIPANT, side, OrderType.LIMIT, TimeInForce.GTC, price, qty);
    }

    public Order(long id, long participantId, Side side, OrderType type, TimeInForce timeInForce, long price, long qty) {
        this(id, participantId, side, type, timeInForce, price, qty, UNASSIGNED_SEQ);
    }

    Order(long id, long participantId, Side side, OrderType type, TimeInForce timeInForce, long price,
            long qtyRemaining, long seqNum) {
        this.id = id;
        this.participantId = participantId;
        this.side = Objects.requireNonNull(side, "side");
        this.type = Objects.requireNonNull(type, "type");
        this.timeInForce = Objects.requireNonNull(timeInForce, "timeInForce");
        this.price = price;
        this.qtyRemaining = qtyRemaining;
        this.seqNum = seqNum;
    }

    public static Order limit(long id, long participantId, Side side, long price, long qty, TimeInForce timeInForce) {
        return new Order(id, participantId, side, OrderType.LIMIT, timeInForce, price, qty);
    }

    public static Order limit(long id, long participantId, Side side, long price, long qty) {
        return limit(id, participantId, side, price, qty, TimeInForce.GTC);
    }

    public static Order market(long id, long participantId, Side side, long qty, TimeInForce timeInForce) {
        return new Order(id, participantId, side, OrderType.MARKET, timeInForce, 0, qty);
    }

    public static Order market(long id, long participantId, Side side, long qty) {
        return market(id, participantId, side, qty, TimeInForce.IOC);
    }

    public long id() {
        return id;
    }

    public long participantId() {
        return participantId;
    }

    public Side side() {
        return side;
    }

    public OrderType type() {
        return type;
    }

    public TimeInForce timeInForce() {
        return timeInForce;
    }

    public long price() {
        return price;
    }

    public long qtyRemaining() {
        return qtyRemaining;
    }

    public long seqNum() {
        return seqNum;
    }

    /** Engine-owned copy with an assigned {@code seqNum}; a market order's price is normalized to 0. */
    Order sequenced(long seq) {
        return new Order(id, participantId, side, type, timeInForce, type == OrderType.MARKET ? 0 : price,
                qtyRemaining, seq);
    }

    /** The same order re-entered after an amend that loses priority. */
    Order reentered(long newPrice, long newQty, long seq) {
        return new Order(id, participantId, side, type, timeInForce, newPrice, newQty, seq);
    }

    /** True if an unfilled remainder rests in the book (GTC limit); otherwise it is cancelled. */
    boolean restsRemainder() {
        return type == OrderType.LIMIT && timeInForce == TimeInForce.GTC;
    }

    void fill(long qty) {
        if (qty <= 0 || qty > qtyRemaining) {
            throw new IllegalArgumentException("invalid fill " + qty + " for remaining " + qtyRemaining);
        }
        qtyRemaining -= qty;
    }

    /** In-place quantity decrease that keeps FIFO position. */
    void reduceTo(long newQty) {
        if (newQty <= 0 || newQty > qtyRemaining) {
            throw new IllegalArgumentException("invalid reduce to " + newQty + " from " + qtyRemaining);
        }
        qtyRemaining = newQty;
    }

    boolean isFilled() {
        return qtyRemaining == 0;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Order other && id == other.id);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "Order[id=" + id + ", participantId=" + participantId + ", side=" + side + ", type=" + type
                + ", tif=" + timeInForce + ", price=" + price + ", qtyRemaining=" + qtyRemaining
                + ", seqNum=" + seqNum + "]";
    }
}
