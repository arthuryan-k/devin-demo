package orderbook.sim;

import java.util.List;
import java.util.Random;

import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;

/** Keeps a few resting orders and spends most turns cancelling or amending them (qty down or re-price). */
final class MaintenanceTrader implements Persona {

    static final int MIN_ORDERS = 2;
    static final int MAX_ORDERS = 5;

    private final double riskTolerance;

    MaintenanceTrader(double riskTolerance) {
        this.riskTolerance = riskTolerance;
    }

    @Override
    public Kind kind() {
        return Kind.MAINTENANCE_TRADER;
    }

    @Override
    public double riskTolerance() {
        return riskTolerance;
    }

    @Override
    public double activityWeight() {
        return 0.6;
    }

    @Override
    public void act(Context ctx) {
        Random random = ctx.random();
        long ref = ctx.referencePrice();
        List<Order> own = ctx.ownOrders();
        if (own.size() < MIN_ORDERS || (own.size() < MAX_ORDERS && random.nextDouble() < 0.25)) {
            Side side = Draw.side(random);
            long offset = 2 + Draw.peaked(random, 4, 3, 0, 15);
            long qty = Draw.peaked(random, 6, 3, 1, 15);
            ctx.placeLimit(side, side == Side.BUY ? ref - offset : ref + offset, qty, TimeInForce.GTC);
            return;
        }
        Order order = own.get(random.nextInt(own.size()));
        double u = random.nextDouble();
        if (u < 0.35) {
            ctx.cancel(order.id());
        } else if (u < 0.65 && order.qtyRemaining() > 1) {
            long newQty = 1 + (long) (random.nextDouble() * (order.qtyRemaining() - 1));
            ctx.amend(order.id(), null, newQty);
        } else {
            long offset = 1 + random.nextInt(6);
            ctx.amend(order.id(), order.side() == Side.BUY ? ref - offset : ref + offset, null);
        }
    }
}
