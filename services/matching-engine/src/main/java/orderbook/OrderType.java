package orderbook;

public enum OrderType {
    /** Matches at its limit price or better; a GTC remainder rests in the book. */
    LIMIT,
    /** Has no price: sweeps available liquidity at any price and never rests. */
    MARKET
}
