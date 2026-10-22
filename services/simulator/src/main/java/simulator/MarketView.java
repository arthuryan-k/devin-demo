package simulator;

import java.util.List;

/**
 * The public market data a simulated participant sees: a per-symbol L2 book replica, plus the trade prints that let
 * a participant notice its own resting orders filling. Implementations must be thread-safe.
 */
public interface MarketView {

    /** A trade print: the resting (maker) and incoming (taker) order ids, price in ticks and quantity. */
    record Trade(String symbol, long makerOrderId, long takerOrderId, long price, long qty) {
    }

    @FunctionalInterface
    interface TradeListener {
        void onTrade(Trade trade);
    }

    /** Up to {@code levels} aggregated levels on {@code side} of {@code symbol}, best first. */
    List<Level> depth(String symbol, Side side, int levels);

    void addTradeListener(TradeListener listener);
}
