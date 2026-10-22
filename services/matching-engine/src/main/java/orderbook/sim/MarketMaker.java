package orderbook.sim;

import java.util.List;
import java.util.Random;

import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;

/**
 * Quotes small GTC limits on both sides of the reference price. Re-seeds a side of the book that has gone empty,
 * chases the reference by re-pricing (amending) stale quotes, and skews quote sizes against its inventory.
 *
 * <p>Inventory is just a signed counter of its own fills (buys minus sells); there is no position ledger.
 */
final class MarketMaker implements Persona {

    static final int MAX_QUOTES = 6;
    /** How far behind the inner edge of its spread a quote may sit before it counts as stale. */
    static final long MAX_QUOTE_DEPTH_TICKS = 20;
    /** Inventory at which size skew saturates. */
    static final double INVENTORY_SCALE = 50;
    static final double PULL_PROBABILITY = 0.3;

    private final double riskTolerance;
    private long inventory;

    MarketMaker(double riskTolerance) {
        this.riskTolerance = riskTolerance;
    }

    @Override
    public Kind kind() {
        return Kind.MARKET_MAKER;
    }

    @Override
    public double riskTolerance() {
        return riskTolerance;
    }

    @Override
    public double activityWeight() {
        return 1.0;
    }

    @Override
    public void onFill(Side side, long qty) {
        inventory += side == Side.BUY ? qty : -qty;
    }

    long inventory() {
        return inventory;
    }

    /**
     * Distance in ticks from the reference to the tightest quote. Cautious makers (low risk tolerance) quote wider
     * and widen more during a shock.
     */
    static long halfSpread(double riskTolerance, double shockIntensity) {
        double sensitivity = Simulator.sensitivity(riskTolerance);
        return Math.round(2 + 3 * sensitivity + 12 * shockIntensity * sensitivity);
    }

    /**
     * Skews a quote size against inventory: when long, bids shrink and asks grow (and vice versa). Cautious makers
     * skew harder.
     */
    static long skewedSize(long baseSize, Side side, long inventory, double riskTolerance) {
        double pressure = Math.max(-1, Math.min(1, inventory / INVENTORY_SCALE));
        double strength = 0.2 + 0.7 * (1 - riskTolerance);
        double factor = side == Side.BUY ? 1 - strength * pressure : 1 + strength * pressure;
        return Math.max(1, Math.round(baseSize * factor));
    }

    /** How far a quote is outside its acceptable band (0 if it is fine). */
    static long staleness(Side side, long price, long reference, long halfSpread) {
        long inner = side == Side.BUY ? reference - halfSpread : reference + halfSpread;
        long behind = side == Side.BUY ? inner - price : price - inner;
        if (behind < -1) {
            return -behind;
        }
        return Math.max(0, behind - MAX_QUOTE_DEPTH_TICKS);
    }

    static long quotePrice(Side side, long reference, long halfSpread, Random random) {
        long distance = (long) Math.floor(Math.abs(random.nextGaussian()) * 3);
        return side == Side.BUY ? reference - halfSpread - distance : reference + halfSpread + distance;
    }

    @Override
    public void act(Context ctx) {
        long ref = ctx.referencePrice();
        long half = halfSpread(riskTolerance, ctx.shockIntensity());
        for (Side side : Side.values()) {
            if (ctx.bestPrice(side).isEmpty()) {
                quote(ctx, side, ref, half);
                return;
            }
        }

        List<Order> own = ctx.ownOrders();
        Order stale = null;
        long worst = 0;
        for (Order order : own) {
            long off = staleness(order.side(), order.price(), ref, half);
            if (off > worst) {
                worst = off;
                stale = order;
            }
        }
        if (stale != null) {
            ctx.amend(stale.id(), quotePrice(stale.side(), ref, half, ctx.random()), null);
            return;
        }

        if (own.size() < MAX_QUOTES) {
            long bids = own.stream().filter(o -> o.side() == Side.BUY).count();
            long asks = own.size() - bids;
            Side side = bids < asks ? Side.BUY : asks < bids ? Side.SELL : Draw.side(ctx.random());
            quote(ctx, side, ref, half);
            return;
        }

        if (ctx.random().nextDouble() < PULL_PROBABILITY) {
            Order furthest = own.get(0);
            for (Order order : own) {
                if (Math.abs(order.price() - ref) > Math.abs(furthest.price() - ref)) {
                    furthest = order;
                }
            }
            ctx.cancel(furthest.id());
        }
    }

    private void quote(Context ctx, Side side, long ref, long half) {
        long base = Draw.peaked(ctx.random(), 8, 3, 1, 25);
        ctx.placeLimit(side, quotePrice(side, ref, half, ctx.random()), skewedSize(base, side, inventory, riskTolerance),
                TimeInForce.GTC);
    }
}
