package orderbook.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ReferencePriceTest {

    /** One-second log returns over a long run. */
    private static double[] returns(long seed, int seconds) {
        ReferencePrice ref = new ReferencePrice(100_00);
        Random random = new Random(seed);
        double[] r = new double[seconds];
        double last = Math.log(ref.ticks());
        for (int i = 0; i < seconds; i++) {
            for (int k = 0; k < 5; k++) {
                ref.advance(0.2, random);
            }
            double now = Math.log(ref.ticks());
            r[i] = now - last;
            last = now;
        }
        return r;
    }

    @Test
    void returnsHaveFatTails() {
        double[] r = returns(1, 20_000);
        double mean = Arrays.stream(r).average().orElseThrow();
        double m2 = Arrays.stream(r).map(x -> Math.pow(x - mean, 2)).average().orElseThrow();
        double m4 = Arrays.stream(r).map(x -> Math.pow(x - mean, 4)).average().orElseThrow();
        double kurtosis = m4 / (m2 * m2);
        System.out.printf("kurtosis=%.2f sd/sec=%.5f%n", kurtosis, Math.sqrt(m2));
        assertTrue(kurtosis > 5, "kurtosis " + kurtosis);
    }

    @Test
    void volatilityClustersIntoCalmAndTurbulentStretches() {
        double[] r = returns(2, 20_000);
        int window = 30;
        double[] vols = new double[r.length / window];
        for (int w = 0; w < vols.length; w++) {
            double s = 0;
            for (int i = w * window; i < (w + 1) * window; i++) {
                s += r[i] * r[i];
            }
            vols[w] = Math.sqrt(s / window);
        }
        Arrays.sort(vols);
        double ratio = vols[vols.length * 9 / 10] / vols[vols.length / 10];
        System.out.printf("p90/p10 window vol=%.2f%n", ratio);
        assertTrue(ratio > 2.5, "p90/p10 " + ratio);
    }

    @Test
    void jumpMovesInItsDirectionAndPartlyRetraces() {
        for (int dir : new int[] {1, -1}) {
            ReferencePrice ref = new ReferencePrice(100_00);
            Random random = new Random(3);
            ref.jump(dir, random);
            long moved = ref.ticks() - 100_00;
            assertEquals(dir, Long.signum(moved));
            assertTrue(Math.abs(moved) >= 100_00 * ReferencePrice.MIN_JUMP - 1);
            assertTrue(Math.abs(moved) <= 100_00 * ReferencePrice.MAX_JUMP + 1);
            assertEquals(1, ref.shockIntensity(), 1e-9);
            assertTrue(ref.volatility() > ReferencePrice.BASE_VOLATILITY_PER_SQRT_SEC);
        }
    }

    @Test
    void neverFallsBelowTheFloor() {
        ReferencePrice ref = new ReferencePrice(150);
        Random random = new Random(4);
        for (int i = 0; i < 2_000; i++) {
            ref.jump(-1, random);
            ref.advance(0.5, random);
            assertTrue(ref.ticks() >= ReferencePrice.MIN_PRICE_TICKS);
        }
    }
}
