package orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderPlaced;
import orderbook.Event.OrderRejected;
import orderbook.Event.OrderRejected.Reason;
import orderbook.Event.TradeExecuted;
import orderbook.OrderBook.Level;

class MatchingEngineTest {

    private MatchingEngine engine;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        book = engine.book();
    }

    private List<Event> buy(long id, long price, long qty) {
        return engine.process(new Command.Place(new Order(id, Side.BUY, price, qty)));
    }

    private List<Event> sell(long id, long price, long qty) {
        return engine.process(new Command.Place(new Order(id, Side.SELL, price, qty)));
    }

    private static List<Trade> trades(List<Event> events) {
        return events.stream()
                .filter(TradeExecuted.class::isInstance)
                .map(e -> ((TradeExecuted) e).trade())
                .toList();
    }

    private long resting(long id) {
        return book.find(id).map(Order::qtyRemaining).orElse(0L);
    }

    @Test
    void nonCrossingLimitOrderRestsAtCorrectLevel() {
        List<Event> bidEvents = buy(1, 99, 10);
        List<Event> askEvents = sell(2, 101, 20);

        assertEquals(List.of(new OrderPlaced(1, Side.BUY, 99, 10, 0)), bidEvents);
        assertEquals(List.of(new OrderPlaced(2, Side.SELL, 101, 20, 1)), askEvents);
        assertEquals(OptionalLong.of(99), book.bestBid());
        assertEquals(OptionalLong.of(101), book.bestAsk());
        assertEquals(List.of(new Level(99, 10, 1)), book.depth(Side.BUY, 5));
        assertEquals(List.of(new Level(101, 20, 1)), book.depth(Side.SELL, 5));
        assertFalse(book.isCrossed());
    }

    @Test
    void crossingBidFillsFullyAtAskPrice() {
        sell(1, 100, 50);
        List<Event> events = buy(2, 105, 50);

        assertEquals(List.of(new Trade(1, 2, 100, 50)), trades(events));
        assertTrue(book.isEmpty());
        assertTrue(book.bestBid().isEmpty());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void crossingAskFillsAtBidPrice() {
        buy(1, 100, 50);
        List<Event> events = sell(2, 95, 50);

        assertEquals(List.of(new Trade(1, 2, 100, 50)), trades(events));
        assertTrue(book.isEmpty());
    }

    @Test
    void partialFillRestsRemainder() {
        sell(1, 100, 60);
        List<Event> events = buy(2, 100, 100);

        assertEquals(List.of(new Trade(1, 2, 100, 60)), trades(events));
        assertFalse(book.contains(1));
        assertEquals(40, resting(2));
        assertEquals(OptionalLong.of(100), book.bestBid());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void partiallyFilledMakerKeepsPriority() {
        sell(1, 100, 100);
        sell(2, 100, 100);
        buy(3, 100, 30);
        List<Event> events = buy(4, 100, 100);

        assertEquals(List.of(new Trade(1, 4, 100, 70), new Trade(2, 4, 100, 30)), trades(events));
        assertEquals(70, resting(2));
    }

    @Test
    void fifoWithinPriceLevel() {
        sell(1, 100, 10);
        sell(2, 100, 10);
        sell(3, 100, 10);

        List<Event> events = buy(4, 100, 15);

        assertEquals(List.of(new Trade(1, 4, 100, 10), new Trade(2, 4, 100, 5)), trades(events));
        assertEquals(5, resting(2));
        assertEquals(10, resting(3));
        assertEquals(List.of(2L, 3L), book.orders(Side.SELL).stream().map(Order::id).toList());
    }

    @Test
    void aggressiveOrderSweepsMultipleLevelsInPriceOrder() {
        sell(1, 103, 10);
        sell(2, 101, 10);
        sell(3, 102, 10);
        sell(4, 101, 5);
        sell(5, 104, 10);

        List<Event> events = buy(6, 103, 100);

        assertEquals(List.of(
                new Trade(2, 6, 101, 10),
                new Trade(4, 6, 101, 5),
                new Trade(3, 6, 102, 10),
                new Trade(1, 6, 103, 10)), trades(events));
        assertEquals(65, resting(6));
        assertEquals(OptionalLong.of(103), book.bestBid());
        assertEquals(OptionalLong.of(104), book.bestAsk());
        assertEquals(List.of(new Level(104, 10, 1)), book.depth(Side.SELL, 10));
        assertFalse(book.isCrossed());
    }

    @Test
    void aggressiveSellSweepsBidsHighestFirst() {
        buy(1, 98, 10);
        buy(2, 100, 10);
        buy(3, 99, 10);

        List<Event> events = sell(4, 99, 25);

        assertEquals(List.of(new Trade(2, 4, 100, 10), new Trade(3, 4, 99, 10)), trades(events));
        assertEquals(5, resting(4));
        assertEquals(OptionalLong.of(98), book.bestBid());
        assertEquals(OptionalLong.of(99), book.bestAsk());
    }

    @Test
    void cancelRemovesOrder() {
        buy(1, 99, 10);
        buy(2, 99, 20);

        assertEquals(List.of(new OrderCancelled(1, 10)), engine.process(new Command.Cancel(1)));
        assertFalse(book.contains(1));
        assertEquals(List.of(new Level(99, 20, 1)), book.depth(Side.BUY, 5));

        assertEquals(List.of(new OrderCancelled(2, 20)), engine.cancel(2));
        assertTrue(book.isEmpty());
        assertTrue(book.bestBid().isEmpty());
        assertEquals(List.of(), book.depth(Side.BUY, 5));
    }

    @Test
    void cancelOfPartiallyFilledOrderCancelsRemainder() {
        buy(1, 100, 100);
        sell(2, 100, 60);

        assertEquals(List.of(new OrderCancelled(1, 40)), engine.cancel(1));
    }

    @Test
    void cancelOfNonexistentIdIsRejected() {
        assertEquals(List.of(new OrderRejected(42, Reason.UNKNOWN_ORDER_ID)), engine.cancel(42));
    }

    @Test
    void cancelOfFilledOrAlreadyCancelledIdIsRejected() {
        sell(1, 100, 10);
        buy(2, 100, 10);
        buy(3, 90, 10);
        engine.cancel(3);

        assertEquals(List.of(new OrderRejected(1, Reason.UNKNOWN_ORDER_ID)), engine.cancel(1));
        assertEquals(List.of(new OrderRejected(2, Reason.UNKNOWN_ORDER_ID)), engine.cancel(2));
        assertEquals(List.of(new OrderRejected(3, Reason.UNKNOWN_ORDER_ID)), engine.cancel(3));
    }

    @Test
    void rejectsNonPositiveQuantityAndPrice() {
        assertEquals(List.of(new OrderRejected(1, Reason.NON_POSITIVE_QUANTITY)), buy(1, 100, 0));
        assertEquals(List.of(new OrderRejected(2, Reason.NON_POSITIVE_QUANTITY)), buy(2, 100, -5));
        assertEquals(List.of(new OrderRejected(3, Reason.NON_POSITIVE_PRICE)), sell(3, 0, 10));
        assertEquals(List.of(new OrderRejected(4, Reason.NON_POSITIVE_PRICE)), sell(4, -1, 10));
        assertTrue(book.isEmpty());
    }

    @Test
    void rejectsDuplicateOrderId() {
        buy(1, 99, 10);
        assertEquals(List.of(new OrderRejected(1, Reason.DUPLICATE_ORDER_ID)), sell(1, 101, 10));
        assertEquals(1, book.size());
    }

    @Test
    void rejectsReuseOfFilledOrderId() {
        sell(1, 100, 10);
        buy(2, 100, 10);
        assertEquals(List.of(new OrderRejected(1, Reason.DUPLICATE_ORDER_ID)), sell(1, 100, 10));
        assertTrue(book.isEmpty());
    }

    @Test
    void rejectedOrderIdCanBeUsedLater() {
        buy(1, 100, 0);
        assertEquals(List.of(new OrderPlaced(1, Side.BUY, 100, 10, 0)), buy(1, 100, 10));
    }

    @Test
    void callerOrderIsNotMutatedByMatching() {
        sell(1, 100, 10);
        Order request = new Order(2, Side.BUY, 100, 25);
        engine.placeLimit(request);

        assertEquals(25, request.qtyRemaining());
        assertEquals(Order.UNASSIGNED_SEQ, request.seqNum());
        assertEquals(15, resting(2));
    }

    @Test
    void seqNumIsMonotonicAndAssignedOnlyToAcceptedOrders() {
        buy(1, 99, 10);
        buy(2, 99, 0);
        buy(3, 99, 10);

        assertEquals(0, book.find(1).orElseThrow().seqNum());
        assertEquals(1, book.find(3).orElseThrow().seqNum());
    }

    @Test
    void queriesOnEmptyBookReturnEmpty() {
        assertTrue(book.bestBid().isEmpty());
        assertTrue(book.bestAsk().isEmpty());
        assertTrue(book.find(1).isEmpty());
        OrderBook.Depth depth = book.depth(5);
        assertEquals(List.of(), depth.bids());
        assertEquals(List.of(), depth.asks());
        assertEquals(List.of(), book.depth(Side.BUY, 0));
        assertEquals(List.of(), book.depth(Side.SELL, -1));
    }

    @Test
    void depthAggregatesAndLimitsLevels() {
        buy(1, 99, 10);
        buy(2, 99, 5);
        buy(3, 98, 7);
        buy(4, 97, 1);

        assertEquals(List.of(new Level(99, 15, 2), new Level(98, 7, 1)), book.depth(Side.BUY, 2));
    }

    @Test
    void orderEqualityIsById() {
        Order a = new Order(7, Side.BUY, 100, 10);
        Order b = new Order(7, Side.SELL, 200, 99);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
