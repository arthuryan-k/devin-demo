package orderbook.sim;

import java.util.Random;

import orderbook.Side;

/** Small sampling helpers shared by the personas. */
final class Draw {

    private Draw() {
    }

    /** A normal draw rounded and clamped to {@code [min, max]}: peaked at {@code mean}. */
    static long peaked(Random random, double mean, double sd, long min, long max) {
        long value = Math.round(mean + random.nextGaussian() * sd);
        return Math.max(min, Math.min(max, value));
    }

    static Side side(Random random) {
        return random.nextBoolean() ? Side.BUY : Side.SELL;
    }
}
