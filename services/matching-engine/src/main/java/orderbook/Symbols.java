package orderbook;

import java.util.List;

/**
 * The built-in listing, used when no reference-data service is configured (tests, the in-page build). Each hosted
 * symbol has its own matching engine, pipeline and journal (see {@code orderbook.exchange.ExchangeCore}); nothing is
 * shared between symbols except the order-id space.
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

    /** Listing-code shape shared with the reference-data table: an upper-case letter, then up to 9 of A-Z/0-9. */
    public static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9]{0,9}");

    /**
     * Returns {@code symbol} if it is a well-formed listing code (hosted symbols may come from the reference-data
     * service rather than {@link #ALL}). @throws IllegalArgumentException otherwise
     */
    public static String requireValid(String symbol) {
        if (symbol == null || !CODE.matcher(symbol).matches()) {
            throw new IllegalArgumentException("invalid symbol '" + symbol + "'");
        }
        return symbol;
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
