package orderbook.sim;

import java.util.Random;

/**
 * The hidden "fair value" the personas trade around, in ticks. A geometric random walk with small steps, plus rare
 * large jumps. A jump (or a whale sweep) starts a volatility shock: for {@link #SHOCK_COOLDOWN_SEC} simulated
 * seconds volatility is raised, and {@link #shockIntensity()} decays linearly from 1 to 0.
 */
final class ReferencePrice {

    static final double VOLATILITY_PER_SQRT_SEC = 0.0003;
    static final double SHOCK_VOLATILITY_MULTIPLIER = 3;
    static final double JUMP_RATE_PER_SEC = 1.0 / 120;
    static final double MIN_JUMP = 0.008;
    static final double MAX_JUMP = 0.025;
    static final double SHOCK_COOLDOWN_SEC = 12;
    static final double DRIFT_HALF_LIFE_SEC = 5;
    static final double MIN_PRICE_TICKS = 100;

    private double price;
    private double drift;
    private double time;
    private double shockUntil = Double.NEGATIVE_INFINITY;

    ReferencePrice(long startTicks) {
        this.price = startTicks;
    }

    /** Advances simulated time by {@code dt} seconds. */
    void advance(double dt, Random random) {
        time += dt;
        drift *= Math.pow(0.5, dt / DRIFT_HALF_LIFE_SEC);
        double vol = VOLATILITY_PER_SQRT_SEC * (1 + SHOCK_VOLATILITY_MULTIPLIER * shockIntensity());
        moveTo(price * Math.exp(vol * Math.sqrt(dt) * random.nextGaussian()));
        if (random.nextDouble() < Simulator.probability(JUMP_RATE_PER_SEC, dt)) {
            jump(random.nextBoolean() ? 1 : -1, random);
        }
    }

    /** A large move in {@code direction} (+1 up, -1 down) that also starts a shock. */
    void jump(int direction, Random random) {
        double size = MIN_JUMP + random.nextDouble() * (MAX_JUMP - MIN_JUMP);
        moveTo(price * (1 + Math.signum(direction) * size));
        startShock();
    }

    void startShock() {
        shockUntil = time + SHOCK_COOLDOWN_SEC;
    }

    void setTicks(long ticks) {
        moveTo(ticks);
    }

    private void moveTo(double newPrice) {
        double bounded = Math.max(MIN_PRICE_TICKS, newPrice);
        drift += bounded - price;
        price = bounded;
    }

    long ticks() {
        return Math.round(price);
    }

    double drift() {
        return drift;
    }

    double time() {
        return time;
    }

    double shockIntensity() {
        return time < shockUntil ? (shockUntil - time) / SHOCK_COOLDOWN_SEC : 0;
    }
}
