package simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Against a stub with auth-service's /participants/register contract. */
class AuthClientTest {

    @Test
    void eachSignUpIsANewAccount() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger next = new AtomicInteger(1001);
        stub.createContext("/participants/register", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String name = body.replaceAll(".*\"name\":\"([^\"]*)\".*", "$1");
            int pid = next.getAndIncrement();
            byte[] out = ("{\"participantId\":" + pid + ",\"label\":\"" + name + "-" + pid
                    + "\",\"apiKey\":\"ob_" + pid + "\",\"accountId\":" + pid + ",\"userId\":" + pid
                    + ",\"startingCash\":\"10000.00\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(name.equals("BAD") ? 500 : 201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
        try {
            AuthClient auth = new AuthClient("http://127.0.0.1:" + stub.getAddress().getPort() + "/",
                    HttpClient.newHttpClient());
            ExchangeClient.Credentials a = auth.register("MM_ACME_1");
            ExchangeClient.Credentials b = auth.register("MM_ACME_1");
            assertEquals(new ExchangeClient.Credentials(1001, "MM_ACME_1-1001", "ob_1001"), a);
            assertEquals(1002, b.participantId());
            assertThrows(IllegalStateException.class, () -> auth.register("BAD"));
        } finally {
            stub.stop(0);
        }
    }
}
