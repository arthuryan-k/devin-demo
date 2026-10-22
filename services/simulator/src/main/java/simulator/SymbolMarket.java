package simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Per-symbol simulation state: the hidden reference price (drift, trend, volatility shocks), the population of
 * participants trading this symbol, and the random stream that drives both. Confined to the simulator loop.
 */
final class SymbolMarket {

    final String symbol;
    final long startTicks;
    final List<SimParticipant> participants = new ArrayList<>();
    Random random;
    ReferencePrice reference;
    int nextSlot;

    SymbolMarket(String symbol, long startTicks, long seed) {
        this.symbol = symbol;
        this.startTicks = startTicks;
        reset(seed);
    }

    void reset(long seed) {
        // Independent, seed-derived stream per symbol: adding a symbol does not perturb the others' processes.
        random = new Random(seed * 1_000_003L + symbol.hashCode());
        reference = new ReferencePrice(startTicks);
        participants.clear();
        nextSlot = 0;
    }

    boolean hasMaker() {
        for (SimParticipant p : participants) {
            if (p.persona().kind() == Persona.Kind.MARKET_MAKER) {
                return true;
            }
        }
        return false;
    }
}
