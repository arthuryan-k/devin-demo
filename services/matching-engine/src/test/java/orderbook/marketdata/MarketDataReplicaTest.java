package orderbook.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import orderbook.Command;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.pipeline.InlinePipeline;

class MarketDataReplicaTest {

    /** Synchronous transport capturing every frame. */
    private static Outbox.Transport capture(List<String> into) {
        return new Outbox.Transport() {
            @Override
            public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
                into.add(text);
                onSuccess.run();
            }

            @Override
            public void close() {
            }
        };
    }

    /** Random mix of limit/market/IOC/FOK places, cancels and amends from a few participants (so STP happens). */
    private static final class Commands {
        private final Random random;
        private final List<Long> ids = new ArrayList<>();
        private long nextId = 1;

        Commands(long seed) {
            random = new Random(seed);
        }

        Command next() {
            int roll = random.nextInt(10);
            if (roll < 2 && !ids.isEmpty()) {
                return new Command.Cancel(ids.get(random.nextInt(ids.size())));
            }
            if (roll < 4 && !ids.isEmpty()) {
                long id = ids.get(random.nextInt(ids.size()));
                Long price = random.nextBoolean() ? null : 95L + random.nextInt(11);
                Long qty = price != null && random.nextBoolean() ? null : 1L + random.nextInt(20);
                return new Command.Amend(id, price, qty);
            }
            long id = nextId++;
            ids.add(id);
            long participant = 1 + random.nextInt(4);
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + random.nextInt(20);
            if (roll == 9) {
                return new Command.Place(Order.market(id, participant, side, qty, TimeInForce.IOC));
            }
            TimeInForce tif = roll == 8 ? TimeInForce.FOK : roll == 7 ? TimeInForce.IOC : TimeInForce.GTC;
            return new Command.Place(Order.limit(id, participant, side, 95 + random.nextInt(11), qty, tif));
        }
    }

    @Property(tries = 60)
    void snapshotPlusDeltasReproducesTheEngineBook(@ForAll long seed) {
        InlinePipeline pipeline = new InlinePipeline();
        MarketDataPublisher publisher = new MarketDataPublisher();
        pipeline.addConsumer(publisher);
        MatchingEngine reference = new MatchingEngine();
        Commands commands = new Commands(seed);
        List<String> early = new ArrayList<>();
        List<String> late = new ArrayList<>();
        publisher.subscribe(capture(early));
        int lateJoin = new Random(seed).nextInt(200);
        for (int i = 0; i < 300; i++) {
            if (i == lateJoin) {
                publisher.subscribe(capture(late));
            }
            Command command = commands.next();
            reference.process(command);
            assertTrue(pipeline.tryPublish(1, command, false) >= 0);
        }
        for (List<String> frames : List.of(early, late)) {
            BookReplica replica = new BookReplica();
            frames.forEach(f -> assertTrue(replica.accept(f), "unexpected gap"));
            assertEquals(299, replica.seq());
            for (Side side : Side.values()) {
                assertEquals(reference.book().depth(side, Integer.MAX_VALUE), replica.levels(side), side.name());
            }
        }
        for (Side side : Side.values()) {
            assertEquals(reference.book().depth(side, Integer.MAX_VALUE), publisher.levels(side));
        }
    }

    @Test
    void everySequenceProducesOneMessageCarryingIt() {
        InlinePipeline pipeline = new InlinePipeline();
        MarketDataPublisher publisher = new MarketDataPublisher();
        pipeline.addConsumer(publisher);
        List<String> frames = new ArrayList<>();
        publisher.subscribe(capture(frames));
        pipeline.tryPublish(1, new Command.Place(Order.limit(1, 1, Side.BUY, 100, 5, TimeInForce.GTC)), false);
        pipeline.tryPublish(1, new Command.Cancel(42), false);
        pipeline.tryPublish(2, new Command.Place(Order.limit(2, 2, Side.SELL, 100, 2, TimeInForce.GTC)), false);
        assertEquals(List.of(-1L, 0L, 1L, 2L), frames.stream().map(BookReplica::seqOf).toList());
        assertTrue(frames.get(0).startsWith("{\"type\":\"snapshot\""));
        assertTrue(frames.get(1).contains("\"levels\":[[\"a\",\"B\",100,5,1]]"), frames.get(1));
        assertTrue(frames.get(2).contains("\"levels\":[]") && frames.get(2).contains("UNKNOWN_ORDER_ID"), frames.get(2));
        assertTrue(frames.get(3).contains("\"levels\":[[\"u\",\"B\",100,3,1]]"), frames.get(3));
        assertTrue(frames.get(3).contains("{\"type\":\"TradeExecuted\",\"n\":1,\"price\":100,\"qty\":2,\"takerSide\":\"SELL\""),
                frames.get(3));
    }

    @Test
    void skippedSequenceIsDetectedAndASnapshotResyncs() {
        InlinePipeline pipeline = new InlinePipeline();
        MarketDataPublisher publisher = new MarketDataPublisher();
        pipeline.addConsumer(publisher);
        MatchingEngine reference = new MatchingEngine();
        List<String> frames = new ArrayList<>();
        publisher.subscribe(capture(frames));
        Commands commands = new Commands(7);
        for (int i = 0; i < 100; i++) {
            Command command = commands.next();
            reference.process(command);
            pipeline.tryPublish(1, command, false);
        }
        BookReplica replica = new BookReplica();
        for (int i = 0; i < frames.size(); i++) {
            if (i == 40) {
                continue; // lost message
            }
            boolean ok = replica.accept(frames.get(i));
            if (i == 41) {
                assertFalse(ok, "gap must be detected on the first message after the hole");
            }
        }
        assertEquals(1, replica.gaps);
        assertTrue(replica.stale(), "deltas after a gap are ignored until a snapshot arrives");
        assertTrue(replica.accept(publisher.snapshot()));
        assertFalse(replica.stale());
        assertEquals(99, replica.seq());
        for (Side side : Side.values()) {
            assertEquals(reference.book().depth(side, Integer.MAX_VALUE), replica.levels(side));
        }
    }

    @Test
    void resetMarkerBroadcastsAnEmptySnapshot() {
        InlinePipeline pipeline = new InlinePipeline();
        MarketDataPublisher publisher = new MarketDataPublisher();
        pipeline.addConsumer(publisher);
        List<String> frames = new ArrayList<>();
        publisher.subscribe(capture(frames));
        pipeline.tryPublish(1, new Command.Place(Order.limit(1, 1, Side.BUY, 100, 5, TimeInForce.GTC)), false);
        pipeline.tryPublishReset();
        String last = frames.get(frames.size() - 1);
        assertTrue(last.startsWith("{\"type\":\"snapshot\",\"seq\":1,\"reason\":\"reset\",\"bids\":[],\"asks\":[]"), last);
    }
}
