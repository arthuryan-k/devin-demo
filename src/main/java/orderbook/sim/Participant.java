package orderbook.sim;

/** One simulated trader: a participant ID (never the user's) plus its persona. */
final class Participant {

    private final long id;
    private final String label;
    private final Persona persona;
    private final double spawnTime;

    Participant(long id, String label, Persona persona, double spawnTime) {
        this.id = id;
        this.label = label;
        this.persona = persona;
        this.spawnTime = spawnTime;
    }

    long id() {
        return id;
    }

    String label() {
        return label;
    }

    Persona persona() {
        return persona;
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
