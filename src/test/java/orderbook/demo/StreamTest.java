package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.sim.Simulator;

class StreamTest {

    private static final long OTHER = 5_000;

    private DemoServer server;
    private WebSocketClient ws;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = new DemoServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), new Simulator(1),
                () -> new Account(DemoServer.USER, 10_000_00, 100));
        server.start();
        ws = new WebSocketClient();
        ws.start();
    }

    @AfterEach
    void stop() throws Exception {
        ws.stop();
        server.stop();
    }

    /** Collects text frames and checks that deltas chain onto the last frame seen. */
    public static final class Frames implements Session.Listener.AutoDemanding {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        volatile Session session;
        long lastSeq = -1;

        @Override
        public void onWebSocketOpen(Session s) {
            session = s;
        }

        @Override
        public void onWebSocketText(String text) {
            queue.add(text);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            // connection torn down at the end of a test
        }

        String next() throws InterruptedException {
            String frame = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "no frame within 5s");
            if (type(frame).equals("delta") && lastSeq >= 0) {
                assertEquals(lastSeq, num(frame, "prevSeq"), "delta must chain onto the previous frame");
            }
            lastSeq = num(frame, "seq");
            return frame;
        }

        String until(Predicate<String> match) throws InterruptedException {
            while (true) {
                String frame = next();
                if (match.test(frame)) {
                    return frame;
                }
            }
        }
    }

    private Frames connect(String query) throws Exception {
        Frames frames = new Frames();
        ws.connect(frames, URI.create("ws://127.0.0.1:" + server.port() + "/ws" + query)).get(5, TimeUnit.SECONDS);
        return frames;
    }

    private void post(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private void counterparty(long id, Side side, long priceTicks, long qty) {
        server.simulator().submit(OTHER, new Command.Place(Order.limit(id, OTHER, side, priceTicks, qty, TimeInForce.GTC)));
    }

    static String type(String frame) {
        Matcher m = Pattern.compile("^\\{\"type\":\"(\\w+)\"").matcher(frame);
        assertTrue(m.find(), frame);
        return m.group(1);
    }

    static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue(m.find(), key + " in " + json);
        return Long.parseLong(m.group(1));
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void connectSendsSnapshotThenChainedDeltas() throws Exception {
        counterparty(1, Side.SELL, 100_00, 10);
        Frames f = connect("");
        String snapshot = f.next();
        assertEquals("snapshot", type(snapshot));
        assertTrue(snapshot.contains("\"reason\":\"connect\""));
        assertTrue(snapshot.contains("{\"id\":1,\"side\":\"SELL\",\"price\":100.00,\"qty\":10"), snapshot);

        post("/api/orders", "side=BUY&price=100.00&qty=4");
        String partial = f.until(d -> d.contains("\"n\":1,"));
        assertEquals("delta", type(partial));
        assertTrue(partial.contains("{\"id\":1,\"side\":\"SELL\",\"price\":100.00,\"qty\":6"), partial);
        assertTrue(partial.contains("\"sharesOwned\":104"), partial);

        post("/api/orders", "side=BUY&price=100.00&qty=6");
        String filled = f.until(d -> d.contains("\"n\":2,"));
        assertTrue(filled.matches(".*\"removed\":\\[[^\\]]*\\b1\\b.*"), filled);
        assertTrue(filled.contains("\"restingOrders\":0"), filled);
    }

    @Test
    void burstsAreCoalescedAndIdleSendsNothing() throws Exception {
        Frames f = connect("");
        f.next();
        server.simulator().run(() -> {
            for (int i = 0; i < 50; i++) {
                counterparty(100 + i, Side.BUY, 90_00 - i, 1);
            }
        });
        String delta = f.next();
        assertEquals("delta", type(delta));
        String orders = delta.substring(delta.indexOf("\"orders\":"), delta.indexOf("\"removed\":"));
        assertEquals(50, count(orders, "\"id\":"));
        assertNull(f.queue.poll(300, TimeUnit.MILLISECONDS), "no frames while nothing changes");
    }

    @Test
    void reconnectWithSinceReplaysMissedDeltas() throws Exception {
        Frames f = connect("");
        long seq = num(f.next(), "seq");
        f.session.close();
        counterparty(1, Side.SELL, 101_00, 1);
        Thread.sleep(150);
        counterparty(2, Side.SELL, 102_00, 1);
        Thread.sleep(150);

        Frames resumed = connect("?since=" + seq);
        resumed.lastSeq = seq;
        String first = resumed.next();
        assertEquals("delta", type(first));
        resumed.until(d -> d.contains("{\"id\":2,"));
    }

    @Test
    void unknownSinceGetsSnapshot() throws Exception {
        assertEquals("snapshot", type(connect("?since=999999").next()));
    }

    @Test
    void resetBroadcastsFreshSnapshot() throws Exception {
        counterparty(1, Side.SELL, 100_00, 10);
        Frames f = connect("");
        f.next();
        post("/api/reset", "");
        String reset = f.until(d -> type(d).equals("snapshot"));
        assertTrue(reset.contains("\"reason\":\"reset\""));
        assertTrue(reset.contains("\"orders\":[]"), reset);
    }
}
