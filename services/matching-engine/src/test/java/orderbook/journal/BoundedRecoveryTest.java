package orderbook.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import orderbook.Command;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.Side;

class BoundedRecoveryTest {

    @TempDir
    Path dir;

    @Test
    void loadsTheStateAsOfAnEarlierSequence() throws Exception {
        try (Journal journal = new Journal(dir)) {
            MatchingEngine engine = new MatchingEngine(journal);
            for (int i = 1; i <= 10; i++) {
                engine.process(new Command.Place(new Order(i, i % 2 == 0 ? Side.BUY : Side.SELL, 100 + i % 2, 1)));
                if (i == 6) {
                    journal.snapshot(engine);
                }
            }
            Journal.Recovery early = journal.load(3);
            assertNull(early.snapshot());
            assertEquals(3, early.lastSeq());
            Journal.Recovery mid = journal.load(7);
            assertEquals(5, mid.snapshot().seq());
            assertEquals(7, mid.lastSeq());
            assertEquals(2, mid.tail().size());
            assertEquals(9, journal.load().lastSeq());

            assertEquals(engine.exportState(), journal.load().toEngine(orderbook.CommandLog.NONE).exportState());
            assertEquals(9, journal.load(7).toEngine(orderbook.CommandLog.NONE).nextOrderId());
        }
    }
}
