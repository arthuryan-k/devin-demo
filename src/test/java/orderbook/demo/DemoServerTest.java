package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoServerTest {

    private DemoServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        server = new DemoServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
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

    private static String compact(String json) {
        return json.replace(" ", "");
    }

    @Test
    void servesIndexPage() throws Exception {
        HttpResponse<String> res = send("GET", "/", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("Order Book Demo"));
        assertEquals(404, send("GET", "/nope", null).statusCode());
    }

    @Test
    void placeMatchAndCancelRoundTrip() throws Exception {
        String rest = send("POST", "/api/orders", "side=SELL&price=100&qty=10").body();
        assertTrue(rest.contains("\"type\":\"OrderPlaced\",\"orderId\":1"), rest);

        String cross = send("POST", "/api/orders", "side=buy&price=101&qty=4").body();
        assertTrue(cross.contains("\"trade\":{\"makerOrderId\":1,\"takerOrderId\":2,\"price\":100,\"qty\":4}"), cross);

        String book = send("GET", "/api/book", null).body();
        assertTrue(compact(book).contains("\"asks\":[{\"price\":100,\"totalQty\":6,\"orders\":[{\"id\":1,\"qty\":6,\"seqNum\":0}]}]"), book);
        assertTrue(book.contains("\"bestBid\":null"), book);
        assertTrue(book.contains("\"tradeCount\":1"), book);

        String cancelled = send("DELETE", "/api/orders/1", null).body();
        assertTrue(cancelled.contains("\"type\":\"OrderCancelled\",\"orderId\":1,\"cancelledQty\":6"), cancelled);

        String unknown = send("DELETE", "/api/orders/1", null).body();
        assertTrue(unknown.contains("\"reason\":\"UNKNOWN_ORDER_ID\""), unknown);
    }

    @Test
    void engineRejectionsAreReturnedAsEvents() throws Exception {
        String res = send("POST", "/api/orders", "side=BUY&price=100&qty=0").body();
        assertTrue(res.contains("\"reason\":\"NON_POSITIVE_QUANTITY\""), res);

        send("POST", "/api/orders", "side=BUY&price=100&qty=5&id=7");
        String dup = send("POST", "/api/orders", "side=SELL&price=105&qty=5&id=7").body();
        assertTrue(dup.contains("\"reason\":\"DUPLICATE_ORDER_ID\""), dup);
        assertTrue(dup.contains("\"nextOrderId\":8"), dup);
    }

    @Test
    void malformedRequestsGet4xx() throws Exception {
        assertEquals(400, send("POST", "/api/orders", "side=HOLD&price=100&qty=1").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&price=abc&qty=1").statusCode());
        assertEquals(400, send("POST", "/api/orders", "side=BUY&qty=1").statusCode());
        assertEquals(400, send("DELETE", "/api/orders/xyz", null).statusCode());
        assertEquals(405, send("GET", "/api/orders", null).statusCode());
    }

    @Test
    void resetClearsState() throws Exception {
        send("POST", "/api/orders", "side=BUY&price=100&qty=5");
        String res = send("POST", "/api/reset", "").body();
        assertTrue(res.contains("\"bids\":[]"), res);
        assertTrue(res.contains("\"nextOrderId\":1"), res);
    }
}
