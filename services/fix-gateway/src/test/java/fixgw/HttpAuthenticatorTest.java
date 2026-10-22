package fixgw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Against a stub with auth-service's /internal/api-keys/verify contract. */
class HttpAuthenticatorTest {

    @Test
    void verifiesKeysAndCachesGoodOnes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/internal/api-keys/verify", ex -> {
            calls.incrementAndGet();
            boolean ok = "ob_good".equals(ex.getRequestHeaders().getFirst("X-Api-Key"))
                    && "s3cret".equals(ex.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] out = (ok ? "{\"keyId\":1,\"participantId\":1042,\"accountId\":42,\"label\":\"fix-1042\"}"
                    : "{\"error\":\"unauthorized\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 401, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
        try {
            HttpAuthenticator auth = new HttpAuthenticator("http://127.0.0.1:" + stub.getAddress().getPort(), "s3cret");
            assertEquals(1042, auth.authenticate("fix", "ob_good"));
            assertEquals(1042, auth.authenticate("fix", "ob_good"));
            assertEquals(1, calls.get());
            assertEquals(-1, auth.authenticate("fix", "ob_bad"));
            assertEquals(-1, auth.authenticate("fix", ""));
            assertEquals(-1, new HttpAuthenticator("http://127.0.0.1:1", null).authenticate("fix", "ob_good"));
        } finally {
            stub.stop(0);
        }
    }
}
