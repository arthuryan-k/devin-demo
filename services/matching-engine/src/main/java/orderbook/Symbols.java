package orderbook;

import java.util.List;

/**
 * The fixed set of tradable symbols. Each one has its own matching engine, pipeline and journal (see
 * {@code orderbook.exchange.ExchangeCore}); nothing is shared between symbols except the order-id space.
 */
public final class Symbols {

    public static final String ACME = "ACME";
    public static final String GLOBX = "GLOBX";
    public static final String INITD = "INITD";
    public static final String NVTA = "NVTA";

    /** Every listed symbol, in listing order. */
    public static final List<String> ALL = List.of(ACME, GLOBX, INITD, NVTA);

    /** Symbol used by constructors that predate the symbol dimension (single-instrument callers). */
    public static final String DEFAULT = ACME;

    private Symbols() {
    }

    public static boolean isListed(String symbol) {
        return symbol != null && ALL.contains(symbol);
    }

    /** Returns {@code symbol} if it is listed. @throws IllegalArgumentException otherwise */
    public static String requireListed(String symbol) {
        if (!isListed(symbol)) {
            throw new IllegalArgumentException("unknown symbol '" + symbol + "', listed: " + ALL);
        }
        return symbol;
    }
}
