package orderbook.api;

import java.util.function.LongSupplier;

/** Classic token bucket: refills at {@code ratePerSec} up to {@code burst} tokens; each admitted message costs one. */
final class TokenBucket {

    private final double ratePerNano;
    private final double burst;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    TokenBucket(double ratePerSec, double burst, LongSupplier nanoClock) {
        if (!(ratePerSec > 0) || !(burst >= 1)) {
            throw new IllegalArgumentException("rate must be positive and burst at least 1");
        }
        this.ratePerNano = ratePerSec / 1e9;
        this.burst = burst;
        this.clock = nanoClock;
        this.tokens = burst;
        this.last = nanoClock.getAsLong();
    }

    synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        tokens = Math.min(burst, tokens + (now - last) * ratePerNano);
        last = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
