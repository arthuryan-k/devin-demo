package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.Side;
import orderbook.api.AdmissionControl;
import orderbook.sim.Simulator;

/** The participant API end to end over HTTP: registration, API keys, admission limits, simulator as a client. */
class ParticipantApiTest {

    private static final Pattern PARTICIPANT_ID = Pattern.compile("\"participantId\":(\\d+)");
    private static final Pattern API_KEY = Pattern.compile("\"apiKey\":\"([^\"]+)\"");
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\":(\\d+)");
    private static final String RESTING_BID = "side=BUY&type=LIMIT&price=1.00&qty=1&timeInForce=GTC";

    private record Client(long participantId, String apiKey) {
    }

    private DemoServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    private DemoServer start(AdmissionControl.Limits limits) throws Exception {
        server = new DemoServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), new Simulator(7),
                () -> new Account(DemoServer.USER, 10_000_00, 100), limits);
        server.start();
        return server;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> send(String method, String path, String apiKey, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(form == null ? "" : form));
        if (apiKey != null) {
            b.header("X-Api-Key", apiKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Client register(String name) throws Exception {
        HttpResponse<String> res = send("POST", "/participants/register", null, "name=" + name);
        assertEquals(200, res.statusCode(), res.body());
        return new Client(Long.parseLong(find(PARTICIPANT_ID, res.body())), find(API_KEY, res.body()));
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), text);
        return m.group(1);
    }

    private int openOrders(long participantId) {
        return server.simulator().execute(() -> server.simulator().ownOrders(participantId).size());
    }

    @Test
    void registeredClientTradesUnderItsOwnIdentity() throws Exception {
        start(AdmissionControl.Limits.DEFAULT);
        Client alice = register("alice");
        Client bob = register("bob");
        assertNotEquals(alice.participantId(), bob.participantId());
        assertNotEquals(Simulator.USER_PARTICIPANT_ID, alice.participantId());
        assertNotEquals(alice.apiKey(), bob.apiKey());

        HttpResponse<String> placed = send("POST", "/api/orders", alice.apiKey(), RESTING_BID);
        assertEquals(200, placed.statusCode(), placed.body());
        assertTrue(placed.body().contains("\"type\":\"OrderPlaced\""), placed.body());
        assertFalse(placed.body().contains("\"book\""), "the YOU book/account view is for the browser session only");
        long orderId = Long.parseLong(find(ORDER_ID, placed.body()));
        Order resting = server.simulator().execute(() -> server.simulator().engine().book().find(orderId).orElseThrow());
        assertEquals(alice.participantId(), resting.participantId());
        assertTrue(send("GET", "/api/book", null, null).body().contains("\"participant\":\"alice-" + alice.participantId()));

        String theirs = send("DELETE", "/api/orders/" + orderId, bob.apiKey(), null).body();
        assertTrue(theirs.contains("\"type\":\"Rejected\"") && theirs.contains("\"reason\":\"NOT_OWN_OPEN_ORDER\""),
                "another participant's key cannot touch the order: " + theirs);
        assertEquals(1, openOrders(alice.participantId()));
        assertEquals(200, send("PATCH", "/api/orders/" + orderId, alice.apiKey(), "qty=3").statusCode());
        HttpResponse<String> cancelled = send("DELETE", "/api/orders/" + orderId, alice.apiKey(), null);
        assertEquals(200, cancelled.statusCode());
        assertTrue(cancelled.body().contains("\"type\":\"OrderCancelled\""), cancelled.body());
        assertEquals(0, openOrders(alice.participantId()));

        assertEquals(400, send("POST", "/api/orders", alice.apiKey(), RESTING_BID + "&participantId=YOU").statusCode(),
                "an API client cannot claim to be YOU");
    }

    @Test
    void missingOrUnknownKeysAreRejectedBeforeTheEngine() throws Exception {
        start(AdmissionControl.Limits.DEFAULT);
        Client alice = register("alice");
        long id = Long.parseLong(find(ORDER_ID, send("POST", "/api/orders", alice.apiKey(), RESTING_BID).body()));
        long applied = server.simulator().execute(server.simulator()::appliedSeq);
        for (String key : new String[] { null, "", "ob_not-a-real-key" }) {
            String reason = key == null || key.isEmpty() ? "MISSING_API_KEY" : "INVALID_API_KEY";
            for (HttpResponse<String> res : List.of(send("POST", "/api/orders", key, RESTING_BID),
                    send("PATCH", "/api/orders/" + id, key, "qty=2"), send("DELETE", "/api/orders/" + id, key, null))) {
                assertEquals(401, res.statusCode(), res.body());
                assertTrue(res.body().contains("\"reason\":\"" + reason + "\""), res.body());
            }
        }
        assertEquals(applied, (long) server.simulator().execute(server.simulator()::appliedSeq), "nothing published");
        assertEquals(1, openOrders(alice.participantId()));
    }

    @Test
    void floodingPastTheTokenBucketGets429AndNeverReachesTheRing() throws Exception {
        start(new AdmissionControl.Limits(5, 10, 1_000, 1_000));
        Client flooder = register("flood");
        Client polite = register("polite");
        long before = server.simulator().execute(server.simulator()::appliedSeq);
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> res = send("POST", "/api/orders", flooder.apiKey(), RESTING_BID);
            if (res.statusCode() == 200) {
                ok++;
            } else {
                assertEquals(429, res.statusCode(), res.body());
                assertTrue(res.body().contains("\"reason\":\"RATE_LIMITED\""), res.body());
                limited++;
            }
        }
        assertTrue(ok >= 10 && ok < 20, "burst of 10 plus a little refill, got " + ok);
        assertTrue(limited > 20, "got " + limited);
        assertEquals(ok, openOrders(flooder.participantId()), "only admitted orders rest");
        assertEquals(before + ok, (long) server.simulator().execute(server.simulator()::appliedSeq),
                "rejected orders were never published");
        assertEquals(200, send("POST", "/api/orders", polite.apiKey(), RESTING_BID).statusCode(),
                "buckets are per participant");
    }

    @Test
    void maxOpenOrdersIsEnforcedPerParticipant() throws Exception {
        start(new AdmissionControl.Limits(1_000, 1_000, 3, 1_000));
        Client alice = register("alice");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(Long.parseLong(find(ORDER_ID, send("POST", "/api/orders", alice.apiKey(), RESTING_BID).body())));
        }
        HttpResponse<String> fourth = send("POST", "/api/orders", alice.apiKey(), RESTING_BID);
        assertEquals(429, fourth.statusCode());
        assertTrue(fourth.body().contains("\"reason\":\"MAX_OPEN_ORDERS\""), fourth.body());
        assertEquals(3, openOrders(alice.participantId()));
        assertEquals(200, send("PATCH", "/api/orders/" + ids.get(0), alice.apiKey(), "qty=5").statusCode(),
                "amends do not add orders");
        assertEquals(200, send("DELETE", "/api/orders/" + ids.get(0), alice.apiKey(), null).statusCode());
        assertEquals(200, send("POST", "/api/orders", alice.apiKey(), RESTING_BID).statusCode());
        assertEquals(200, send("POST", "/api/orders", register("bob").apiKey(), RESTING_BID).statusCode());
    }

    @Test
    void repeatedViolatorsAreBlocked() throws Exception {
        start(new AdmissionControl.Limits(1_000, 1_000, 1, 3));
        Client alice = register("alice");
        long id = Long.parseLong(find(ORDER_ID, send("POST", "/api/orders", alice.apiKey(), RESTING_BID).body()));
        for (int i = 0; i < 3; i++) {
            assertEquals(429, send("POST", "/api/orders", alice.apiKey(), RESTING_BID).statusCode());
        }
        HttpResponse<String> blocked = send("DELETE", "/api/orders/" + id, alice.apiKey(), null);
        assertEquals(403, blocked.statusCode());
        assertTrue(blocked.body().contains("\"reason\":\"BLOCKED\""), blocked.body());
        assertEquals(200, send("POST", "/api/orders", register("bob").apiKey(), RESTING_BID).statusCode());
    }

    @Test
    void browserSessionCookieIsYou() throws Exception {
        start(AdmissionControl.Limits.DEFAULT);
        HttpResponse<String> page = send("GET", "/", null, null);
        String cookie = page.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith(DemoServer.SESSION_COOKIE + "=") && cookie.contains("HttpOnly"), cookie);
        HttpRequest withCookie = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/orders"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookie.substring(0, cookie.indexOf(';')))
                .POST(HttpRequest.BodyPublishers.ofString(RESTING_BID)).build();
        HttpResponse<String> res = http.send(withCookie, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"book\""), "YOU still gets the account view");
        assertEquals(1, openOrders(Simulator.USER_PARTICIPANT_ID));
    }

    @Test
    void simulatorTradesOverHttpWithinTheDefaultLimitsAndNeverAsYou() throws Exception {
        start(AdmissionControl.Limits.DEFAULT);
        Simulator sim = server.simulator();
        List<Object[]> flow = Collections.synchronizedList(new ArrayList<>());
        sim.addListener((participant, command, events) -> flow.add(new Object[] { participant, command, events }));
        assertEquals(200, send("POST", "/simulate/rate?perSec=" + (int) Simulator.MAX_RATE, null, null).statusCode());
        assertEquals(200, send("POST", "/simulate/start", null, null).statusCode());
        Thread.sleep(4_000);
        assertEquals(200, send("POST", "/simulate/stop", null, null).statusCode());

        Map<Integer, Long> replies = sim.clientReplies();
        assertEquals(Set.of(200), replies.keySet(), "every simulated API call succeeded: " + replies);
        assertTrue(replies.get(200) > 100, "simulated clients were active: " + replies);

        Set<Event.OrderRejected.Reason> reasons = EnumSet.noneOf(Event.OrderRejected.Reason.class);
        boolean sawPlace = false;
        synchronized (flow) {
            for (Object[] entry : flow) {
                long participant = (long) entry[0];
                assertNotEquals(Simulator.USER_PARTICIPANT_ID, participant);
                assertNotEquals(Simulator.USER_LABEL, sim.label(participant));
                assertTrue(sim.label(participant).matches("(MM|TAKER|MAINT|WHALE)-\\d+"), sim.label(participant));
                if (entry[1] instanceof Command.Place place) {
                    sawPlace = true;
                    assertEquals(participant, place.order().participantId());
                    assertTrue(place.order().qtyRemaining() > 0);
                }
                @SuppressWarnings("unchecked")
                List<Event> events = (List<Event>) entry[2];
                for (Event e : events) {
                    if (e instanceof Event.OrderRejected r) {
                        reasons.add(r.reason());
                    }
                }
            }
        }
        assertTrue(sawPlace);
        assertTrue(EnumSet.of(Event.OrderRejected.Reason.UNKNOWN_ORDER_ID).containsAll(reasons),
                "only races with fills (cancel/amend of an order that just traded) may be rejected: " + reasons);
        sim.run(() -> {
            assertFalse(sim.engine().book().isCrossed());
            for (Side side : Side.values()) {
                assertTrue(sim.engine().book().orders(side).isEmpty(), "stop leaves no simulated order resting");
            }
        });
        assertTrue(sim.participants().isEmpty());
    }
}
