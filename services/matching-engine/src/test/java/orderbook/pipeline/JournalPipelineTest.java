package orderbook.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import orderbook.Command;
import orderbook.CommandLog;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.journal.Journal;

class JournalPipelineTest {

    private static final CommandLog NO_LOG = c -> { };

    @TempDir
    Path dir;

    private static Command place(long id) {
        Side side = id % 2 == 0 ? Side.BUY : Side.SELL;
        long price = side == Side.BUY ? 95 + id % 6 : 99 + id % 6;
        return new Command.Place(Order.limit(id, 1 + id % 4, side, price, 1 + id % 5, TimeInForce.GTC));
    }

    private static long publish(Pipeline pipeline, Command command) {
        long seq;
        while ((seq = command == null ? pipeline.tryPublishSnapshot() : pipeline.tryPublish(1, command, false))
                == Pipeline.BUSY) {
            Thread.onSpinWait();
        }
        return seq;
    }

    /** 0..n commands with a snapshot marker after every {@code markEvery}-th; returns the marker sequences. */
    private static List<Long> drive(Pipeline pipeline, int n, int markEvery) {
        List<Long> markers = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            publish(pipeline, place(i));
            if (i % markEvery == 0) {
                markers.add(publish(pipeline, null));
            }
        }
        return markers;
    }

    private static List<String> dump(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                out.add(p.getFileName() + "\n" + Files.readString(p));
            }
        }
        return out;
    }

    private static MatchingEngine expected(int n) {
        MatchingEngine engine = new MatchingEngine();
        for (int i = 1; i <= n; i++) {
            engine.process(place(i));
        }
        return engine;
    }

    @Test
    void snapshotMarkersAreSequencedDeterministically() throws IOException {
        Path inline = dir.resolve("inline");
        Path disruptor = dir.resolve("disruptor");
        List<Long> inlineMarkers;
        List<Long> disruptorMarkers;
        try (Journal journal = new Journal(inline); InlinePipeline pipeline = new InlinePipeline()) {
            pipeline.addConsumer(new JournalHandler(journal));
            inlineMarkers = drive(pipeline, 200, 50);
        }
        try (Journal journal = new Journal(disruptor); DisruptorPipeline pipeline = new DisruptorPipeline(16, 16)) {
            pipeline.addConsumer(new JournalHandler(journal));
            disruptorMarkers = drive(pipeline, 200, 50);
        }
        assertEquals(List.of(50L, 101L, 152L, 203L), inlineMarkers, "a marker takes the next sequence");
        assertEquals(inlineMarkers, disruptorMarkers);
        assertEquals(dump(inline), dump(disruptor), "identical segments and snapshots");

        Journal.Recovery recovery = new Journal(disruptor).load();
        assertEquals(203, recovery.snapshot().seq());
        assertEquals(0, recovery.tail().size());
        assertEquals(expected(200).exportState(), recovery.toEngine(NO_LOG).exportState());
    }

    @Test
    void snapshotIntervalSnapshotsTheCommandSequenceItCovers() throws IOException {
        try (Journal journal = new Journal(dir); DisruptorPipeline pipeline = new DisruptorPipeline(16, 16)) {
            pipeline.addConsumer(new JournalHandler(journal));
            pipeline.setSnapshotInterval(40);
            drive(pipeline, 130, Integer.MAX_VALUE);
        }
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(119, recovery.snapshot().seq(), "the 120th command is seq 119");
        assertEquals(List.of(120L, 121L, 122L, 123L, 124L, 125L, 126L, 127L, 128L, 129L),
                recovery.tail().stream().map(e -> e.seq()).toList());
        assertEquals(expected(120).exportState(), recovery.snapshot().state());
        assertEquals(expected(130).exportState(), recovery.toEngine(NO_LOG).exportState());
    }

    @Test
    void resetIsJournaledAsAnEmptySnapshot() throws IOException {
        try (Journal journal = new Journal(dir); InlinePipeline pipeline = new InlinePipeline()) {
            pipeline.addConsumer(new JournalHandler(journal));
            drive(pipeline, 20, Integer.MAX_VALUE);
            assertEquals(20, pipeline.tryPublishReset());
            publish(pipeline, place(3));
        }
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(20, recovery.snapshot().seq());
        assertTrue(recovery.snapshot().state().bids().isEmpty() && recovery.snapshot().state().asks().isEmpty());
        MatchingEngine fresh = new MatchingEngine();
        fresh.process(place(3));
        assertEquals(fresh.exportState(), recovery.toEngine(NO_LOG).exportState());
    }

    @Test
    void attachingToAnExistingJournalStartsFromAnEmptySnapshot() throws IOException {
        try (Journal journal = new Journal(dir); InlinePipeline pipeline = new InlinePipeline()) {
            pipeline.addConsumer(new JournalHandler(journal));
            drive(pipeline, 10, Integer.MAX_VALUE);
        }
        try (Journal journal = new Journal(dir); InlinePipeline pipeline = new InlinePipeline()) {
            JournalHandler handler = new JournalHandler(journal);
            assertEquals(11, handler.offset());
            pipeline.addConsumer(handler);
            publish(pipeline, place(1));
        }
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(10, recovery.snapshot().seq());
        assertEquals(11, recovery.tail().get(0).seq());
        assertEquals(expected(1).exportState(), recovery.toEngine(NO_LOG).exportState());
    }

    @Test
    void stalledJournalConsumerDoesNotStallTheEngineAndSnapshotsAreCapturedAtTheMarker() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        int n = 200;
        long marker;
        try (Journal journal = new Journal(dir, Journal.Options.fsync(1, 0));
                DisruptorPipeline pipeline = new DisruptorPipeline(16, 1024)) {
            JournalHandler handler = new JournalHandler(journal);
            pipeline.addConsumer((r, eob) -> {
                release.await();
                handler.onResult(r, eob);
            });
            for (int i = 1; i <= 100; i++) {
                publish(pipeline, place(i));
            }
            marker = publish(pipeline, null);
            for (int i = 101; i <= n; i++) {
                publish(pipeline, place(i));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pipeline.engineSequence() < n && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(n, pipeline.engineSequence(), "engine ran every command while the journal was blocked");
            assertTrue(Files.notExists(dir) || dump(dir).stream().noneMatch(f -> f.startsWith("snapshot-")));
            release.countDown();
        }
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(100, marker);
        assertEquals(marker, recovery.snapshot().seq());
        assertEquals(expected(100).exportState(), recovery.snapshot().state(),
                "state was captured at the marker, not when the consumer caught up");
        assertEquals(n - 100, recovery.tail().size());
        assertEquals(expected(n).exportState(), recovery.toEngine(NO_LOG).exportState());
    }
}
