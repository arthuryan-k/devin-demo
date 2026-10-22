package orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import orderbook.Event.OrderAmended;
import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderPlaced;
import orderbook.Event.OrderRejected;
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

    /**
     * Full command set (limit/market, GTC/IOC/FOK, participants, cancels, amends). On top of conservation and
     * no-crossing: only GTC limits rest, an accepted FOK fills completely, rejected commands leave the book
     * untouched, no trade pairs two orders of the same participant, and a priority-retaining amend keeps its seqNum.
     */
    @Property(tries = 500)
    void invariantsHoldForFullCommandSet(@ForAll("allCommands") List<Command> commands) {
        replayAndCheck(commands);
    }

    @Provide
    Arbitrary<List<Command>> commandsWithCancels() {
        return Arbitraries.frequencyOf(Tuple.of(4, CommandArbitraries.gtcLimitPlaces()),
                Tuple.of(1, CommandArbitraries.cancels())).list().ofMaxSize(300);
    }

    @Provide
    Arbitrary<List<Command>> placesOnly() {
        return CommandArbitraries.gtcLimitPlaces().list().ofMaxSize(300);
    }

    @Provide
    Arbitrary<List<Command>> allCommands() {
        return CommandArbitraries.allCommands(300);
    }

    private static final class Totals {
        long submitted;
        long filled;
        long cancelled;
        long resting;
    }

    /** What the test knows about an accepted order, updated as events arrive. */
    private static final class Tracked {
        final Order request;
        long submitted;
        long price;
        long seqNum;
        long filled;
        long cancelled;

        Tracked(Order request, OrderPlaced placed) {
            this.request = request;
            this.submitted = placed.qty();
            this.price = placed.price();
            this.seqNum = placed.seqNum();
        }
    }

    private static Totals replayAndCheck(List<Command> commands) {
        MatchingEngine engine = new MatchingEngine();
        OrderBook book = engine.book();
        Map<Long, Tracked> orders = new HashMap<>();

        for (Command command : commands) {
            BookState before = BookState.of(book);
            List<Event> events = engine.process(command);
            for (Event event : events) {
                if (event instanceof OrderPlaced p) {
                    assertFalse(orders.containsKey(p.orderId()), "id accepted twice: " + p.orderId());
                    orders.put(p.orderId(), new Tracked(((Command.Place) command).order(), p));
                } else if (event instanceof OrderAmended a) {
                    Tracked t = orders.get(a.orderId());
                    assertEquals(t.price, a.oldPrice());
                    if (a.priorityRetained()) {
                        assertEquals(t.seqNum, a.seqNum(), "priority-retaining amend changed seqNum");
                        assertEquals(a.oldPrice(), a.newPrice());
                        assertTrue(a.newQty() <= a.oldQty());
                    } else {
                        assertTrue(a.seqNum() > t.seqNum);
                    }
                    t.submitted += a.newQty() - a.oldQty();
                    t.price = a.newPrice();
                    t.seqNum = a.seqNum();
                } else if (event instanceof TradeExecuted e) {
                    checkTrade(e.trade(), orders);
                    orders.get(e.trade().makerOrderId()).filled += e.trade().qty();
                    orders.get(e.trade().takerOrderId()).filled += e.trade().qty();
                } else if (event instanceof OrderCancelled c) {
                    orders.get(c.orderId()).cancelled += c.cancelledQty();
                } else if (event instanceof OrderRejected) {
                    assertEquals(1, events.size(), "rejection must be the only event");
                    assertEquals(before, BookState.of(book), "rejected command mutated the book");
                }
            }
            if (command instanceof Command.Place place && events.get(0) instanceof OrderPlaced) {
                Order request = place.order();
                if (!(request.type() == OrderType.LIMIT && request.timeInForce() == TimeInForce.GTC)) {
                    assertFalse(book.contains(request.id()), "non-GTC order rested: " + request);
                }
                if (request.timeInForce() == TimeInForce.FOK) {
                    assertEquals(request.qtyRemaining(), orders.get(request.id()).filled, "FOK partially filled");
                }
            }
            assertFalse(book.isCrossed(), "crossed book after " + command);
            checkLevels(book.depth(Side.BUY, Integer.MAX_VALUE), true);
            checkLevels(book.depth(Side.SELL, Integer.MAX_VALUE), false);
        }

        Totals totals = new Totals();
        for (Map.Entry<Long, Tracked> entry : orders.entrySet()) {
            Tracked t = entry.getValue();
            long resting = book.find(entry.getKey()).map(Order::qtyRemaining).orElse(0L);
            assertEquals(t.submitted, resting + t.filled + t.cancelled,
                    "conservation violated for order " + entry.getKey());
            totals.submitted += t.submitted;
            totals.resting += resting;
            totals.filled += t.filled;
            totals.cancelled += t.cancelled;
        }
        assertEquals(totals.submitted, totals.resting + totals.filled + totals.cancelled);
        assertEquals(book.size(), book.orders(Side.BUY).size() + book.orders(Side.SELL).size());
        return totals;
    }

    private static void checkTrade(Trade trade, Map<Long, Tracked> orders) {
        Tracked maker = orders.get(trade.makerOrderId());
        Tracked taker = orders.get(trade.takerOrderId());
        assertTrue(trade.qty() > 0);
        assertEquals(maker.price, trade.price(), "trade must execute at maker price");
        assertTrue(maker.seqNum < taker.seqNum, "maker must have rested before taker");
        assertTrue(maker.request.side() != taker.request.side());
        if (taker.request.participantId() != Order.NO_PARTICIPANT) {
            assertNotEquals(taker.request.participantId(), maker.request.participantId(), "self-trade executed");
        }
        if (taker.request.type() == OrderType.LIMIT) {
            if (taker.request.side() == Side.BUY) {
                assertTrue(trade.price() <= taker.price);
            } else {
                assertTrue(trade.price() >= taker.price);
            }
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
