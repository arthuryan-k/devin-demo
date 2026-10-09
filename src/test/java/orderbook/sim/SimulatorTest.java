package orderbook.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.api.AdmissionControl;
import orderbook.api.ExchangeService;
import orderbook.api.InProcessExchangeClient;

class SimulatorTest {

    private record Submitted(long participantId, Command command, List<Event> events) {
    }

    /** Steps run back to back in wall-clock time, so rate limits are lifted here; ParticipantApiTest covers them. */
    private static final AdmissionControl.Limits UNTHROTTLED = new AdmissionControl.Limits(1e9, 1_000_000, 50,
            1_000_000);

    private final List<Simulator> sims = new ArrayList<>();

    private Simulator sim(long seed) {
        Simulator sim = new Simulator(seed);
        ExchangeService exchange = new ExchangeService(sim, UNTHROTTLED, new Random(seed));
        sim.connect(new InProcessExchangeClient(exchange), ClientLoop.INLINE);
        sims.add(sim);
        return sim;
    }

    private static List<Submitted> record(Simulator sim) {
        List<Submitted> log = Collections.synchronizedList(new ArrayList<>());
        sim.addListener((participant, command, events) -> log.add(new Submitted(participant, command, events)));
        return log;
    }

    @AfterEach
    void closeAll() {
        sims.forEach(Simulator::close);
    }

    private static List<Order> restingOf(Simulator sim, long participantId) {
        return sim.execute(() -> {
            List<Order> own = new ArrayList<>();
            for (Side side : Side.values()) {
                for (Order o : sim.engine().book().orders(side)) {
                    if (o.participantId() == participantId) {
                        own.add(o);
                    }
                }
            }
            return own;
        });
    }

    private static int nonUserResting(Simulator sim) {
        return sim.execute(() -> {
            int n = 0;
            for (Side side : Side.values()) {
                for (Order o : sim.engine().book().orders(side)) {
                    if (o.participantId() != Simulator.USER_PARTICIPANT_ID) {
                        n++;
                    }
                }
            }
            return n;
        });
    }

    @Test
    void startRunsTheLoopAndStopCancelsEverySimulatedOrder() throws Exception {
        Simulator sim = sim(42);
        List<Submitted> log = record(sim);
        sim.submit(Simulator.USER_PARTICIPANT_ID, new Command.Place(
                Order.limit(1_000_000, Simulator.USER_PARTICIPANT_ID, Side.BUY, 50_00, 1, TimeInForce.GTC)));
        sim.setRate(100);
        sim.start();
        sim.start(); // idempotent
        assertTrue(sim.isRunning());
        int n = sim.participants().size();
        assertTrue(n >= Simulator.MIN_PARTICIPANTS && n <= Simulator.MAX_PARTICIPANTS, "participants " + n);

        long deadline = System.currentTimeMillis() + 5_000;
        while (nonUserResting(sim) < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(nonUserResting(sim) >= 5, "simulation placed orders");

        sim.stop();
        assertFalse(sim.isRunning());
        assertTrue(sim.participants().isEmpty());
        assertEquals(0, nonUserResting(sim));
        for (Submitted s : log) {
            if (s.command() instanceof Command.Cancel c) {
                assertTrue(c.orderId() != 1_000_000, "stop never cancels the user's order");
            }
            assertTrue(s.participantId() != Simulator.USER_PARTICIPANT_ID || s.command() instanceof Command.Place);
        }
        Thread.sleep(100);
        assertEquals(0, nonUserResting(sim), "no arrivals after stop");
    }

    @Test
    void rateIsValidatedAndDrivesExponentialInterArrivals() {
        Simulator sim = sim(7);
        assertThrows(IllegalArgumentException.class, () -> sim.setRate(0));
        assertThrows(IllegalArgumentException.class, () -> sim.setRate(-1));
        assertThrows(IllegalArgumentException.class, () -> sim.setRate(Simulator.MAX_RATE + 1));
        assertThrows(IllegalArgumentException.class, () -> sim.setRate(Double.NaN));

        sim.setRate(10);
        assertEquals(10, sim.rate());
        double[] stats = sim.execute(() -> {
            int n = 50_000;
            double sum = 0;
            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double d = sim.nextDelay();
                assertTrue(d >= 0);
                sum += d;
                sumSq += d * d;
            }
            double mean = sum / n;
            return new double[] {mean, Math.sqrt(sumSq / n - mean * mean)};
        });
        assertEquals(0.1, stats[0], 0.003, "mean 1/rate");
        assertEquals(0.1, stats[1], 0.005, "exponential: std dev equals mean");
    }

    @Test
    void exitCancelsTheParticipantsRestingOrdersFirst() {
        Simulator sim = sim(3);
        List<Submitted> log = record(sim);
        Participant mm = sim.execute(() -> sim.addParticipant(Persona.Kind.MARKET_MAKER, 0.5, 0));
        sim.run(() -> {
            for (int i = 0; i < 10; i++) {
                sim.act(mm);
            }
        });
        List<Order> before = restingOf(sim, mm.id());
        assertFalse(before.isEmpty());
        log.clear();

        sim.run(() -> sim.exit(mm));
        assertTrue(restingOf(sim, mm.id()).isEmpty());
        assertFalse(sim.participants().stream().anyMatch(p -> p.id() == mm.id()));
        Set<Long> cancelled = new HashSet<>();
        for (Submitted s : log) {
            assertEquals(mm.id(), s.participantId());
            cancelled.add(((Command.Cancel) s.command()).orderId());
        }
        assertEquals(before.stream().map(Order::id).collect(java.util.stream.Collectors.toSet()), cancelled);
    }

    @Test
    void churnKeepsBetweenTwoAndEightParticipants() {
        Simulator sim = sim(11);
        sim.run(sim::populate);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            sim.runSteps(25);
            int n = sim.participants().size();
            assertTrue(n >= Simulator.MIN_PARTICIPANTS && n <= Simulator.MAX_PARTICIPANTS, "participants " + n);
            sim.participants().forEach(p -> seen.add(p.id()));
            // Whales trigger shocks on their own; also force some so post-shock exits are exercised.
            if (i % 50 == 0) {
                sim.run(() -> sim.reference().startShock());
            }
        }
        assertTrue(seen.size() > Simulator.INITIAL_PARTICIPANTS, "participants came and went: " + seen.size());
    }

    @Test
    void shockRaisesExitProbabilityAndWidensQuotes() {
        Simulator sim = sim(5);
        sim.run(() -> {
            Participant cautious = sim.addParticipant(Persona.Kind.MARKET_MAKER, 0.1, 0);
            Participant bold = sim.addParticipant(Persona.Kind.MARKET_MAKER, 0.9, 0);
            double calmCautious = sim.exitHazard(cautious);
            double calmBold = sim.exitHazard(bold);
            assertTrue(calmCautious > calmBold, "cautious participants exit more readily");

            sim.reference().startShock();
            assertEquals(1, sim.reference().shockIntensity(), 1e-9);
            assertTrue(sim.exitHazard(cautious) > 3 * calmCautious);
            assertTrue(sim.exitHazard(cautious) / calmCautious > sim.exitHazard(bold) / calmBold,
                    "shock sensitivity scales with caution");

            sim.reference().advance(ReferencePrice.SHOCK_COOLDOWN_SEC + 1, new Random(0));
            assertEquals(calmCautious, sim.exitHazard(cautious), 1e-12, "shock decays after the cooldown");
        });

        for (double rt : new double[] {0, 0.3, 0.7, 1}) {
            assertTrue(MarketMaker.halfSpread(rt, 1) > MarketMaker.halfSpread(rt, 0), "shock widens at rt " + rt);
        }
        assertTrue(MarketMaker.halfSpread(0, 0) > MarketMaker.halfSpread(1, 0), "cautious makers quote wider");
        assertTrue(MarketMaker.halfSpread(0, 1) - MarketMaker.halfSpread(0, 0)
                > MarketMaker.halfSpread(1, 1) - MarketMaker.halfSpread(1, 0));
    }

    @Test
    void shockedMakerQuotesSitFurtherFromReference() {
        Simulator calm = sim(9);
        Simulator shocked = sim(9);
        shocked.run(() -> shocked.reference().startShock());
        assertTrue(meanQuoteDistance(shocked) > meanQuoteDistance(calm) + 3);
    }

    private static double meanQuoteDistance(Simulator sim) {
        Participant mm = sim.execute(() -> sim.addParticipant(Persona.Kind.MARKET_MAKER, 0.5, 0));
        sim.run(() -> {
            for (int i = 0; i < 6; i++) {
                sim.act(mm);
            }
        });
        long ref = sim.execute(() -> sim.reference().ticks());
        return restingOf(sim, mm.id()).stream().mapToLong(o -> Math.abs(o.price() - ref)).average().orElseThrow();
    }

    @Test
    void makerRequotesWhenReferenceDrifts() {
        Simulator sim = sim(13);
        List<Submitted> log = record(sim);
        Participant mm = sim.execute(() -> sim.addParticipant(Persona.Kind.MARKET_MAKER, 0.5, 0));
        sim.run(() -> {
            for (int i = 0; i < 6; i++) {
                sim.act(mm);
            }
        });
        log.clear();
        long newRef = 103_00;
        sim.run(() -> {
            sim.reference().setTicks(newRef);
            for (int i = 0; i < 30; i++) {
                sim.act(mm);
            }
        });
        assertTrue(log.stream().anyMatch(s -> s.command() instanceof Command.Amend), "stale quotes are amended");
        for (Order o : restingOf(sim, mm.id())) {
            assertEquals(0, MarketMaker.staleness(o.side(), o.price(), newRef,
                    MarketMaker.halfSpread(0.5, 0)), "quote " + o + " follows the reference");
        }
    }

    @Test
    void makerReseedsAnEmptySide() {
        Simulator sim = sim(17);
        List<Submitted> log = record(sim);
        Participant mm = sim.execute(() -> sim.addParticipant(Persona.Kind.MARKET_MAKER, 0.5, 0));
        sim.submit(1001_000L, new Command.Place(Order.limit(9_999_999, 1001_000L, Side.BUY, 99_00, 5, TimeInForce.GTC)));
        log.clear();
        sim.run(() -> sim.act(mm));
        Command.Place place = (Command.Place) log.get(0).command();
        assertEquals(Side.SELL, place.order().side(), "fills the empty ask side first");
    }

    @Test
    void inventorySkewsQuoteSizesAgainstPosition() {
        assertTrue(MarketMaker.skewedSize(10, Side.BUY, 40, 0.5) < 10, "long: smaller bids");
        assertTrue(MarketMaker.skewedSize(10, Side.SELL, 40, 0.5) > 10, "long: larger asks");
        assertTrue(MarketMaker.skewedSize(10, Side.BUY, -40, 0.5) > 10, "short: larger bids");
        assertTrue(MarketMaker.skewedSize(10, Side.SELL, -40, 0.5) < 10, "short: smaller asks");
        assertEquals(10, MarketMaker.skewedSize(10, Side.BUY, 0, 0.5));
        assertTrue(MarketMaker.skewedSize(20, Side.SELL, 40, 0.0) > MarketMaker.skewedSize(20, Side.SELL, 40, 1.0),
                "cautious makers skew harder");

        MarketMaker mm = new MarketMaker(0.5);
        mm.onFill(Side.BUY, 30);
        mm.onFill(Side.SELL, 10);
        assertEquals(20, mm.inventory(), "signed own-fill counter");
    }

    @Test
    void takerDirectionLeansTowardRecentDrift() {
        Random random = new Random(1);
        int n = 200_000;
        int up = 0;
        int down = 0;
        int flat = 0;
        for (int i = 0; i < n; i++) {
            up += AggressiveTaker.chooseSide(0.5, random) == Side.BUY ? 1 : 0;
            down += AggressiveTaker.chooseSide(-0.5, random) == Side.BUY ? 1 : 0;
            flat += AggressiveTaker.chooseSide(0, random) == Side.BUY ? 1 : 0;
        }
        assertEquals(0.55, up / (double) n, 0.005);
        assertEquals(0.45, down / (double) n, 0.005);
        assertEquals(0.50, flat / (double) n, 0.005);
    }

    @Test
    void sameSeedReproducesTheSameCommandStream() {
        assertEquals(transcript(123), transcript(123));
        assertNotEquals(transcript(123), transcript(124));
    }

    private List<String> transcript(long seed) {
        Simulator sim = sim(seed);
        List<Submitted> log = record(sim);
        sim.run(sim::populate);
        sim.runSteps(3_000);
        List<String> out = new ArrayList<>();
        for (Submitted s : log) {
            out.add(s.participantId() + " " + s.command() + " " + s.events());
        }
        return out;
    }

    @Test
    void simulatedFlowIssuesOnlyLegalCommandsAndNeverActsAsYou() {
        Simulator sim = sim(2024);
        List<Submitted> log = record(sim);
        Set<Long> ownedSeen = new HashSet<>();
        sim.addListener((participant, command, events) -> {
            if (command instanceof Command.Cancel c) {
                assertEquals(participant, sim.participantOf(c.orderId()), "cancels only own orders");
            } else if (command instanceof Command.Amend a) {
                assertEquals(participant, sim.participantOf(a.orderId()), "amends only own orders");
            }
            ownedSeen.add(participant);
        });
        sim.run(sim::populate);
        for (int i = 0; i < 20; i++) {
            sim.runSteps(500);
            if (i % 5 == 0) {
                sim.run(() -> sim.reference().startShock());
            }
        }
        sim.stop();

        assertTrue(log.size() > 5_000, "commands: " + log.size());
        Set<Class<?>> kinds = new HashSet<>();
        for (Submitted s : log) {
            assertNotEquals(Simulator.USER_PARTICIPANT_ID, s.participantId());
            assertTrue(s.participantId() >= 1001);
            assertNotEquals(Simulator.USER_LABEL, sim.execute(() -> sim.label(s.participantId())));
            kinds.add(s.command().getClass());
            if (s.command() instanceof Command.Place p) {
                Order o = p.order();
                assertEquals(s.participantId(), o.participantId());
                assertTrue(o.qtyRemaining() > 0, "positive qty");
                if (o.type() == orderbook.OrderType.LIMIT) {
                    assertTrue(o.price() > 0, "positive price");
                } else {
                    assertNotEquals(TimeInForce.GTC, o.timeInForce(), "market orders are IOC/FOK");
                }
            }
            for (Event e : s.events()) {
                assertFalse(e instanceof Event.OrderRejected, () -> "engine rejected " + s);
            }
        }
        assertEquals(Set.of(Command.Place.class, Command.Cancel.class, Command.Amend.class), kinds);
        assertFalse(ownedSeen.contains(Simulator.USER_PARTICIPANT_ID));
    }
}
