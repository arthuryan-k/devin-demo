package orderbook.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import orderbook.BookState;
import orderbook.Command;
import orderbook.Event;
import orderbook.Event.OrderRejected;
import orderbook.Event.OrderRejected.Reason;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;

class JournalTest {

    @TempDir
    Path dir;

    private static List<Command> sampleCommands() {
        return List.of(
                new Command.Place(Order.limit(1, 7, Side.SELL, 100, 10)),
                new Command.Place(Order.limit(2, 8, Side.SELL, 101, 10)),
                new Command.Place(Order.limit(3, 8, Side.BUY, 98, 10)),
                new Command.Place(Order.limit(4, 9, Side.BUY, 100, 4, TimeInForce.IOC)),
                new Command.Amend(3, null, 20L),
                new Command.Amend(2, null, 6L),
                new Command.Place(Order.market(5, 9, Side.BUY, 3)),
                new Command.Place(Order.limit(6, 9, Side.BUY, 101, 50, TimeInForce.FOK)),
                new Command.Place(Order.limit(7, 7, Side.BUY, 100, 5)),
                new Command.Cancel(42),
                new Command.Place(new Order(8, Side.BUY, 99, 0)),
                new Command.Place(Order.limit(9, 9, Side.BUY, 97, 5)),
                new Command.Amend(9, 99L, null),
                new Command.Cancel(3));
    }

    private static String encodeAll(List<Command> commands) {
        return String.join("\n", commands.stream().map(CommandCodec::encode).toList());
    }

    @Test
    void roundTripRebuildsIdenticalBookAndCounters() throws IOException {
        Path file = dir.resolve("journal.jsonl");
        List<Event> originalEvents = new ArrayList<>();
        MatchingEngine original;
        try (Journal journal = new Journal(file)) {
            original = new MatchingEngine(journal);
            for (Command c : sampleCommands()) {
                originalEvents.addAll(original.process(c));
            }
        }
        assertTrue(original.book().size() > 0);

        Journal reopened = new Journal(file);
        List<Command> replayed = reopened.replay();
        assertEquals(encodeAll(sampleCommands()), encodeAll(replayed));

        List<Event> replayedEvents = new ArrayList<>();
        MatchingEngine fresh = new MatchingEngine();
        for (Command c : replayed) {
            replayedEvents.addAll(fresh.process(c));
        }
        assertEquals(originalEvents, replayedEvents);

        MatchingEngine recovered = reopened.recover();
        assertEquals(BookState.of(original.book()), BookState.of(recovered.book()));
        assertEquals(original.nextSeqNum(), recovered.nextSeqNum());
        assertEquals(original.nextOrderId(), recovered.nextOrderId());
        assertEquals(10, recovered.nextOrderId());
        reopened.close();
    }

    @Test
    void recoveredEngineRejectsPreviouslyUsedIdsAndKeepsJournaling() throws IOException {
        Path file = dir.resolve("journal.jsonl");
        try (Journal journal = new Journal(file)) {
            MatchingEngine engine = new MatchingEngine(journal);
            engine.process(new Command.Place(new Order(1, Side.SELL, 100, 10)));
            engine.process(new Command.Place(new Order(2, Side.BUY, 100, 10)));
        }

        try (Journal journal = new Journal(file)) {
            MatchingEngine engine = journal.recover();
            assertEquals(List.of(new OrderRejected(1, Reason.DUPLICATE_ORDER_ID)),
                    engine.process(new Command.Place(new Order(1, Side.BUY, 90, 1))));
            engine.process(new Command.Place(new Order(3, Side.BUY, 90, 5)));
        }

        try (Journal journal = new Journal(file)) {
            assertEquals(4, journal.replay().size());
            MatchingEngine engine = journal.recover();
            assertEquals(5, engine.book().find(3).orElseThrow().qtyRemaining());
            assertEquals(3, engine.nextSeqNum());
        }
    }

    @Test
    void everyCommandIsJournaledBeforeProcessingIncludingRejections() {
        List<String> log = new ArrayList<>();
        MatchingEngine[] holder = new MatchingEngine[1];
        holder[0] = new MatchingEngine(c -> log.add(CommandCodec.encode(c) + " size=" + holder[0].book().size()));

        holder[0].process(new Command.Place(new Order(1, Side.BUY, 100, 10)));
        holder[0].process(new Command.Place(new Order(1, Side.BUY, 100, 10)));
        holder[0].process(new Command.Amend(1, null, 5L));
        holder[0].process(new Command.Cancel(1));

        assertEquals(4, log.size());
        assertTrue(log.get(0).endsWith("size=0"), "place must be logged before the order rests");
        assertTrue(log.get(3).endsWith("size=1"), "cancel must be logged before the order is removed");
    }

    @Test
    void failedAppendPreventsProcessing() {
        MatchingEngine engine = new MatchingEngine(c -> {
            throw new IllegalStateException("disk full");
        });
        assertThrows(IllegalStateException.class,
                () -> engine.process(new Command.Place(new Order(1, Side.BUY, 100, 10))));
        assertTrue(engine.book().isEmpty());
        assertEquals(0, engine.nextSeqNum());
    }

    @Test
    void missingFileReplaysAsEmpty() {
        Journal journal = new Journal(dir.resolve("absent.jsonl"));
        assertEquals(List.of(), journal.replay());
        assertTrue(journal.recover().book().isEmpty());
    }

    @Test
    void tornFinalLineIsIgnoredOnReplayAndTruncatedOnNextAppend() throws IOException {
        Path file = dir.resolve("journal.jsonl");
        try (Journal journal = new Journal(file)) {
            journal.append(new Command.Place(new Order(1, Side.BUY, 100, 10)));
        }
        Files.writeString(file, "{\"cmd\":\"place\",\"id\":2,\"part", StandardOpenOption.APPEND);

        try (Journal journal = new Journal(file)) {
            assertEquals(1, journal.replay().size());
            journal.append(new Command.Cancel(1));
            assertEquals(List.of("{\"cmd\":\"place\",\"id\":1,\"participant\":0,\"side\":\"BUY\",\"type\":\"LIMIT\","
                            + "\"tif\":\"GTC\",\"price\":100,\"qty\":10}", "{\"cmd\":\"cancel\",\"id\":1}"),
                    Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void corruptCompleteLineFailsReplay() throws IOException {
        Path file = dir.resolve("journal.jsonl");
        Files.writeString(file, "{\"cmd\":\"cancel\",\"id\":1}\nnot json\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new Journal(file).replay());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void codecRoundTripsEveryCommandShape() {
        List<Command> commands = List.of(
                new Command.Place(new Order(-3, 12, Side.SELL, OrderType.MARKET, TimeInForce.FOK, 0, 9)),
                new Command.Place(Order.limit(4, 0, Side.BUY, 100, 1, TimeInForce.IOC)),
                new Command.Cancel(Long.MAX_VALUE),
                new Command.Amend(1, null, null),
                new Command.Amend(1, 5L, null),
                new Command.Amend(1, null, -2L));
        for (Command c : commands) {
            String line = CommandCodec.encode(c);
            assertEquals(line, CommandCodec.encode(CommandCodec.decode(line)));
            if (!(c instanceof Command.Place)) {
                assertEquals(c, CommandCodec.decode(line));
            }
        }
        assertEquals(new Command.Amend(1, 5L, null),
                CommandCodec.decode(" { \"qty\" : null , \"cmd\" : \"amend\" , \"price\" : 5 , \"id\" : 1 } "));
    }

    @Test
    void codecRejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"cmd\":\"cancel\"}", "{\"cmd\":\"nope\",\"id\":1}",
                "{\"cmd\":\"cancel\",\"id\":1} x", "{\"cmd\":\"cancel\",\"id\":1,\"id\":2}",
                "{\"cmd\":\"place\",\"id\":1}")) {
            assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(bad), bad);
        }
    }
}
