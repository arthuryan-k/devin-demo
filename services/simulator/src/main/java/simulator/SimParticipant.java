package simulator;

import java.util.Random;

/**
 * One simulated trader: a real account (its own credentials), a persona, a private random stream for the persona's
 * decisions, its own client loop, and the orders it has resting. Trades a single home symbol.
 */
final class SimParticipant {

    private final String label;
    private final String symbol;
    private final Persona persona;
    private final double spawnTime;
    private final Random random;
    private final ClientLoop loop;
    private final OrderTracker orders = new OrderTracker();
    private volatile ExchangeClient.Credentials credentials;

    SimParticipant(String label, String symbol, Persona persona, double spawnTime, Random random, ClientLoop loop) {
        this.label = label;
        this.symbol = symbol;
        this.persona = persona;
        this.spawnTime = spawnTime;
        this.random = random;
        this.loop = loop;
    }

    /** Simulator-local label, e.g. {@code MM-ACME-3}; stable for a given seed. */
    String label() {
        return label;
    }

    String symbol() {
        return symbol;
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

    OrderTracker orders() {
        return orders;
    }

    /** The account it signed up for; null until registration has completed on its client loop. */
    ExchangeClient.Credentials credentials() {
        return credentials;
    }

    void setCredentials(ExchangeClient.Credentials credentials) {
        this.credentials = credentials;
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
