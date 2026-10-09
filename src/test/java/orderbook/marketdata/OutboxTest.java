package orderbook.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class OutboxTest {

    /** Records sends and completes them only when told to, like a slow network. */
    private static final class SlowTransport implements Outbox.Transport {
        final List<String> sent = new ArrayList<>();
        Runnable pendingSuccess;
        Consumer<Throwable> pendingFailure;
        boolean closed;

        @Override
        public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
            sent.add(text);
            pendingSuccess = onSuccess;
            pendingFailure = onFailure;
        }

        @Override
        public void close() {
            closed = true;
        }

        void complete() {
            Runnable r = pendingSuccess;
            pendingSuccess = null;
            r.run();
        }
    }

    @Test
    void sendsInOrderWithOneWriteInFlight() {
        SlowTransport t = new SlowTransport();
        Outbox out = new Outbox(t, 10);
        out.enqueue("a", () -> "snap");
        out.enqueue("b", () -> "snap");
        out.enqueue("c", () -> "snap");
        assertEquals(List.of("a"), t.sent);
        t.complete();
        assertEquals(List.of("a", "b"), t.sent);
        t.complete();
        assertEquals(List.of("a", "b", "c"), t.sent);
    }

    @Test
    void slowClientBacklogIsReplacedBySnapshot() {
        SlowTransport t = new SlowTransport();
        Outbox out = new Outbox(t, 3);
        out.enqueue("f0", () -> "snap");
        out.enqueue("f1", () -> "snap");
        out.enqueue("f2", () -> "snap");
        out.enqueue("f3", () -> "snap");
        assertEquals(3, out.queued());
        out.enqueue("f4", () -> "snap");
        assertEquals(1, out.queued());
        assertEquals(1, out.resyncs());
        t.complete();
        assertEquals(List.of("f0", "snap"), t.sent);
    }

    @Test
    void sendFailureClosesTheClient() {
        SlowTransport t = new SlowTransport();
        Outbox out = new Outbox(t, 3);
        out.enqueue("a", () -> "snap");
        t.pendingFailure.accept(new IOException("reset by peer"));
        assertTrue(out.isClosed());
        assertTrue(t.closed);
        out.enqueue("b", () -> "snap");
        assertEquals(List.of("a"), t.sent);
    }
}
