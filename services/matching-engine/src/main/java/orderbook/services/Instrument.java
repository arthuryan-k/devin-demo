package orderbook.services;

import java.math.BigDecimal;

/** One listing from the reference-data service ({@code GET /symbols}). Decimals stay exact. */
public record Instrument(String symbol, String name, String currency, BigDecimal tickSize, int lotSize,
        BigDecimal initialCashGrant) {
}
