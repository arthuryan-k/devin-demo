package simulator;

import java.util.Random;

/**
 * The simulator's seeded logical clock. It draws the Poisson arrival process (exponential inter-arrival delays with
 * mean {@code 1 / rate}) and which symbol each arrival goes to. Simulated time advances by the drawn delays, never
 * by the wall clock, so for a given seed (and rate schedule) the arrival times are identical run to run; the wall
 * clock only paces when arrivals are released.
 */
public final class SimClock {

    private final Random random;
    private double time;
    private long ticks;

    public SimClock(long seed) {
        this.random = new Random(seed);
    }

    /** Draws the next exponential inter-arrival delay in simulated seconds. */
    public double nextDelay(double rate) {
        if (!(rate > 0)) {
            throw new IllegalArgumentException("rate must be positive");
        }
        return -Math.log(1 - random.nextDouble()) / rate;
    }

    public void advance(double dt) {
        time += dt;
        ticks++;
    }

    /** Uniform choice in {@code [0, n)}, e.g. which symbol the next arrival trades. */
    public int pick(int n) {
        return random.nextInt(n);
    }

    /** Simulated seconds since the start. */
    public double time() {
        return time;
    }

    /** Arrivals released so far. */
    public long arrivals() {
        return ticks;
    }
}
