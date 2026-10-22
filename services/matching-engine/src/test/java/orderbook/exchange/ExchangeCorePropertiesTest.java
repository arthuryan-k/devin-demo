package orderbook.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import orderbook.Command;
import orderbook.CommandArbitraries;
import orderbook.Event;
import orderbook.MatchingEngine;
import orderbook.Symbols;
import orderbook.pipeline.InlinePipeline;

class ExchangeCorePropertiesTest {

    /** Random commands, each retargeted at a random listed symbol; ids collide across symbols on purpose. */
    @Provide
    Arbitrary<List<Command>> multiSymbolCommands() {
        Arbitrary<Command> one = Combinators.combine(
                        Arbitraries.frequencyOf(
                                net.jqwik.api.Tuple.of(6, CommandArbitraries.places()),
                                net.jqwik.api.Tuple.of(1, CommandArbitraries.cancels()),
                                net.jqwik.api.Tuple.of(2, CommandArbitraries.amends())),
                        Arbitraries.of(Symbols.ALL))
                .as(ExchangeCorePropertiesTest::onSymbol);
        return one.list().ofMaxSize(150);
    }

    static Command onSymbol(Command c, String symbol) {
        if (c instanceof Command.Place p) {
            return new Command.Place(p.order().withSymbol(symbol));
        }
        if (c instanceof Command.Cancel x) {
            return new Command.Cancel(symbol, x.orderId());
        }
        Command.Amend a = (Command.Amend) c;
        return new Command.Amend(symbol, a.orderId(), a.newPrice(), a.newQty());
    }

    /**
     * Each symbol's engine behind the router produces exactly what a standalone engine produces for that symbol's
     * commands alone, so flow on one symbol can never affect (or match against) another.
     */
    @Property(tries = 200)
    void eachSymbolBehavesLikeAnIsolatedEngine(@ForAll("multiSymbolCommands") List<Command> commands) {
        Map<String, List<List<Event>>> routed = new HashMap<>();
        Map<String, Set<Long>> placedIds = new HashMap<>();
        try (ExchangeCore core = ExchangeCore.builder().pipelines(InlinePipeline::new).build()) {
            for (String symbol : core.symbols()) {
                List<List<Event>> out = new ArrayList<>();
                routed.put(symbol, out);
                placedIds.put(symbol, new HashSet<>());
                core.engine(symbol).pipeline().addConsumer((r, end) -> {
                    assertEquals(symbol, r.command().symbol());
                    out.add(List.copyOf(r.events()));
                });
            }
            for (Command c : commands) {
                assertTrue(core.submit(1, c, false).published());
                if (c instanceof Command.Place p) {
                    placedIds.get(c.symbol()).add(p.order().id());
                }
            }
        }
        for (String symbol : Symbols.ALL) {
            MatchingEngine isolated = new MatchingEngine(symbol);
            List<List<Event>> expected = new ArrayList<>();
            for (Command c : commands) {
                if (c.symbol().equals(symbol)) {
                    expected.add(isolated.process(c));
                }
            }
            assertEquals(expected, routed.get(symbol), symbol);
            for (List<Event> events : routed.get(symbol)) {
                for (Event e : events) {
                    if (e instanceof Event.TradeExecuted t) {
                        assertTrue(placedIds.get(symbol).contains(t.trade().makerOrderId()), symbol);
                        assertTrue(placedIds.get(symbol).contains(t.trade().takerOrderId()), symbol);
                    }
                }
            }
        }
    }
}
