package orderbook.sim;

import java.util.Random;

/**
 * One simulated trader: an API client with its own credentials, persona, random stream and {@link ClientLoop}.
 * Never the user's participant.
 */
final class Participant {

    private final long id;
    private final String label;
    private final String apiKey;
    private final Persona persona;
    private final double spawnTime;
    private final Random random;
    private final ClientLoop loop;

    Participant(long id, String label, String apiKey, Persona persona, double spawnTime, Random random,
            ClientLoop loop) {
        this.id = id;
        this.label = label;
        this.apiKey = apiKey;
        this.persona = persona;
        this.spawnTime = spawnTime;
        this.random = random;
        this.loop = loop;
    }

    long id() {
        return id;
    }

    String label() {
        return label;
    }

    String apiKey() {
        return apiKey;
    }

    Persona persona() {
        return persona;
    }

    Random random() {
        return random;
    }

    ClientLoop loop() {
        return loop;
    }

    /** Simulated time of entry; {@code -Infinity} for the initial population, which starts fully active. */
    double spawnTime() {
        return spawnTime;
    }

    @Override
    public String toString() {
        return label;
    }
}
