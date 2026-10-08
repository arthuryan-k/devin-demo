package orderbook;

import java.util.Objects;

/**
 * A limit order. Price is in integer ticks and quantity in integer units; floating point is never used.
 *
 * <p>{@code qtyRemaining} mutates as the order fills, so equality and hashing are based on {@code id} only.
 *
 * <p>{@code seqNum} is a monotonic counter assigned by the {@link MatchingEngine} when the order is accepted
 * and determines FIFO priority within a price level. Orders built by callers via the public constructor carry
 * {@link #UNASSIGNED_SEQ}; the engine works on its own sequenced copy.
 */
public final class Order {

    public static final long UNASSIGNED_SEQ = -1L;

    private final long id;
    private final Side side;
    private final long price;
    private long qtyRemaining;
    private final long seqNum;

    public Order(long id, Side side, long price, long qty) {
        this(id, side, price, qty, UNASSIGNED_SEQ);
    }

    Order(long id, Side side, long price, long qtyRemaining, long seqNum) {
        this.id = id;
        this.side = Objects.requireNonNull(side, "side");
        this.price = price;
        this.qtyRemaining = qtyRemaining;
        this.seqNum = seqNum;
    }

    public long id() {
        return id;
    }

    public Side side() {
        return side;
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

    void fill(long qty) {
        if (qty <= 0 || qty > qtyRemaining) {
            throw new IllegalArgumentException("invalid fill " + qty + " for remaining " + qtyRemaining);
        }
        qtyRemaining -= qty;
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
        return "Order[id=" + id + ", side=" + side + ", price=" + price
                + ", qtyRemaining=" + qtyRemaining + ", seqNum=" + seqNum + "]";
    }
}
