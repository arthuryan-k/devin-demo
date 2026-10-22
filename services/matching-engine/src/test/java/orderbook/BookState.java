package orderbook;

import java.util.List;

/** Full-field snapshot of a book, for equality checks ({@link Order#equals} compares IDs only). */
public record BookState(List<OrderState> bids, List<OrderState> asks) {

    public record OrderState(long id, long participantId, Side side, OrderType type, TimeInForce tif, long price,
            long qty, long seqNum) {

        public static OrderState of(Order o) {
            return new OrderState(o.id(), o.participantId(), o.side(), o.type(), o.timeInForce(), o.price(),
                    o.qtyRemaining(), o.seqNum());
        }
    }

    public static BookState of(OrderBook book) {
        return new BookState(
                book.orders(Side.BUY).stream().map(OrderState::of).toList(),
                book.orders(Side.SELL).stream().map(OrderState::of).toList());
    }
}
