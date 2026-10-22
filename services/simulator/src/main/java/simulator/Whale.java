package simulator;

import java.util.Random;


/**
 * Rarely acts; when it does, it sweeps most of one side of the book with a market order. Its turns come with a
 * volatility shock in the sweep's direction, started by the simulator clock.
 */
final class Whale implements Persona {

    static final long MIN_SWEEP_QTY = 50;

    private final double riskTolerance;

    Whale(double riskTolerance) {
        this.riskTolerance = riskTolerance;
    }

    @Override
    public Kind kind() {
        return Kind.WHALE;
    }

    @Override
    public double riskTolerance() {
        return riskTolerance;
    }

    @Override
    public double activityWeight() {
        return 0.015;
    }

    @Override
    public void act(Context ctx) {
        Random random = ctx.random();
        Side side = ctx.shockDirection() > 0 ? Side.BUY : ctx.shockDirection() < 0 ? Side.SELL : Draw.side(random);
        long depth = 0;
        for (Level level : ctx.depth(side.opposite(), 10)) {
            depth += level.totalQty();
        }
        if (depth == 0) {
            return;
        }
        long qty = Math.max(MIN_SWEEP_QTY, Math.round(depth * (0.6 + 0.4 * random.nextDouble())));
        ctx.placeMarket(side, qty);
    }
}
