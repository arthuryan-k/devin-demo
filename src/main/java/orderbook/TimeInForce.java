package orderbook;

public enum TimeInForce {
    /** Good-till-cancelled: a limit order's unfilled remainder rests in the book. */
    GTC,
    /** Immediate-or-cancel: match what crosses now, cancel the remainder. */
    IOC,
    /** Fill-or-kill: fill the whole quantity immediately or reject with no trades and no book change. */
    FOK
}
