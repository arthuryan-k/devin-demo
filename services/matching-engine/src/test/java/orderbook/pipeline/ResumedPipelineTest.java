package orderbook.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.Side;

class ResumedPipelineTest {

    @Test
    void sequencesContinueFromTheRecoveredEngine() throws Exception {
        MatchingEngine engine = new MatchingEngine("ACME");
        engine.process(new Command.Place(new Order(1, Side.SELL, 100, 5)));
        List<Long> seen = new CopyOnWriteArrayList<>();
        try (DisruptorPipeline p = new DisruptorPipeline(engine, 10, 64, 64)) {
            assertEquals(9, p.lastSequence());
            p.addConsumer((r, end) -> seen.add(r.seq()));
            long seq = p.tryPublish(2, new Command.Place(new Order(2, Side.BUY, 100, 2)), true);
            assertEquals(10, seq);
            Result result = p.awaitResponse(seq, 5000);
            assertEquals(2, result.events().size(), "the resumed book still holds order 1");
            assertEquals(11, p.tryPublish(2, new Command.Cancel(1), false));
            long deadline = System.currentTimeMillis() + 5000;
            while (seen.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(List.of(10L, 11L), seen);
            assertEquals(11, p.engineSequence());
        }
    }
}
