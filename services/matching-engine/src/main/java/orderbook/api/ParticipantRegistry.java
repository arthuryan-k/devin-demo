package orderbook.api;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import orderbook.Order;
import orderbook.sim.ExchangeClient.Credentials;
import orderbook.sim.Simulator;

/**
 * In-memory API key registry. {@link #register} hands out a participant id (from 1001) and a random key; the browser
 * session's {@link #userKey() reserved key} maps to {@link Simulator#USER_PARTICIPANT_ID} ("YOU"), which can never be
 * registered. Thread-safe and never touches the simulator loop.
 */
public final class ParticipantRegistry {

    public static final long FIRST_PARTICIPANT_ID = 1001;
    private static final int KEY_BYTES = 24;
    private static final int MAX_NAME_LENGTH = 24;

    private final Random random;
    private final AtomicLong nextId = new AtomicLong(FIRST_PARTICIPANT_ID);
    private final Map<String, Credentials> byKey = new ConcurrentHashMap<>();
    private final Map<Long, String> labels = new ConcurrentHashMap<>();
    private final String userKey;

    /** {@code random} generates API keys; use a {@code SecureRandom} where keys must be unguessable. */
    public ParticipantRegistry(Random random) {
        this.random = random;
        this.userKey = newKey();
        byKey.put(userKey, new Credentials(Simulator.USER_PARTICIPANT_ID, Simulator.USER_LABEL, userKey));
    }

    /** The reserved key of the browser session ("YOU"). */
    public String userKey() {
        return userKey;
    }

    /** Registers a new participant labelled {@code <name>-<id>} (name defaults to {@code P}). */
    public Credentials register(String name) {
        String prefix = sanitize(name);
        long id = nextId.getAndIncrement();
        String label = prefix + "-" + id;
        String key = newKey();
        Credentials credentials = new Credentials(id, label, key);
        labels.put(id, label);
        byKey.put(key, credentials);
        return credentials;
    }

    public Optional<Credentials> resolve(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? Optional.empty() : Optional.ofNullable(byKey.get(apiKey.trim()));
    }

    /** Display label: {@code YOU}, a registered label, {@code anon}, or {@code P<id>}. */
    public String label(long participantId) {
        if (participantId == Simulator.USER_PARTICIPANT_ID) {
            return Simulator.USER_LABEL;
        }
        if (participantId == Order.NO_PARTICIPANT) {
            return "anon";
        }
        String label = labels.get(participantId);
        return label != null ? label : "P" + participantId;
    }

    public int size() {
        return labels.size();
    }

    static String sanitize(String name) {
        StringBuilder out = new StringBuilder();
        if (name != null) {
            for (char c : name.trim().toCharArray()) {
                if (out.length() < MAX_NAME_LENGTH && (Character.isLetterOrDigit(c) || c == '_' || c == '.')) {
                    out.append(c);
                }
            }
        }
        String s = out.toString();
        if (s.isEmpty() || s.equalsIgnoreCase(Simulator.USER_LABEL) || s.equalsIgnoreCase("anon")) {
            return "P";
        }
        return s;
    }

    private String newKey() {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder("ob_");
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
