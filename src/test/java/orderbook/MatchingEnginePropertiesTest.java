package orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderPlaced;
import orderbook.Event.TradeExecuted;
import orderbook.OrderBook.Level;

class MatchingEnginePropertiesTest {

    /**
     * Per accepted order: resting + filled (+ cancelled, when cancels are generated) = submitted, and the book is
     * never crossed at rest. Checked after every command, not just at the end.
     */
    @Property(tries = 500)
    void quantityIsConservedAndBookNeverCrossed(@ForAll("commandsWithCancels") List<Command> commands) {
        replayAndCheck(commands);
    }

    /** The same invariant without cancels: sum(resting) + sum(filled) = sum(submitted) exactly. */
    @Property(tries = 500)
    void restingPlusFilledEqualsSubmitted(@ForAll("placesOnly") List<Command> commands) {
        Totals totals = replayAndCheck(commands);
        assertEquals(0, totals.cancelled);
        assertEquals(totals.submitted, totals.resting + totals.filled);
    }

    @Provide
    Arbitrary<List<Command>> commandsWithCancels() {
        return Arbitraries.frequencyOf(Tuple.of(4, places()), Tuple.of(1, cancels())).list().ofMaxSize(300);
    }

    @Provide
    Arbitrary<List<Command>> placesOnly() {
        return places().list().ofMaxSize(300);
    }

    private static Arbitrary<Long> ids() {
        return Arbitraries.longs().between(1, 80);
    }

    private static Arbitrary<Command> places() {
        Arbitrary<Long> prices = Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(95, 105)),
                Tuple.of(1, Arbitraries.longs().between(-3, 0)));
        Arbitrary<Long> qtys = Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(1, 100)),
                Tuple.of(1, Arbitraries.longs().between(-3, 0)));
        return Combinators.combine(ids(), Arbitraries.of(Side.class), prices, qtys)
                .as((id, side, price, qty) -> new Command.Place(new Order(id, side, price, qty)));
    }

    private static Arbitrary<Command> cancels() {
        return ids().map(Command.Cancel::new);
    }

    private static final class Totals {
        long submitted;
        long filled;
        long cancelled;
        long resting;
    }

    private static Totals replayAndCheck(List<Command> commands) {
        MatchingEngine engine = new MatchingEngine();
        OrderBook book = engine.book();
        Map<Long, OrderPlaced> placed = new HashMap<>();
        Map<Long, Long> filled = new HashMap<>();
        Map<Long, Long> cancelled = new HashMap<>();

        for (Command command : commands) {
            for (Event event : engine.process(command)) {
                if (event instanceof OrderPlaced p) {
                    assertFalse(placed.containsKey(p.orderId()), "id accepted twice: " + p.orderId());
                    placed.put(p.orderId(), p);
                } else if (event instanceof TradeExecuted t) {
                    checkTrade(t.trade(), placed);
                    filled.merge(t.trade().makerOrderId(), t.trade().qty(), Long::sum);
                    filled.merge(t.trade().takerOrderId(), t.trade().qty(), Long::sum);
                } else if (event instanceof OrderCancelled c) {
                    cancelled.merge(c.orderId(), c.cancelledQty(), Long::sum);
                }
            }
            assertFalse(book.isCrossed(), "crossed book after " + command);
            checkLevels(book.depth(Side.BUY, Integer.MAX_VALUE), true);
            checkLevels(book.depth(Side.SELL, Integer.MAX_VALUE), false);
        }

        Totals totals = new Totals();
        for (OrderPlaced p : placed.values()) {
            long resting = book.find(p.orderId()).map(Order::qtyRemaining).orElse(0L);
            long f = filled.getOrDefault(p.orderId(), 0L);
            long c = cancelled.getOrDefault(p.orderId(), 0L);
            assertEquals(p.qty(), resting + f + c, "conservation violated for order " + p.orderId());
            totals.submitted += p.qty();
            totals.resting += resting;
            totals.filled += f;
            totals.cancelled += c;
        }
        assertEquals(totals.submitted, totals.resting + totals.filled + totals.cancelled);
        assertEquals(book.size(), book.orders(Side.BUY).size() + book.orders(Side.SELL).size());
        return totals;
    }

    private static void checkTrade(Trade trade, Map<Long, OrderPlaced> placed) {
        OrderPlaced maker = placed.get(trade.makerOrderId());
        OrderPlaced taker = placed.get(trade.takerOrderId());
        assertTrue(trade.qty() > 0);
        assertEquals(maker.price(), trade.price(), "trade must execute at maker price");
        assertTrue(maker.seqNum() < taker.seqNum(), "maker must have rested before taker");
        assertTrue(maker.side() != taker.side());
        if (taker.side() == Side.BUY) {
            assertTrue(trade.price() <= taker.price());
        } else {
            assertTrue(trade.price() >= taker.price());
        }
    }

    private static void checkLevels(List<Level> levels, boolean descending) {
        for (int i = 0; i < levels.size(); i++) {
            assertTrue(levels.get(i).totalQty() > 0 && levels.get(i).orderCount() > 0, "empty level left in book");
            if (i > 0) {
                long prev = levels.get(i - 1).price();
                long cur = levels.get(i).price();
                assertTrue(descending ? prev > cur : prev < cur, "levels out of order");
            }
        }
    }
}
