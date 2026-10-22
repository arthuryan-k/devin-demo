package simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

class AdminServerTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> call(int port, String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void controlsStartStopRateAndParticipantCount() throws Exception {
        FakeExchange ex = new FakeExchange(MarketSimulatorTest.TICKERS, null);
        try (MarketSimulator sim = new MarketSimulator(1, MarketSimulatorTest.SYMBOLS, ex, ex, ClientLoop.INLINE,
                false); AdminServer admin = new AdminServer(sim, 0)) {
            int port = admin.start();
            assertTrue(call(port, "GET", "/admin/status").body().contains("\"running\":false"));
            HttpResponse<String> started = call(port, "POST", "/admin/start");
            assertEquals(200, started.statusCode());
            assertTrue(started.body().contains("\"running\":true"));
            assertTrue(call(port, "POST", "/admin/rate?value=50").body().contains("\"rate\":50.0"));
            assertEquals(50.0, sim.rate());
            assertTrue(call(port, "POST", "/admin/participants?value=6").body()
                    .contains("\"targetParticipants\":6"));
            assertEquals(400, call(port, "POST", "/admin/rate?value=-1").statusCode());
            assertEquals(400, call(port, "POST", "/admin/participants").statusCode());
            assertEquals(405, call(port, "GET", "/admin/start").statusCode());
            assertEquals(404, call(port, "POST", "/admin/nope").statusCode());
            sim.runSteps(200);
            String status = call(port, "GET", "/admin/status").body();
            assertTrue(status.contains("\"symbol\":\"NVTA\""), status);
            assertTrue(status.contains("\"persona\":\"MARKET_MAKER\""), status);
            assertTrue(call(port, "POST", "/admin/stop").body().contains("\"running\":false"));
            assertEquals("*", started.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        }
    }
}
