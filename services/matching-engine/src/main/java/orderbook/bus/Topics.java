package orderbook.bus;

/**
 * Topic names on the event bus. Every topic has exactly one partition, keyed by symbol, so each symbol's commands,
 * events and market data are each totally ordered.
 */
public final class Topics {

    private Topics() {
    }

    /** Inbound Place/Cancel/Amend for one symbol; the matching engine is its only consumer. */
    public static String commands(String symbol) {
        return "commands." + symbol;
    }

    /** One record per sequenced command: the engine's events for it, stamped with the global {@code seq}. */
    public static String events(String symbol) {
        return "events." + symbol;
    }

    /** L2 market data: a snapshot at startup and periodically, otherwise one delta per {@code seq}. */
    public static String marketData(String symbol) {
        return "marketdata." + symbol;
    }

    /** Command records that could not be decoded or validated, with the reason in the {@code error} header. */
    public static String deadLetter(String symbol) {
        return "commands." + symbol + ".dlq";
    }
}
