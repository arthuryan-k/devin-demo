package orderbook.sim;

import java.util.List;
import java.util.Random;

import orderbook.OrderBook.Level;
import orderbook.Side;
import orderbook.TimeInForce;

/**
 * Takes liquidity with market and IOC limit orders, occasionally sweeping several levels. Direction leans toward
 * the recent drift of the reference price (informed flow).
 */
final class AggressiveTaker implements Persona {

    /** Probability of trading in the direction of the recent drift. */
    static final double INFORMED_BIAS = 0.55;

    private final double riskTolerance;

    AggressiveTaker(double riskTolerance) {
        this.riskTolerance = riskTolerance;
    }

    @Override
    public Kind kind() {
        return Kind.AGGRESSIVE_TAKER;
    }

    @Override
    public double riskTolerance() {
        return riskTolerance;
    }

    @Override
    public double activityWeight() {
        return 0.8;
    }

    static Side chooseSide(double drift, Random random) {
        double pBuy = drift > 0 ? INFORMED_BIAS : drift < 0 ? 1 - INFORMED_BIAS : 0.5;
        return random.nextDouble() < pBuy ? Side.BUY : Side.SELL;
    }

    @Override
    public void act(Context ctx) {
        Random random = ctx.random();
        Side side = chooseSide(ctx.drift(), random);
        List<Level> levels = ctx.depth(side.opposite(), 4);
        if (levels.isEmpty()) {
            return;
        }
        if (random.nextDouble() < 0.04 + 0.08 * riskTolerance) {
            int sweepLevels = Math.min(levels.size(), 2 + random.nextInt(3));
            long qty = 0;
            for (int i = 0; i < sweepLevels; i++) {
                qty += levels.get(i).totalQty();
            }
            ctx.placeMarket(side, qty);
            return;
        }
        long qty = Draw.peaked(random, 5 + 5 * riskTolerance, 3, 1, 20);
        if (random.nextDouble() < 0.6) {
            ctx.placeMarket(side, qty);
        } else {
            long best = levels.get(0).price();
            long slip = random.nextInt(3);
            ctx.placeLimit(side, side == Side.BUY ? best + slip : best - slip, qty, TimeInForce.IOC);
        }
    }
}
