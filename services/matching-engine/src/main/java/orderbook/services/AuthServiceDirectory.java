package orderbook.services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import orderbook.Order;
import orderbook.api.ApiException;
import orderbook.api.ParticipantDirectory;
import orderbook.api.ParticipantRegistry;
import orderbook.sim.ExchangeClient.Credentials;
import orderbook.sim.Simulator;

/**
 * {@link ParticipantDirectory} backed by the auth service (participants, accounts and API keys in Postgres). API keys
 * are checked with {@code GET /internal/api-keys/verify}, session JWTs (the auth service's {@code ob_session} cookie)
 * with {@code GET /auth/me}, and {@link #register} creates a real simulated account. Lookups are cached for
 * {@code ttl} (unknown keys for a fraction of it) so the order path rarely leaves the process. Only the anonymous
 * browser seat ("YOU", participant 1) stays local.
 */
public final class AuthServiceDirectory implements ParticipantDirectory {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private record Cached(Optional<Credentials> credentials, long expiresAtNanos) {
    }

    private final URI base;
    private final String internalToken;
    private final HttpClient http;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final String userKey;
    private final Map<String, Cached> resolved = new ConcurrentHashMap<>();
    private final Map<Long, String> labels = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> labelLookups = new ConcurrentHashMap<>();

    /** {@code internalToken} (nullable) is sent on {@code /internal/*} calls when the auth service requires one. */
    public AuthServiceDirectory(URI base, String internalToken, Duration ttl) {
        this(base, internalToken, ttl, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), System::nanoTime);
    }

    AuthServiceDirectory(URI base, String internalToken, Duration ttl, HttpClient http, LongSupplier clock) {
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.internalToken = internalToken == null || internalToken.isBlank() ? null : internalToken.trim();
        this.http = http;
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
        this.userKey = new ParticipantRegistry(new SecureRandom()).userKey();
    }

    @Override
    public String userKey() {
        return userKey;
    }

    @Override
    public Credentials register(String name) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "");
        HttpResponse<String> res = send(HttpRequest.newBuilder(base.resolve("participants/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + clean + "\"}")));
        if (res.statusCode() != 201 && res.statusCode() != 200) {
            throw unavailable("registration failed with HTTP " + res.statusCode());
        }
        Map<String, Object> body = JsonReader.object(res.body());
        Credentials c = new Credentials(longField(body, "participantId"), (String) body.get("label"),
                (String) body.get("apiKey"));
        labels.put(c.participantId(), c.label());
        resolved.put(c.apiKey(), new Cached(Optional.of(c), clock.getAsLong() + ttlNanos));
        return c;
    }

    @Override
    public Optional<Credentials> resolve(String apiKeyOrToken) {
        if (apiKeyOrToken == null || apiKeyOrToken.isBlank()) {
            return Optional.empty();
        }
        String key = apiKeyOrToken.trim();
        if (key.equals(userKey)) {
            return Optional.of(new Credentials(Simulator.USER_PARTICIPANT_ID, Simulator.USER_LABEL, userKey));
        }
        long now = clock.getAsLong();
        Cached cached = resolved.get(key);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.credentials();
        }
        Optional<Credentials> found = isJwt(key) ? lookupSession(key) : lookupApiKey(key);
        found.ifPresent(c -> labels.put(c.participantId(), c.label()));
        resolved.put(key, new Cached(found, now + (found.isPresent() ? ttlNanos : ttlNanos / 10)));
        return found;
    }

    /** Never blocks: an unknown id answers {@code P<id>} and is looked up in the background for next time. */
    @Override
    public String label(long participantId) {
        if (participantId == Simulator.USER_PARTICIPANT_ID) {
            return Simulator.USER_LABEL;
        }
        if (participantId == Order.NO_PARTICIPANT) {
            return "anon";
        }
        String label = labels.get(participantId);
        if (label != null) {
            return label;
        }
        if (labelLookups.putIfAbsent(participantId, Boolean.TRUE) == null) {
            http.sendAsync(internal(HttpRequest.newBuilder(base.resolve("internal/participants/" + participantId)))
                    .GET().build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200) {
                            Object l = JsonReader.object(res.body()).get("label");
                            if (l instanceof String s) {
                                labels.put(participantId, s);
                            }
                        }
                    })
                    .whenComplete((ok, err) -> labelLookups.remove(participantId));
        }
        return "P" + participantId;
    }

    private Optional<Credentials> lookupApiKey(String key) {
        HttpResponse<String> res = send(internal(HttpRequest.newBuilder(base.resolve("internal/api-keys/verify")))
                .header("X-Api-Key", key).GET());
        if (res.statusCode() == 401 || res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() != 200) {
            throw unavailable("API key lookup failed with HTTP " + res.statusCode());
        }
        Map<String, Object> body = JsonReader.object(res.body());
        return Optional.of(new Credentials(longField(body, "participantId"), (String) body.get("label"), key));
    }

    @SuppressWarnings("unchecked")
    private Optional<Credentials> lookupSession(String token) {
        HttpResponse<String> res = send(HttpRequest.newBuilder(base.resolve("auth/me"))
                .header("Authorization", "Bearer " + token).GET());
        if (res.statusCode() == 401 || res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() != 200) {
            throw unavailable("session lookup failed with HTTP " + res.statusCode());
        }
        Map<String, Object> account = (Map<String, Object>) JsonReader.object(res.body()).get("account");
        if (account == null) {
            throw unavailable("session lookup returned no account");
        }
        return Optional.of(new Credentials(longField(account, "participantId"), (String) account.get("label"), token));
    }

    private HttpRequest.Builder internal(HttpRequest.Builder b) {
        return internalToken == null ? b : b.header(INTERNAL_TOKEN_HEADER, internalToken);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable("auth service unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        }
    }

    /** True for a JWT (three dot-separated parts), false for an {@code ob_} API key. */
    public static boolean isJwt(String s) {
        return s.chars().filter(c -> c == '.').count() == 2 && !s.startsWith("ob_");
    }

    private static long longField(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (!(v instanceof java.math.BigDecimal n)) {
            throw unavailable("auth service response is missing " + name);
        }
        return n.longValueExact();
    }

    private static ApiException unavailable(String message) {
        return new ApiException(503, "AUTH_UNAVAILABLE", message);
    }

}
