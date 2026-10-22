package simulator;

/**
 * One event of the seeded arrival process: a participant entering or leaving a symbol's population, or being dealt a
 * turn. Everything here is a function of the seed alone (never of exchange replies or market data), so two runs with
 * the same seed produce the same sequence of arrivals.
 */
public record Arrival(long seq, double time, String symbol, Kind kind, String participant, Persona.Kind persona,
        long referenceTicks, int shockDirection) {

    public enum Kind {
        ENTER, EXIT, TURN
    }
}
