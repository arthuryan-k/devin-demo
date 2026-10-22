package simulator;

import java.util.List;
import java.util.OptionalLong;
import java.util.Random;

/**
 * A simulated trading strategy. Each simulated participant owns one instance, so implementations may keep
 * per-participant state. Personas only act through {@link Context}, which turns every action into an authenticated
 * call on the public gateway API and only lets a persona touch its own orders.
 */
public interface Persona {

    enum Kind {
        MARKET_MAKER("MM", 0.58),
        AGGRESSIVE_TAKER("TAKER", 0.19),
        MAINTENANCE_TRADER("MAINT", 0.19),
        WHALE("WHALE", 0.04);

        private final String labelPrefix;
        private final double spawnWeight;

        Kind(String labelPrefix, double spawnWeight) {
            this.labelPrefix = labelPrefix;
            this.spawnWeight = spawnWeight;
        }

        public String labelPrefix() {
            return labelPrefix;
        }

        /** Relative probability that a newly spawned participant has this persona. */
        public double spawnWeight() {
            return spawnWeight;
        }
    }

    Kind kind();

    /** Hidden trait in [0, 1] drawn at spawn; lower means more cautious (wider quotes, more likely to leave). */
    double riskTolerance();

    /** Relative chance of being picked to act on a simulation step. */
    double activityWeight();

    void act(Context ctx);

    /** Called when one of this participant's orders trades. */
    default void onFill(Side side, long qty) {
    }

    static Persona create(Kind kind, double riskTolerance) {
        return switch (kind) {
            case MARKET_MAKER -> new MarketMaker(riskTolerance);
            case AGGRESSIVE_TAKER -> new AggressiveTaker(riskTolerance);
            case MAINTENANCE_TRADER -> new MaintenanceTrader(riskTolerance);
            case WHALE -> new Whale(riskTolerance);
        };
    }

    /** What a persona can see and do on its turn. Prices are in ticks. */
    interface Context {
        Random random();

        long referencePrice();

        /** Recent change of the reference price (exponentially decayed); its sign is the recent trend. */
        double drift();

        /** 1 right after a volatility shock, decaying linearly to 0 over the cooldown. */
        double shockIntensity();

        /** Best price on {@code side} in the market-data snapshot this turn was dealt. */
        OptionalLong bestPrice(Side side);

        /** Up to {@code levels} aggregated price levels on {@code side} (at most 10), best first. */
        List<Level> depth(Side side, int levels);

        /** This participant's resting orders, in priority order per side (bids first). */
        List<OwnOrder> ownOrders();

        void placeLimit(Side side, long price, long qty, TimeInForce timeInForce);

        void placeMarket(Side side, long qty);

        void cancel(long orderId);

        void amend(long orderId, Long newPrice, Long newQty);

        /**
         * Direction (+1 up, -1 down) of the volatility shock the simulator's clock started with this turn, or 0. The
         * shock itself (reference jump) is decided by the seeded clock, never by market state, so the arrival process
         * stays reproducible.
         */
        int shockDirection();
    }
}
