package orderbook.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;

class DisruptorPipelineTest {

    private static Command.Place place(long id, long participant) {
        return new Command.Place(Order.limit(id, participant, Side.BUY, 100 + id % 7, 1, TimeInForce.GTC));
    }

    @Test
    void ringSequenceIsTheGlobalSeqNumAcrossConcurrentProducers() throws Exception {
        int producers = 4;
        int perProducer = 2_000;
        List<Long> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch all = new CountDownLatch(producers * perProducer);
        List<List<Long>> assigned = new ArrayList<>();
        try (DisruptorPipeline pipeline = new DisruptorPipeline(64, 64)) {
            pipeline.addConsumer((r, eob) -> {
                seen.add(r.seq());
                all.countDown();
            });
            List<Thread> threads = new ArrayList<>();
            AtomicLong ids = new AtomicLong(1);
            for (int p = 0; p < producers; p++) {
                List<Long> mine = new ArrayList<>();
                assigned.add(mine);
                long participant = p + 1;
                Thread t = new Thread(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        Command c = place(ids.getAndIncrement(), participant);
                        long seq;
                        while ((seq = pipeline.tryPublish(participant, c, false)) == Pipeline.BUSY) {
                            Thread.onSpinWait();
                        }
                        mine.add(seq);
                    }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            assertTrue(all.await(10, TimeUnit.SECONDS));
        }
        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < producers * perProducer; i++) {
            expected.add(i);
        }
        assertEquals(expected, seen, "consumers see every sequence exactly once, in order");
        List<Long> union = new ArrayList<>();
        for (List<Long> mine : assigned) {
            for (int i = 1; i < mine.size(); i++) {
                assertTrue(mine.get(i) > mine.get(i - 1), "a producer's commands keep their order");
            }
            union.addAll(mine);
        }
        Collections.sort(union);
        assertEquals(expected, union, "returned sequences are unique and gap-free");
    }

    @Test
    void fullRingRejectsWithBusyInsteadOfBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger unpublished = new AtomicInteger();
        try (DisruptorPipeline pipeline = new DisruptorPipeline(4, 4)) {
            pipeline.addConsumer((r, eob) -> {
                release.await();
                delivered.incrementAndGet();
            });
            Gateway gateway = new Gateway(pipeline);
            gateway.setRiskCheck(1, new Gateway.RiskCheck() {
                @Override
                public Gateway.Rejection admit(Command command) {
                    return null;
                }

                @Override
                public void unpublished(Command command, Event.OrderRejected busy) {
                    unpublished.incrementAndGet();
                }
            });
            int published = 0;
            Gateway.Submission busy = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (busy == null && System.nanoTime() < deadline) {
                long start = System.nanoTime();
                Gateway.Submission s = gateway.submit(1, place(gateway.nextOrderId(), 1), false);
                assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500), "publish never blocks");
                if (s.published()) {
                    published++;
                } else {
                    busy = s;
                }
            }
            assertTrue(busy != null, "a stalled pipeline must eventually report BUSY");
            assertEquals(Event.OrderRejected.Reason.BUSY, busy.busy().reason());
            assertEquals(1, unpublished.get(), "the risk check releases the rejected command's reservation");
            assertTrue(published >= 4 && published <= 4 + 4 + 2, "about two rings plus in-flight: " + published);
            release.countDown();
            int expected = published;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (delivered.get() < expected && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(expected, delivered.get(), "everything accepted before BUSY is still delivered");
        }
    }

    @Test
    void slowConsumerDoesNotStallMatchingOrOtherConsumers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger slowSeen = new AtomicInteger();
        int n = 1_000;
        try (DisruptorPipeline pipeline = new DisruptorPipeline(1024, 4096)) {
            pipeline.addConsumer((r, eob) -> {
                release.await();
                slowSeen.incrementAndGet();
            });
            List<Long> seqs = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                long seq = pipeline.tryPublish(1, place(i, 1), true);
                assertTrue(seq >= 0);
                seqs.add(seq);
            }
            for (long seq : seqs) {
                Result r = pipeline.awaitResponse(seq, 5_000);
                assertEquals(seq, r.seq());
                assertTrue(r.events().get(0) instanceof Event.OrderPlaced);
            }
            assertEquals(n - 1, pipeline.engineSequence(), "the engine matched everything");
            assertEquals(0, slowSeen.get(), "while the slow consumer has not processed a single result");
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (slowSeen.get() < n && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(n, slowSeen.get(), "and it catches up from its own cursor");
        }
    }

    @Test
    void responsesAreCorrelatedBySequence() {
        try (DisruptorPipeline pipeline = new DisruptorPipeline()) {
            long a = pipeline.tryPublish(1, new Command.Place(Order.limit(1, 1, Side.SELL, 100, 5, TimeInForce.GTC)), true);
            long b = pipeline.tryPublish(2, new Command.Place(Order.limit(2, 2, Side.BUY, 100, 3, TimeInForce.GTC)), true);
            long c = pipeline.tryPublish(2, new Command.Cancel(99), true);
            assertTrue(pipeline.awaitResponse(c, 5_000).events().get(0) instanceof Event.OrderRejected);
            Result rb = pipeline.awaitResponse(b, 5_000);
            assertEquals(2, rb.participantId());
            assertTrue(rb.events().stream().anyMatch(e -> e instanceof Event.TradeExecuted));
            assertEquals(1, pipeline.awaitResponse(a, 5_000).events().size());
        }
    }
}
