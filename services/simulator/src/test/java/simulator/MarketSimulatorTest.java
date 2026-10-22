package simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MarketSimulatorTest {

    static final List<MarketSimulator.SymbolSpec> SYMBOLS = List.of(new MarketSimulator.SymbolSpec("ACME", 100_00),
            new MarketSimulator.SymbolSpec("GLOBX", 50_00), new MarketSimulator.SymbolSpec("INITD", 25_00),
            new MarketSimulator.SymbolSpec("NVTA", 200_00));
    static final Set<String> TICKERS = Set.of("ACME", "GLOBX", "INITD", "NVTA");

    private final List<MarketSimulator> sims = new ArrayList<>();

    private MarketSimulator sim(long seed, FakeExchange exchange) {
        MarketSimulator sim = new MarketSimulator(seed, SYMBOLS, exchange, exchange, ClientLoop.INLINE, false);
        sims.add(sim);
        return sim;
    }

    @AfterEach
    void closeAll() {
        sims.forEach(MarketSimulator::close);
    }

    @Test
    void producesLegalTwoSidedFlowOnEverySymbol() {
        FakeExchange ex = new FakeExchange(TICKERS, null);
        MarketSimulator sim = sim(7, ex);
        sim.start();
        sim.runSteps(4000);
        assertEquals(List.of(), ex.violations);
        assertEquals(TICKERS, ex.placesBySymbol.keySet());
        for (String s : TICKERS) {
            assertTrue(ex.trades.stream().filter(t -> t.symbol().equals(s)).count() > 20, "trades on " + s);
        }
        MarketSimulator.Status status = sim.status();
        for (MarketSimulator.SymbolStatus s : status.symbols()) {
            assertTrue(s.participants().size() >= sim.minParticipants(), s.symbol());
            assertTrue(s.participants().size() <= sim.maxParticipants(), s.symbol());
        }
    }

    @Test
    void everyParticipantSignsUpForItsOwnAccount() {
        FakeExchange ex = new FakeExchange(TICKERS, null);
        MarketSimulator sim = sim(11, ex);
        sim.start();
        sim.runSteps(6000);
        List<ExchangeClient.Credentials> accounts = sim.accounts();
        assertTrue(accounts.size() > 4 * (sim.targetParticipants() + 1), "churn adds accounts: " + accounts.size());
        Set<Long> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (ExchangeClient.Credentials c : accounts) {
            assertTrue(ids.add(c.participantId()), "account reused " + c);
            assertTrue(keys.add(c.apiKey()), "key reused " + c);
        }
        for (MarketSimulator.SymbolStatus s : sim.status().symbols()) {
            for (MarketSimulator.ParticipantInfo p : s.participants()) {
                assertEquals(p.label(), ex.nameOf(p.participantId()));
                assertTrue(p.label().contains("_" + s.symbol() + "_"));
            }
        }
    }

    @Test
    void exitingParticipantsCancelTheirRestingOrders() {
        FakeExchange ex = new FakeExchange(TICKERS, null);
        MarketSimulator sim = sim(3, ex);
        List<Arrival> exits = new ArrayList<>();
        sim.addArrivalListener(a -> {
            if (a.kind() == Arrival.Kind.EXIT) {
                exits.add(a);
            }
        });
        sim.start();
        sim.runSteps(8000);
        assertTrue(exits.size() > 0, "churn produced exits");
        Set<Long> active = new HashSet<>();
        sim.status().symbols().forEach(s -> s.participants().forEach(p -> active.add(p.participantId())));
        int exitedWithOrders = 0;
        for (ExchangeClient.Credentials c : sim.accounts()) {
            if (!active.contains(c.participantId())) {
                assertEquals(List.of(), ex.resting(c.participantId()), c.label() + " left orders behind");
                exitedWithOrders++;
            }
        }
        assertTrue(exitedWithOrders > 0);

        sim.stop();
        for (ExchangeClient.Credentials c : sim.accounts()) {
            assertEquals(List.of(), ex.resting(c.participantId()), c.label() + " left orders behind on stop");
        }
    }

    @Test
    void sameSeedReproducesTheArrivalProcessWhateverTheExchangeReplies() {
        List<Arrival> a = arrivals(42, new Random(1));
        List<Arrival> b = arrivals(42, new Random(999));
        List<Arrival> c = arrivals(42, null);
        assertTrue(a.size() > 3000);
        assertEquals(a, b);
        assertEquals(a, c);
        assertNotEquals(a, arrivals(43, null));
        Set<String> symbols = new HashSet<>();
        a.forEach(x -> symbols.add(x.symbol()));
        assertEquals(TICKERS, symbols);
        assertTrue(a.stream().anyMatch(x -> x.shockDirection() != 0), "whale shocks are part of the process");
    }

    @Test
    void interArrivalsAreExponentialWithMeanOneOverRate() {
        SimClock clock = new SimClock(5);
        double rate = 20;
        int n = 50_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += clock.nextDelay(rate);
        }
        assertEquals(1 / rate, sum / n, 0.002);
    }

    @Test
    void resetReplaysTheSameSeed() {
        FakeExchange ex = new FakeExchange(TICKERS, null);
        MarketSimulator sim = sim(42, ex);
        List<Arrival> log = new ArrayList<>();
        sim.addArrivalListener(log::add);
        sim.start();
        sim.runSteps(500);
        List<Arrival> first = new ArrayList<>(log.subList(0, log.size()));
        sim.reset(42);
        log.clear();
        sim.start();
        sim.runSteps(500);
        assertEquals(first, log);
    }

    private List<Arrival> arrivals(long seed, Random noise) {
        FakeExchange ex = new FakeExchange(TICKERS, noise);
        MarketSimulator sim = sim(seed, ex);
        List<Arrival> log = new ArrayList<>();
        sim.addArrivalListener(log::add);
        sim.start();
        sim.runSteps(3000);
        return log;
    }
}
