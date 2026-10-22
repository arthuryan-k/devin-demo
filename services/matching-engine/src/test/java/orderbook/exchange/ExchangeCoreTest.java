package orderbook.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import orderbook.Command;
import orderbook.Event;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.Symbols;
import orderbook.TimeInForce;
import orderbook.pipeline.Gateway;
import orderbook.pipeline.InlinePipeline;
import orderbook.pipeline.Result;

class ExchangeCoreTest {

    @TempDir
    Path dir;

    private static Command place(String symbol, long id, long participant, Side side, long price, long qty) {
        return new Command.Place(new Order(symbol, id, participant, side, OrderType.LIMIT, TimeInForce.GTC, price, qty));
    }

    private static List<Event> run(ExchangeCore core, Command command) {
        Gateway.Submission s = core.submit(7, command, true);
        assertTrue(s.published(), () -> "not published: " + s);
        Result r = core.awaitResponse(command.symbol(), s.seq(), 5_000);
        return r.events();
    }

    @Test
    void hostsOnePipelinePerListedSymbol() {
        try (ExchangeCore core = ExchangeCore.create()) {
            assertEquals(Symbols.ALL, core.symbols());
            for (String symbol : Symbols.ALL) {
                SymbolEngine engine = core.engine(symbol);
                assertEquals(symbol, engine.symbol());
                assertEquals(symbol, engine.pipeline().symbol());
                assertEquals(symbol, engine.gateway().symbol());
                assertNull(engine.journal());
            }
            assertEquals(4, core.engines().values().stream().map(SymbolEngine::pipeline).distinct().count());
        }
    }

    @Test
    void routesCommandsToTheirSymbolsEngine() {
        ConcurrentHashMap<String, List<Command>> seen = new ConcurrentHashMap<>();
        try (ExchangeCore core = ExchangeCore.builder().pipelines(InlinePipeline::new).build()) {
            for (String symbol : core.symbols()) {
                List<Command> list = new CopyOnWriteArrayList<>();
                seen.put(symbol, list);
                core.engine(symbol).pipeline().addConsumer((r, end) -> {
                    if (r.command() != null) {
                        list.add(r.command());
                    }
                });
            }
            Command acme = place(Symbols.ACME, 1, 1, Side.BUY, 100, 5);
            Command nvta = place(Symbols.NVTA, 2, 1, Side.SELL, 50, 5);
            Command cancel = new Command.Cancel(Symbols.NVTA, 2);
            Command amend = new Command.Amend(Symbols.ACME, 1, 101L, null);
            for (Command c : List.of(acme, nvta, cancel, amend)) {
                core.submit(1, c, false);
            }
            assertEquals(List.of(acme, amend), seen.get(Symbols.ACME));
            assertEquals(List.of(nvta, cancel), seen.get(Symbols.NVTA));
            assertEquals(List.of(), seen.get(Symbols.GLOBX));
            assertEquals(List.of(), seen.get(Symbols.INITD));
        }
    }

    @Test
    void unknownSymbolIsRejectedBeforeAnyRing() {
        try (ExchangeCore core = ExchangeCore.create()) {
            Gateway.Submission s = core.submit(1, place("ZZZZ", 1, 1, Side.BUY, 100, 1), false);
            assertFalse(s.published());
            assertEquals(ExchangeCore.UNKNOWN_SYMBOL, s.rejection().reason());
            assertEquals(1, s.rejection().orderId());
            for (SymbolEngine engine : core.engines().values()) {
                assertEquals(-1, engine.pipeline().lastSequence());
            }
            assertThrows(IllegalArgumentException.class, () -> core.engine("ZZZZ"));
            assertThrows(IllegalArgumentException.class,
                    () -> ExchangeCore.builder().symbols(List.of("not a symbol")).build());
        }
    }

    @Test
    void ordersOnDifferentSymbolsNeverMatch() {
        try (ExchangeCore core = ExchangeCore.create()) {
            assertEquals(1, run(core, place(Symbols.ACME, 1, 1, Side.BUY, 100, 10)).size());
            List<Event> globx = run(core, place(Symbols.GLOBX, 2, 2, Side.SELL, 90, 10));
            assertEquals(List.of(new Event.OrderPlaced(2, Side.SELL, 90, 10, 0)), globx);
            List<Event> market = run(core,
                    new Command.Place(Order.market(3, 3, Side.SELL, 4).withSymbol(Symbols.INITD)));
            assertTrue(market.stream().noneMatch(e -> e instanceof Event.TradeExecuted), market.toString());

            List<Event> cross = run(core, place(Symbols.ACME, 4, 3, Side.SELL, 100, 4));
            assertEquals(new Event.TradeExecuted(new orderbook.Trade(1, 4, 100, 4)), cross.get(1));
            // the same order id is independent per symbol's book
            assertEquals(List.of(new Event.OrderRejected(2, Event.OrderRejected.Reason.UNKNOWN_ORDER_ID)),
                    run(core, new Command.Cancel(Symbols.ACME, 2)));
        }
    }

    @Test
    void orderIdsAreUniqueAcrossSymbols() {
        try (ExchangeCore core = ExchangeCore.create()) {
            core.engine(Symbols.ACME).gateway().reserveOrderIdsThrough(41);
            assertEquals(42, core.engine(Symbols.GLOBX).gateway().nextOrderId());
            assertEquals(43, core.nextOrderId());
        }
    }

    @Test
    void everyBookStartsEmptyWithNoPrice() {
        try (ExchangeCore core = ExchangeCore.builder().journalRoot(dir).build()) {
            assertEquals(4, core.symbols().size());
        }
        for (String symbol : Symbols.ALL) {
            MatchingEngine engine = ExchangeCore.recover(dir, symbol);
            assertEquals(symbol, engine.symbol());
            assertTrue(engine.book().isEmpty());
            assertTrue(engine.book().bestBid().isEmpty());
            assertTrue(engine.book().bestAsk().isEmpty());
            assertEquals(0, engine.nextSeqNum());
        }
        try (ExchangeCore core = ExchangeCore.create()) {
            List<Event> market = run(core, new Command.Place(Order.market(1, 1, Side.BUY, 5).withSymbol(Symbols.NVTA)));
            assertEquals(List.of(new Event.OrderPlaced(1, Side.BUY, 0, 5, 0),
                    new Event.OrderCancelled(1, 5, Event.OrderCancelled.CancelReason.UNFILLED_REMAINDER)), market);
        }
    }

    @Test
    void perSymbolJournalsRecoverIndependently() throws IOException {
        try (ExchangeCore core = ExchangeCore.builder().journalRoot(dir).snapshotInterval(3).build()) {
            for (int i = 1; i <= 10; i++) {
                run(core, place(Symbols.ACME, i, 1, Side.BUY, 90 + i, 1));
                run(core, place(Symbols.GLOBX, 100 + i, 2, Side.SELL, 200 + i, 2));
            }
            run(core, new Command.Cancel(Symbols.ACME, 3));
            run(core, place(Symbols.GLOBX, 200, 3, Side.BUY, 205, 3));
        }
        for (String symbol : Symbols.ALL) {
            assertTrue(Files.isDirectory(dir.resolve(symbol)), symbol);
        }
        MatchingEngine acme = ExchangeCore.recover(dir, Symbols.ACME);
        assertEquals(9, acme.book().size());
        assertFalse(acme.book().contains(3));
        assertTrue(acme.book().orders(Side.BUY).stream().allMatch(o -> o.symbol().equals(Symbols.ACME)));
        assertEquals(100, acme.book().bestBid().getAsLong());

        MatchingEngine globx = ExchangeCore.recover(dir, Symbols.GLOBX);
        assertEquals(List.of(202L, 203L, 204L, 205L, 206L, 207L, 208L, 209L, 210L),
                globx.book().orders(Side.SELL).stream().map(Order::price).toList());
        assertEquals(1, globx.book().find(102).orElseThrow().qtyRemaining());
        assertTrue(globx.book().bestBid().isEmpty());
        assertTrue(ExchangeCore.recover(dir, Symbols.INITD).book().isEmpty());

        for (Path segment : (Iterable<Path>) Files.list(dir.resolve(Symbols.ACME))
                .filter(p -> p.getFileName().toString().endsWith(".log"))::iterator) {
            for (String line : Files.readAllLines(segment, StandardCharsets.UTF_8)) {
                assertTrue(line.startsWith("#") || line.contains("\"sym\":\"ACME\""), line);
            }
        }

        // corrupting one symbol's journal does not affect another's recovery
        Path globxSnapshot = Files.list(dir.resolve(Symbols.GLOBX))
                .filter(p -> p.getFileName().toString().startsWith("snapshot-")).sorted()
                .reduce((first, second) -> second).orElseThrow();
        Files.writeString(globxSnapshot, "00000000:{\"format\":2}", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> ExchangeCore.recover(dir, Symbols.GLOBX));
        assertEquals(9, ExchangeCore.recover(dir, Symbols.ACME).book().size());
    }

    @Test
    void engineRejectsCommandsForAnotherSymbol() {
        MatchingEngine acme = new MatchingEngine(Symbols.ACME);
        assertEquals(List.of(new Event.OrderRejected(1, Event.OrderRejected.Reason.WRONG_SYMBOL)),
                acme.process(place(Symbols.GLOBX, 1, 1, Side.BUY, 100, 1)));
        assertEquals(List.of(new Event.OrderRejected(1, Event.OrderRejected.Reason.WRONG_SYMBOL)),
                acme.process(new Command.Cancel(Symbols.GLOBX, 1)));
        assertTrue(acme.book().isEmpty());
        assertEquals(0, acme.nextSeqNum());
        assertEquals(1, acme.nextOrderId());
    }
}
