package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.sim.Simulator;

/** {@code GET /marketdata/stream} over real HTTP, and HTTP + simulator producers sharing the pipeline. */
class StreamTest {

    private static final long OTHER = 5_000;
    private static final Pattern SEQ = Pattern.compile("\"seq\":(-?\\d+)");

    private DemoServer server;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final List<CompletableFuture<?>> streams = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = new DemoServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), new Simulator(1),
                () -> new Account(DemoServer.USER, 10_000_00, 100));
        server.start();
        // The page sets the browser session cookie (the reserved "YOU" key) that authenticates /api/orders.
        client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterEach
    void stop() {
        streams.forEach(f -> f.cancel(true));
        server.stop();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    /** Opens the SSE stream; each {@code data:} payload lands in the returned queue. */
    private BlockingQueue<String> subscribe() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(URI.create(url(DemoServer.STREAM_PATH))).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        streams.add(CompletableFuture.runAsync(() -> response.body()
                .filter(line -> line.startsWith("data: "))
                .forEach(line -> messages.add(line.substring(6)))));
        return messages;
    }

    private static String next(BlockingQueue<String> messages) throws InterruptedException {
        String m = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(m, "no stream message within 5 s");
        return m;
    }

    private static long seq(String message) {
        Matcher m = SEQ.matcher(message);
        assertTrue(m.find(), message);
        return Long.parseLong(m.group(1));
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void counterparty(long id, Side side, long priceTicks, long qty) {
        server.simulator().submit(OTHER, new Command.Place(Order.limit(id, OTHER, side, priceTicks, qty, TimeInForce.GTC)));
    }

    @Test
    void firstMessageIsSnapshotThenOneDeltaPerSequence() throws Exception {
        counterparty(1, Side.SELL, 100_00, 10);
        BlockingQueue<String> messages = subscribe();
        String snapshot = next(messages);
        assertTrue(snapshot.startsWith("{\"type\":\"snapshot\",\"seq\":0,\"reason\":\"connect\""), snapshot);
        assertTrue(snapshot.contains("\"asks\":[[10000,10,1]]"), snapshot);

        counterparty(2, Side.SELL, 100_00, 5);
        String add = next(messages);
        assertEquals(1, seq(add));
        assertTrue(add.contains("\"levels\":[[\"u\",\"S\",10000,15,2]]"), add);

        assertEquals(200, post("/api/orders", "side=BUY&type=LIMIT&price=100.00&qty=12&timeInForce=GTC").statusCode());
        String trade = next(messages);
        assertEquals(2, seq(trade));
        assertTrue(trade.contains("\"levels\":[[\"u\",\"S\",10000,3,1]]"), trade);
        assertTrue(trade.contains("\"type\":\"TradeExecuted\"") && trade.contains("\"taker\":\"YOU\""), trade);
    }

    @Test
    void resetBroadcastsAnEmptySnapshot() throws Exception {
        counterparty(1, Side.BUY, 99_00, 4);
        BlockingQueue<String> messages = subscribe();
        next(messages);
        assertEquals(200, post("/api/reset", "").statusCode());
        String reset;
        do {
            reset = next(messages);
        } while (!reset.startsWith("{\"type\":\"snapshot\""));
        assertTrue(reset.contains("\"reason\":\"reset\",\"bids\":[],\"asks\":[]"), reset);
    }

    @Test
    void concurrentHttpAndSimulatorSubmissionsShareOneSequence() throws Exception {
        BlockingQueue<String> messages = subscribe();
        long first = seq(next(messages));
        assertEquals(200, post("/simulate/rate?perSec=100", "").statusCode());
        assertEquals(200, post("/simulate/start", "").statusCode());
        int threads = 4;
        int perThread = 15;
        Set<Long> httpSeqs = Collections.synchronizedSet(new HashSet<>());
        List<Thread> workers = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        HttpResponse<String> r = post("/api/orders", "side=BUY&type=LIMIT&price=1.00&qty=1&timeInForce=GTC");
                        assertEquals(200, r.statusCode(), r.body());
                        assertTrue(r.body().contains("\"type\":\"OrderPlaced\""), r.body());
                        assertTrue(httpSeqs.add(seq(r.body())), "duplicate seq");
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(threads * perThread, httpSeqs.size());
        assertEquals(200, post("/simulate/stop", "").statusCode());

        long last = server.simulator().execute(() -> {
            server.simulator().catchUp();
            return server.simulator().appliedSeq();
        });
        assertTrue(last - first > threads * perThread, "the simulator also traded: " + (last - first));
        long expect = first + 1;
        while (expect <= last) {
            String m = next(messages);
            assertEquals(expect, seq(m), "stream sequence is contiguous");
            expect++;
        }
        server.simulator().run(() -> {
            for (Side side : Side.values()) {
                assertEquals(server.simulator().engine().book().depth(side, Integer.MAX_VALUE),
                        server.marketData().levels(side), "published levels match the engine replica");
            }
        });
    }
}
