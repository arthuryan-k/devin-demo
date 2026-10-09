package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.sim.Simulator;

class DemoServerTest {

    private static final long OTHER = 5_000;

    private DemoServer server;
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

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
        server.stop();
    }

    private HttpResponse<String> send(String method, String path, String form) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (form == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(form));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String body(String method, String path, String form) throws Exception {
        return compact(send(method, path, form).body());
    }

    private static String compact(String json) {
        return json.replace(" ", "");
    }

    /** A resting order from a non-user participant, entered through the simulator's command path. */
    private void counterparty(long id, Side side, long priceTicks, long qty) {
        server.simulator().submit(OTHER, new Command.Place(Order.limit(id, OTHER, side, priceTicks, qty, TimeInForce.GTC)));
    }

    @Test
    void servesIndexPage() throws Exception {
        HttpResponse<String> res = send("GET", "/", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("Order Book Demo"));
        assertTrue(res.body().contains("Balance sheet"));
        assertEquals(404, send("GET", "/nope", null).statusCode());
    }

    @Test
    void placeMatchAndCancelRoundTripInDecimalPrices() throws Exception {
        counterparty(1, Side.SELL, 100_00, 10);

        String cross = body("POST", "/api/orders", "participantId=YOU&side=buy&price=101.00&qty=4");
        assertTrue(cross.contains("\"makerOrderId\":1,\"takerOrderId\":2"), cross);
        assertTrue(cross.contains("\"takerParticipant\":\"YOU\",\"takerSide\":\"BUY\",\"price\":100.00,\"qty\":4"), cross);

        String book = body("GET", "/api/book", null);
        assertTrue(book.contains("\"asks\":[{\"price\":100.00,\"totalQty\":6,\"orders\":[{\"id\":1,\"qty\":6"), book);
        assertTrue(book.contains("\"bestBid\":null"), book);
        assertTrue(book.contains("\"tradeCount\":1"), book);
        assertTrue(book.contains("\"lastTradePrice\":100.00"), book);
        assertTrue(book.contains("\"cash\":9600.00,\"reservedCash\":0.00,\"availableCash\":9600.00,\"sharesOwned\":104"), book);
        assertTrue(book.contains("\"equity\":20000.00"), book);

        String theirs = body("DELETE", "/api/orders/1", null);
        assertTrue(theirs.contains("\"type\":\"AccountRejected\",\"orderId\":1"), theirs);
        assertTrue(theirs.contains("\"reason\":\"NOT_OWN_OPEN_ORDER\""), theirs);

        String rest = body("POST", "/api/orders", "side=BUY&price=99.50&qty=10");
        assertTrue(rest.contains("\"type\":\"OrderPlaced\",\"orderId\":3,\"participant\":\"YOU\",\"side\":\"BUY\",\"price\":99.50"), rest);
        assertTrue(rest.contains("\"reservedCash\":995.00"), rest);
        assertTrue(rest.contains("\"participant\":\"YOU\",\"mine\":true"), rest);

        String amended = body("PATCH", "/api/orders/3", "qty=4");
        assertTrue(amended.contains("\"type\":\"OrderAmended\""), amended);
        assertTrue(amended.contains("\"reservedCash\":398.00"), amended);

        String cancelled = body("DELETE", "/api/orders/3", null);
        assertTrue(cancelled.contains("\"type\":\"OrderCancelled\",\"orderId\":3,\"participant\":\"YOU\",\"cancelledQty\":4"), cancelled);
        assertTrue(cancelled.contains("\"reservedCash\":0.00"), cancelled);
    }

    @Test
    void marketOrdersNeedNoPrice() throws Exception {
        counterparty(1, Side.SELL, 99_50, 5);
        String res = body("POST", "/api/orders", "side=BUY&type=MARKET&timeInForce=IOC&qty=2");
        assertTrue(res.contains("\"price\":99.50,\"qty\":2"), res);
        assertTrue(res.contains("\"sharesOwned\":102"), res);

        counterparty(100, Side.BUY, 99_00, 5);
        String sell = body("POST", "/api/orders", "side=SELL&type=market&timeInForce=ioc&qty=3");
        assertTrue(sell.contains("\"price\":99.00,\"qty\":3"), sell);
        assertTrue(sell.contains("\"sharesOwned\":99"), sell);
    }

    @Test
    void accountRejectionsAreTicketErrorsAndFeedEvents() throws Exception {
        String funds = body("POST", "/api/orders", "side=BUY&price=100&qty=1000");
        assertTrue(funds.contains("\"type\":\"AccountRejected\""), funds);
        assertTrue(funds.contains("\"reason\":\"INSUFFICIENT_FUNDS\""), funds);
        assertTrue(funds.contains("\"bids\":[]"), funds);

        String shares = body("POST", "/api/orders", "side=SELL&price=100&qty=101");
        assertTrue(shares.contains("\"reason\":\"INSUFFICIENT_SHARES\""), shares);

        String feed = body("GET", "/api/book?since=0", null);
        assertTrue(feed.contains("{\"seq\":1,\"type\":\"AccountRejected\""), feed);
        assertTrue(feed.contains("\"seq\":2,\"type\":\"AccountRejected\""), feed);
        String none = body("GET", "/api/book?since=2", null);
        assertTrue(none.contains("\"events\":[]"), none);
    }

    @Test
    void engineRejectionsAreReturnedAsEvents() throws Exception {
        String res = body("POST", "/api/orders", "side=BUY&price=100&qty=0");
        assertTrue(res.contains("\"reason\":\"NON_POSITIVE_QUANTITY\""), res);

        body("POST", "/api/orders", "side=BUY&price=100&qty=5&id=7");
        String dup = body("POST", "/api/orders", "side=SELL&price=105&qty=5&id=7");
        assertTrue(dup.contains("\"reason\":\"DUPLICATE_ORDER_ID\""), dup);
        assertTrue(dup.contains("\"nextOrderId\":8"), dup);
        assertTrue(dup.contains("\"reservedCash\":500.00"), "the resting order keeps its reservation: " + dup);
        assertTrue(dup.contains("\"reservedShares\":0"), dup);
    }

    @Test
    void malformedRequestsGet4xx() throws Exception {
        assertEquals(400, send("POST", "/api/orders", "side=HOLD&price=100&qty=1").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&price=abc&qty=1").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&price=100.001&qty=1").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&qty=1").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&price=100&qty=1&type=STOP").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&price=100&qty=1&timeInForce=DAY").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&price=100&qty=1&participantId=MM-1001").statusCode());
        assertEquals(400, send("PATCH", "/api/orders/1", "").statusCode());
        assertEquals(400, send("DELETE", "/api/orders/xyz", null).statusCode());
        assertEquals(405, send("GET", "/api/orders", null).statusCode());
        assertEquals(400, send("POST", "/simulate/rate?perSec=0", null).statusCode());
        assertEquals(400, send("POST", "/simulate/rate", null).statusCode());
        assertEquals(405, send("GET", "/simulate/start", null).statusCode());
        assertEquals(404, send("POST", "/simulate/nope", null).statusCode());
    }

    @Test
    void simulationLifecycleOverHttp() throws Exception {
        String initial = body("GET", "/api/book", null);
        assertTrue(initial.contains("\"running\":false"), "simulation must start stopped: " + initial);
        assertTrue(initial.contains("\"participants\":[]"), initial);

        String rate = body("POST", "/simulate/rate?perSec=20", null);
        assertTrue(rate.contains("\"ratePerSec\":20.0"), rate);

        String started = body("POST", "/simulate/start", null);
        assertTrue(started.contains("\"running\":true"), started);
        assertTrue(started.contains("\"kind\":\"MARKET_MAKER\""), started);

        long deadline = System.currentTimeMillis() + 5_000;
        String book = body("GET", "/api/book", null);
        while (!book.contains("\"participant\":\"MM-") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            book = body("GET", "/api/book", null);
        }
        assertTrue(book.contains("\"participant\":\"MM-"), book);
        assertTrue(!book.contains("\"events\":[]"), "simulated events reach the feed");

        String stopped = body("POST", "/simulate/stop", null);
        assertTrue(stopped.contains("\"running\":false"), stopped);
        assertTrue(stopped.contains("\"participants\":[]"), stopped);
        assertTrue(stopped.contains("\"bids\":[],\"asks\":[]"), stopped);
    }

    @Test
    void resetClearsStateAndAccount() throws Exception {
        body("POST", "/api/orders", "side=BUY&price=100&qty=5");
        body("POST", "/simulate/start", null);
        String res = body("POST", "/api/reset", "");
        assertTrue(res.contains("\"bids\":[]"), res);
        assertTrue(res.contains("\"nextOrderId\":1"), res);
        assertTrue(res.contains("\"running\":false"), res);
        assertTrue(res.contains("\"reservedCash\":0.00"), res);
        assertTrue(res.contains("\"tradeCount\":0"), res);
    }
}
