package orderbook;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable copy of a {@link MatchingEngine}'s complete state: every resting order in priority order (best price
 * first, then FIFO), every order ID ever accepted, and the {@code seqNum} and order-ID counters. Produced by
 * {@link MatchingEngine#exportState()} and turned back into an engine by {@link MatchingEngine#restore}.
 *
 * @param acceptedIds sorted ascending, without duplicates
 */
public record EngineState(List<RestingOrder> bids, List<RestingOrder> asks, long[] acceptedIds, long nextSeqNum,
        long maxOrderIdSeen) {

    public static final EngineState EMPTY = new EngineState(List.of(), List.of(), new long[0], 0, 0);

    /** One resting order as it sits in the book (remaining quantity, engine-assigned {@code seqNum}). */
    public record RestingOrder(long id, long participantId, Side side, OrderType type, TimeInForce timeInForce,
            long price, long qtyRemaining, long seqNum) {

        public RestingOrder {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(timeInForce, "timeInForce");
        }

        static RestingOrder of(Order o) {
            return new RestingOrder(o.id(), o.participantId(), o.side(), o.type(), o.timeInForce(), o.price(),
                    o.qtyRemaining(), o.seqNum());
        }

        Order toOrder() {
            return new Order(id, participantId, side, type, timeInForce, price, qtyRemaining, seqNum);
        }
    }

    public EngineState {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
        acceptedIds = acceptedIds.clone();
    }

    @Override
    public long[] acceptedIds() {
        return acceptedIds.clone();
    }

    /** Number of resting orders on both sides. */
    public int orderCount() {
        return bids.size() + asks.size();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof EngineState s && bids.equals(s.bids) && asks.equals(s.asks)
                && Arrays.equals(acceptedIds, s.acceptedIds) && nextSeqNum == s.nextSeqNum
                && maxOrderIdSeen == s.maxOrderIdSeen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bids, asks, Arrays.hashCode(acceptedIds), nextSeqNum, maxOrderIdSeen);
    }

    @Override
    public String toString() {
        return "EngineState[bids=" + bids + ", asks=" + asks + ", acceptedIds=" + acceptedIds.length
                + ", nextSeqNum=" + nextSeqNum + ", maxOrderIdSeen=" + maxOrderIdSeen + "]";
    }
}
