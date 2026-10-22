package orderbook.sim;

import java.util.Random;

/**
 * The hidden "fair value" the personas trade around, in ticks. Its log price moves by:
 * <ul>
 * <li>fat-tailed noise (unit-variance Student-t) whose volatility is itself random: log-volatility mean-reverts
 * with a {@link #LOG_VOL_HALF_LIFE_SEC} half-life, so calm and turbulent stretches cluster;</li>
 * <li>a hidden trend that mean-reverts with a {@link #TREND_HALF_LIFE_SEC} half-life, so prices run rather than
 * just jitter;</li>
 * <li>Poisson jumps with exponential sizes, biased toward the trend; part of each jump is an overshoot that retraces
 * with an {@link #OVERSHOOT_HALF_LIFE_SEC} half-life.</li>
 * </ul>
 * A jump (or a whale sweep) also starts a volatility shock: for {@link #SHOCK_COOLDOWN_SEC} simulated seconds
 * volatility is raised, and {@link #shockIntensity()} decays linearly from 1 to 0.
 */
final class ReferencePrice {

    static final double BASE_VOLATILITY_PER_SQRT_SEC = 0.0007;
    static final double LOG_VOL_HALF_LIFE_SEC = 30;
    static final double LOG_VOL_NOISE_PER_SQRT_SEC = 0.12;
    static final double MIN_LOG_VOL = -1.2;
    static final double MAX_LOG_VOL = 1.8;
    static final double SHOCK_LOG_VOL_KICK = 0.4;
    static final double SHOCK_VOLATILITY_MULTIPLIER = 2;
    static final double STUDENT_DEGREES_OF_FREEDOM = 4;
    static final double TREND_HALF_LIFE_SEC = 20;
    static final double TREND_NOISE_PER_SQRT_SEC = 0.00005;
    /** Rough stationary standard deviation of the trend, in log price per second. */
    static final double TREND_SCALE = 0.0002;
    static final double JUMP_RATE_PER_SEC = 1.0 / 60;
    static final double MIN_JUMP = 0.0015;
    static final double MEAN_EXTRA_JUMP = 0.003;
    static final double MAX_JUMP = 0.03;
    static final double JUMP_OVERSHOOT = 0.35;
    static final double OVERSHOOT_HALF_LIFE_SEC = 10;
    static final double SHOCK_COOLDOWN_SEC = 12;
    static final double DRIFT_HALF_LIFE_SEC = 5;
    static final double MIN_PRICE_TICKS = 100;

    private double logFair;
    private double overshoot;
    private double logVol;
    private double trend;
    private double drift;
    private double time;
    private double shockUntil = Double.NEGATIVE_INFINITY;

    ReferencePrice(long startTicks) {
        this.logFair = Math.log(Math.max(MIN_PRICE_TICKS, startTicks));
    }

    /** Advances simulated time by {@code dt} seconds. */
    void advance(double dt, Random random) {
        double before = price();
        time += dt;
        drift *= decay(dt, DRIFT_HALF_LIFE_SEC);
        double sqrtDt = Math.sqrt(dt);
        logVol = clamp(logVol * decay(dt, LOG_VOL_HALF_LIFE_SEC)
                + LOG_VOL_NOISE_PER_SQRT_SEC * sqrtDt * random.nextGaussian(), MIN_LOG_VOL, MAX_LOG_VOL);
        trend = trend * decay(dt, TREND_HALF_LIFE_SEC) + TREND_NOISE_PER_SQRT_SEC * sqrtDt * random.nextGaussian();
        overshoot *= decay(dt, OVERSHOOT_HALF_LIFE_SEC);
        logFair += trend * dt + volatility() * sqrtDt * studentT(random);
        settle(before);
        if (random.nextDouble() < Simulator.probability(JUMP_RATE_PER_SEC, dt)) {
            double pUp = clamp(0.5 + 0.25 * trend / TREND_SCALE, 0.2, 0.8);
            jump(random.nextDouble() < pUp ? 1 : -1, random);
        }
    }

    /** A large move in {@code direction} (+1 up, -1 down) that partly overshoots, adds momentum and starts a shock. */
    void jump(int direction, Random random) {
        double before = price();
        double sign = Math.signum(direction);
        double size = Math.min(MAX_JUMP, MIN_JUMP - MEAN_EXTRA_JUMP * Math.log(1 - random.nextDouble()));
        logFair += sign * size * (1 - JUMP_OVERSHOOT);
        overshoot += sign * size * JUMP_OVERSHOOT;
        trend += sign * TREND_SCALE;
        logVol = Math.min(MAX_LOG_VOL, logVol + SHOCK_LOG_VOL_KICK);
        settle(before);
        startShock();
    }

    void startShock() {
        shockUntil = time + SHOCK_COOLDOWN_SEC;
    }

    void setTicks(long ticks) {
        double before = price();
        logFair = Math.log(Math.max(MIN_PRICE_TICKS, ticks));
        overshoot = 0;
        settle(before);
    }

    /** Current diffusion volatility per sqrt(second), including any shock. */
    double volatility() {
        return BASE_VOLATILITY_PER_SQRT_SEC * Math.exp(logVol) * (1 + SHOCK_VOLATILITY_MULTIPLIER * shockIntensity());
    }

    private void settle(double before) {
        logFair = Math.max(Math.log(MIN_PRICE_TICKS), logFair);
        drift += price() - before;
    }

    private double price() {
        return Math.max(MIN_PRICE_TICKS, Math.exp(logFair + overshoot));
    }

    /** Student-t draw scaled to unit variance: fat tails without changing the typical move size. */
    private static double studentT(Random random) {
        double chi2 = 0;
        for (int i = 0; i < STUDENT_DEGREES_OF_FREEDOM; i++) {
            double g = random.nextGaussian();
            chi2 += g * g;
        }
        double nu = STUDENT_DEGREES_OF_FREEDOM;
        return random.nextGaussian() / Math.sqrt(chi2 / nu) * Math.sqrt((nu - 2) / nu);
    }

    private static double decay(double dt, double halfLife) {
        return Math.pow(0.5, dt / halfLife);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    long ticks() {
        return Math.round(price());
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
