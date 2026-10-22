package orderbook.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import orderbook.api.ApiException;
import orderbook.sim.ExchangeClient.Credentials;
import orderbook.sim.Simulator;

class AuthServiceDirectoryTest {

    private static final String KEY = "ob_" + "ab".repeat(24);
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";

    private FakeService auth;
    private final AtomicLong clock = new AtomicLong();
    private AuthServiceDirectory directory;

    @BeforeEach
    void start() throws Exception {
        auth = new FakeService()
                .on("GET /internal/api-keys/verify", s -> KEY.equals(s.headers().get("x-api-key"))
                        ? new FakeService.Reply(200, "{\"keyId\":3,\"participantId\":1004,\"accountId\":4,\"userId\":4,"
                                + "\"username\":\"MM-1004\",\"label\":\"MM-1004\",\"kind\":\"simulated\"}")
                        : new FakeService.Reply(401, "{\"error\":\"unauthorized\"}"))
                .on("GET /auth/me", s -> ("Bearer " + JWT).equals(s.headers().get("authorization"))
                        ? new FakeService.Reply(200, "{\"user\":{\"id\":9,\"username\":\"alice\",\"kind\":\"human\"},"
                                + "\"account\":{\"id\":9,\"participantId\":1009,\"label\":\"alice\",\"startingCash\":\"10000.00\"}}")
                        : new FakeService.Reply(401, "{\"error\":\"unauthorized\"}"))
                .on("POST /participants/register", s -> new FakeService.Reply(201,
                        "{\"participantId\":1010,\"label\":\"Whale-1010\",\"apiKey\":\"ob_new\",\"accountId\":10}"))
                .on("GET /internal/participants/1004", s -> new FakeService.Reply(200,
                        "{\"participantId\":1004,\"label\":\"MM-1004\"}"));
        directory = new AuthServiceDirectory(auth.uri(), "s3cret", Duration.ofSeconds(30), HttpClient.newHttpClient(),
                clock::get);
    }

    @AfterEach
    void stop() {
        auth.close();
    }

    @Test
    void resolvesApiKeysThroughTheInternalLookupAndCachesThem() {
        Credentials c = directory.resolve(KEY).orElseThrow();
        assertEquals(1004, c.participantId());
        assertEquals("MM-1004", c.label());
        directory.resolve(" " + KEY + " ");
        assertEquals(1, auth.count("/internal/api-keys/verify"));
        assertEquals("s3cret", auth.seen.get(0).headers().get("x-internal-token"));
        assertEquals("MM-1004", directory.label(1004));

        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        directory.resolve(KEY);
        assertEquals(2, auth.count("/internal/api-keys/verify"));
    }

    @Test
    void unknownKeysAreRejectedAndBrieflyNegativeCached() {
        assertTrue(directory.resolve("ob_unknown").isEmpty());
        assertTrue(directory.resolve("ob_unknown").isEmpty());
        assertEquals(1, auth.count("/internal/api-keys/verify"));
        clock.addAndGet(Duration.ofSeconds(4).toNanos());
        assertTrue(directory.resolve("ob_unknown").isEmpty());
        assertEquals(2, auth.count("/internal/api-keys/verify"));
        assertTrue(directory.resolve("").isEmpty());
        assertTrue(directory.resolve(null).isEmpty());
    }

    @Test
    void sessionTokensResolveToTheSignedUpAccount() {
        Credentials c = directory.resolve(JWT).orElseThrow();
        assertEquals(1009, c.participantId());
        assertEquals("alice", c.label());
        assertTrue(directory.resolve(JWT.replace("c2ln", "bad")).isEmpty());
        assertEquals(0, auth.count("/internal/api-keys/verify"));
    }

    @Test
    void registrationCreatesARealAccountWhoseKeyNeedsNoLookup() {
        Credentials c = directory.register("Whale<script>");
        assertEquals(new Credentials(1010, "Whale-1010", "ob_new"), c);
        assertEquals("{\"name\":\"Whalescript\"}", auth.seen.get(0).body());
        assertEquals(c, directory.resolve("ob_new").orElseThrow());
        assertEquals(0, auth.count("/internal/api-keys/verify"));
    }

    @Test
    void theAnonymousBrowserSeatStaysLocal() {
        Credentials you = directory.resolve(directory.userKey()).orElseThrow();
        assertEquals(Simulator.USER_PARTICIPANT_ID, you.participantId());
        assertEquals("YOU", directory.label(Simulator.USER_PARTICIPANT_ID));
        assertEquals("anon", directory.label(0));
        assertTrue(auth.seen.isEmpty());
    }

    @Test
    void labelsAreFetchedInTheBackgroundWithoutBlocking() throws Exception {
        assertEquals("P1004", directory.label(1004));
        for (int i = 0; i < 100 && directory.label(1004).equals("P1004"); i++) {
            Thread.sleep(20);
        }
        assertEquals("MM-1004", directory.label(1004));
        assertEquals("P4242", directory.label(4242)); // 404: stays P<id>
    }

    @Test
    void anUnreachableAuthServiceIsA503NotA401() {
        AuthServiceDirectory down = new AuthServiceDirectory(URI.create("http://127.0.0.1:1"), null,
                Duration.ofSeconds(30));
        ApiException e = assertThrows(ApiException.class, () -> down.resolve(KEY));
        assertEquals(503, e.status());
        assertEquals("AUTH_UNAVAILABLE", e.reason());
    }

    @Test
    void distinguishesJwtsFromApiKeys() {
        assertTrue(AuthServiceDirectory.isJwt(JWT));
        assertFalse(AuthServiceDirectory.isJwt(KEY));
        assertFalse(AuthServiceDirectory.isJwt("ob_a.b.c"));
    }
}
