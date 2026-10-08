package orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import orderbook.Event.OrderAmended;
import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderCancelled.CancelReason;
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

    private List<Event> place(Order order) {
        return engine.process(new Command.Place(order));
    }

    private List<Event> amend(long id, Long price, Long qty) {
        return engine.process(new Command.Amend(id, price, qty));
    }

    private List<Long> ids(Side side) {
        return book.orders(side).stream().map(Order::id).toList();
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

    // ---- amend / replace ----

    @Test
    void amendQuantityDecreaseKeepsFifoPosition() {
        sell(1, 100, 10);
        sell(2, 100, 10);
        sell(3, 100, 10);

        assertEquals(List.of(new OrderAmended(1, 100, 100, 10, 4, 0, true)), amend(1, null, 4L));
        assertEquals(List.of(1L, 2L, 3L), ids(Side.SELL));
        assertEquals(0, book.find(1).orElseThrow().seqNum());
        assertEquals(new Level(100, 24, 3), book.depth(Side.SELL, 1).get(0));

        assertEquals(List.of(new Trade(1, 4, 100, 4), new Trade(2, 4, 100, 2)), trades(buy(4, 100, 6)));
    }

    @Test
    void amendQuantityIncreaseLosesPriority() {
        sell(1, 100, 10);
        sell(2, 100, 10);
        sell(3, 100, 10);

        assertEquals(List.of(new OrderAmended(1, 100, 100, 10, 20, 3, false)), amend(1, null, 20L));
        assertEquals(List.of(2L, 3L, 1L), ids(Side.SELL));
        assertEquals(List.of(new Trade(2, 4, 100, 10), new Trade(3, 4, 100, 5)), trades(buy(4, 100, 15)));
    }

    @Test
    void amendPriceChangeLosesPriorityEvenWithQuantityDecrease() {
        sell(1, 100, 10);
        sell(2, 101, 10);

        assertEquals(List.of(new OrderAmended(1, 100, 101, 10, 5, 2, false)), amend(1, 101L, 5L));
        assertEquals(List.of(2L, 1L), ids(Side.SELL));
        assertEquals(List.of(new Level(101, 15, 2)), book.depth(Side.SELL, 5));
    }

    @Test
    void amendPriceBackToOriginalLevelGoesToBackOfQueue() {
        buy(1, 100, 10);
        buy(2, 100, 10);

        amend(1, 99L, null);
        amend(1, 100L, null);

        assertEquals(List.of(2L, 1L), ids(Side.BUY));
    }

    @Test
    void amendWithNoChangeKeepsPriority() {
        buy(1, 100, 10);
        buy(2, 100, 10);

        assertEquals(List.of(new OrderAmended(1, 100, 100, 10, 10, 0, true)), amend(1, 100L, 10L));
        assertEquals(List.of(new OrderAmended(1, 100, 100, 10, 10, 0, true)), amend(1, null, null));
        assertEquals(List.of(1L, 2L), ids(Side.BUY));
    }

    @Test
    void amendQuantityIsRemainingQuantityOfPartiallyFilledOrder() {
        sell(1, 100, 10);
        buy(2, 100, 4);

        assertEquals(List.of(new OrderAmended(1, 100, 100, 6, 3, 0, true)), amend(1, null, 3L));
        assertEquals(3, resting(1));
    }

    @Test
    void amendToCrossingPriceMatchesAsAggressor() {
        buy(1, 99, 10);
        sell(2, 101, 4);

        List<Event> events = amend(1, 101L, null);

        assertEquals(new OrderAmended(1, 99, 101, 10, 10, 2, false), events.get(0));
        assertEquals(List.of(new Trade(2, 1, 101, 4)), trades(events));
        assertEquals(6, resting(1));
        assertEquals(OptionalLong.of(101), book.bestBid());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void amendRejectsUnknownFilledOrCancelledIdAndInvalidValues() {
        sell(1, 100, 10);
        buy(2, 100, 10);
        buy(3, 90, 10);
        engine.cancel(3);
        buy(4, 95, 10);
        BookState before = BookState.of(book);

        assertEquals(List.of(new OrderRejected(42, Reason.UNKNOWN_ORDER_ID)), amend(42, 100L, null));
        assertEquals(List.of(new OrderRejected(1, Reason.UNKNOWN_ORDER_ID)), amend(1, null, 5L));
        assertEquals(List.of(new OrderRejected(3, Reason.UNKNOWN_ORDER_ID)), amend(3, null, 5L));
        assertEquals(List.of(new OrderRejected(4, Reason.NON_POSITIVE_QUANTITY)), amend(4, null, 0L));
        assertEquals(List.of(new OrderRejected(4, Reason.NON_POSITIVE_QUANTITY)), amend(4, 96L, -1L));
        assertEquals(List.of(new OrderRejected(4, Reason.NON_POSITIVE_PRICE)), amend(4, 0L, null));
        assertEquals(List.of(new OrderRejected(4, Reason.NON_POSITIVE_PRICE)), amend(4, -5L, 5L));
        assertEquals(before, BookState.of(book));
    }

    // ---- market / IOC / FOK ----

    @Test
    void marketOrderSweepsAllLevelsAtAnyPrice() {
        sell(1, 101, 10);
        sell(2, 105, 10);
        sell(3, 102, 10);

        List<Event> events = place(Order.market(4, 0, Side.BUY, 25));

        assertEquals(new OrderPlaced(4, Side.BUY, 0, 25, 3), events.get(0));
        assertEquals(List.of(new Trade(1, 4, 101, 10), new Trade(3, 4, 102, 10), new Trade(2, 4, 105, 5)),
                trades(events));
        assertEquals(4, events.size());
        assertFalse(book.contains(4));
        assertEquals(List.of(new Level(105, 5, 1)), book.depth(Side.SELL, 5));
    }

    @Test
    void marketOrderRemainderIsCancelledAndNeverRests() {
        buy(1, 100, 10);
        buy(2, 90, 5);

        List<Event> events = place(Order.market(3, 0, Side.SELL, 30));

        assertEquals(List.of(new Trade(1, 3, 100, 10), new Trade(2, 3, 90, 5)), trades(events));
        assertEquals(new OrderCancelled(3, 15, CancelReason.UNFILLED_REMAINDER), events.get(events.size() - 1));
        assertTrue(book.isEmpty());
    }

    @Test
    void marketOrderOnEmptyBookIsCancelledInFull() {
        assertEquals(List.of(new OrderPlaced(1, Side.BUY, 0, 10, 0),
                        new OrderCancelled(1, 10, CancelReason.UNFILLED_REMAINDER)),
                place(Order.market(1, 0, Side.BUY, 10)));
        assertTrue(book.isEmpty());
    }

    @Test
    void marketOrderIgnoresPriceAndGtc() {
        sell(1, 100, 5);
        List<Event> events = place(new Order(2, 0, Side.BUY, OrderType.MARKET, TimeInForce.GTC, -7, 8));

        assertEquals(new OrderPlaced(2, Side.BUY, 0, 8, 1), events.get(0));
        assertEquals(List.of(new Trade(1, 2, 100, 5)), trades(events));
        assertEquals(new OrderCancelled(2, 3, CancelReason.UNFILLED_REMAINDER), events.get(2));
        assertTrue(book.isEmpty());
    }

    @Test
    void marketOrderStillRejectsNonPositiveQuantity() {
        assertEquals(List.of(new OrderRejected(1, Reason.NON_POSITIVE_QUANTITY)),
                place(Order.market(1, 0, Side.BUY, 0)));
    }

    @Test
    void iocCrossesLikeLimitAndCancelsRemainder() {
        sell(1, 100, 10);
        sell(2, 102, 10);

        List<Event> events = place(Order.limit(3, 0, Side.BUY, 101, 25, TimeInForce.IOC));

        assertEquals(List.of(new OrderPlaced(3, Side.BUY, 101, 25, 2),
                new TradeExecuted(new Trade(1, 3, 100, 10)),
                new OrderCancelled(3, 15, CancelReason.UNFILLED_REMAINDER)), events);
        assertTrue(book.bestBid().isEmpty());
        assertEquals(List.of(new Level(102, 10, 1)), book.depth(Side.SELL, 5));
    }

    @Test
    void nonCrossingIocIsCancelledEntirely() {
        sell(1, 100, 10);
        List<Event> events = place(Order.limit(2, 0, Side.BUY, 99, 5, TimeInForce.IOC));

        assertEquals(List.of(new OrderPlaced(2, Side.BUY, 99, 5, 1),
                new OrderCancelled(2, 5, CancelReason.UNFILLED_REMAINDER)), events);
        assertEquals(1, book.size());
    }

    @Test
    void fullyFilledIocEmitsNoCancel() {
        sell(1, 100, 10);
        List<Event> events = place(Order.limit(2, 0, Side.BUY, 100, 10, TimeInForce.IOC));

        assertEquals(2, events.size());
        assertTrue(book.isEmpty());
    }

    @Test
    void fokFillsFullyAcrossLevels() {
        sell(1, 100, 10);
        sell(2, 101, 10);

        List<Event> events = place(Order.limit(3, 0, Side.BUY, 101, 15, TimeInForce.FOK));

        assertEquals(new OrderPlaced(3, Side.BUY, 101, 15, 2), events.get(0));
        assertEquals(List.of(new Trade(1, 3, 100, 10), new Trade(2, 3, 101, 5)), trades(events));
        assertEquals(3, events.size());
        assertEquals(5, resting(2));
    }

    @Test
    void fokThatCannotFillIsRejectedWithNoTradesAndNoBookMutation() {
        sell(1, 100, 10);
        sell(2, 101, 10);
        sell(3, 102, 50);
        BookState before = BookState.of(book);
        long seqBefore = engine.nextSeqNum();

        assertEquals(List.of(new OrderRejected(4, Reason.FOK_NOT_FILLABLE)),
                place(Order.limit(4, 0, Side.BUY, 101, 25, TimeInForce.FOK)));

        assertEquals(before, BookState.of(book));
        assertEquals(seqBefore, engine.nextSeqNum());
        assertEquals(new OrderPlaced(4, Side.BUY, 101, 5, seqBefore), buy(4, 101, 5).get(0));
    }

    @Test
    void marketFokIsAllOrNothing() {
        sell(1, 100, 10);
        sell(2, 150, 10);
        BookState before = BookState.of(book);

        assertEquals(List.of(new OrderRejected(3, Reason.FOK_NOT_FILLABLE)),
                place(Order.market(3, 0, Side.BUY, 21, TimeInForce.FOK)));
        assertEquals(before, BookState.of(book));

        List<Event> events = place(Order.market(4, 0, Side.BUY, 20, TimeInForce.FOK));
        assertEquals(List.of(new Trade(1, 4, 100, 10), new Trade(2, 4, 150, 10)), trades(events));
        assertTrue(book.isEmpty());
    }

    @Test
    void fokBlockedBySelfTradePreventionIsRejected() {
        place(Order.limit(1, 7, Side.SELL, 100, 10));
        place(Order.limit(2, 8, Side.SELL, 100, 10));
        BookState before = BookState.of(book);

        assertEquals(List.of(new OrderRejected(3, Reason.FOK_NOT_FILLABLE)),
                place(Order.limit(3, 7, Side.BUY, 100, 10, TimeInForce.FOK)));
        assertEquals(before, BookState.of(book));
    }

    // ---- self-trade prevention ----

    @Test
    void selfTradeCancelsTakerAndLeavesMaker() {
        place(Order.limit(1, 7, Side.SELL, 100, 10));

        List<Event> events = place(Order.limit(2, 7, Side.BUY, 100, 5));

        assertEquals(List.of(new OrderPlaced(2, Side.BUY, 100, 5, 1),
                new OrderCancelled(2, 5, CancelReason.SELF_TRADE_PREVENTION)), events);
        assertEquals(10, resting(1));
        assertFalse(book.contains(2));
    }

    @Test
    void selfTradeStopsMatchingAfterEarlierFills() {
        place(Order.limit(1, 8, Side.SELL, 100, 5));
        place(Order.limit(2, 7, Side.SELL, 100, 10));
        place(Order.limit(3, 8, Side.SELL, 101, 10));

        List<Event> events = place(Order.limit(4, 7, Side.BUY, 101, 20));

        assertEquals(List.of(new Trade(1, 4, 100, 5)), trades(events));
        assertEquals(new OrderCancelled(4, 15, CancelReason.SELF_TRADE_PREVENTION), events.get(events.size() - 1));
        assertEquals(List.of(2L, 3L), ids(Side.SELL));
        assertTrue(book.bestBid().isEmpty());
    }

    @Test
    void selfTradePreventionAppliesToMarketOrders() {
        place(Order.limit(1, 7, Side.BUY, 100, 10));
        List<Event> events = place(Order.market(2, 7, Side.SELL, 10));

        assertEquals(new OrderCancelled(2, 10, CancelReason.SELF_TRADE_PREVENTION), events.get(1));
        assertEquals(10, resting(1));
    }

    @Test
    void selfTradePreventionAppliesToAmendReentry() {
        place(Order.limit(1, 7, Side.BUY, 99, 10));
        place(Order.limit(2, 7, Side.SELL, 101, 10));

        List<Event> events = amend(1, 101L, null);

        assertEquals(List.of(new OrderAmended(1, 99, 101, 10, 10, 2, false),
                new OrderCancelled(1, 10, CancelReason.SELF_TRADE_PREVENTION)), events);
        assertEquals(List.of(), ids(Side.BUY));
        assertEquals(List.of(2L), ids(Side.SELL));
    }

    @Test
    void differentAndAnonymousParticipantsTrade() {
        place(Order.limit(1, 7, Side.SELL, 100, 10));
        assertEquals(List.of(new Trade(1, 2, 100, 4)), trades(place(Order.limit(2, 8, Side.BUY, 100, 4))));

        sell(3, 100, 10);
        assertEquals(List.of(new Trade(1, 4, 100, 6), new Trade(3, 4, 100, 4)), trades(buy(4, 100, 10)));
    }

    // ---- counters ----

    @Test
    void nextOrderIdIsDerivedFromMaxSeenPlaceId() {
        assertEquals(1, engine.nextOrderId());
        buy(5, 100, 10);
        buy(9, 100, 0);
        engine.cancel(50);
        assertEquals(10, engine.nextOrderId());
    }
}
