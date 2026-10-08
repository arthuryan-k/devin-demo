package orderbook;

import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/** Random command generators shared by the property tests. IDs and prices are kept small so commands collide. */
public final class CommandArbitraries {

    private CommandArbitraries() {
    }

    public static Arbitrary<Long> ids() {
        return Arbitraries.longs().between(1, 80);
    }

    /** Mostly valid prices around 100, occasionally non-positive. */
    public static Arbitrary<Long> prices() {
        return Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(95, 105)),
                Tuple.of(1, Arbitraries.longs().between(-3, 0)));
    }

    /** Mostly valid quantities, occasionally non-positive. */
    public static Arbitrary<Long> qtys() {
        return Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(1, 100)),
                Tuple.of(1, Arbitraries.longs().between(-3, 0)));
    }

    /** Anonymous GTC limit orders only (the Phase 1 command set). */
    public static Arbitrary<Command> gtcLimitPlaces() {
        return Combinators.combine(ids(), Arbitraries.of(Side.class), prices(), qtys())
                .as((id, side, price, qty) -> new Command.Place(new Order(id, side, price, qty)));
    }

    /** Limit and market orders with every time-in-force and a few participants (0 = anonymous). */
    public static Arbitrary<Command> places() {
        Arbitrary<OrderType> types = Arbitraries.frequencyOf(Tuple.of(5, Arbitraries.just(OrderType.LIMIT)),
                Tuple.of(1, Arbitraries.just(OrderType.MARKET)));
        Arbitrary<TimeInForce> tifs = Arbitraries.frequencyOf(Tuple.of(6, Arbitraries.just(TimeInForce.GTC)),
                Tuple.of(1, Arbitraries.just(TimeInForce.IOC)), Tuple.of(1, Arbitraries.just(TimeInForce.FOK)));
        return Combinators.combine(ids(), Arbitraries.longs().between(0, 3), Arbitraries.of(Side.class), types, tifs,
                        prices(), qtys())
                .as((id, participant, side, type, tif, price, qty) ->
                        new Command.Place(new Order(id, participant, side, type, tif, price, qty)));
    }

    public static Arbitrary<Command> cancels() {
        return ids().map(Command.Cancel::new);
    }

    public static Arbitrary<Command> amends() {
        Arbitrary<Long> price = prices().injectNull(0.4);
        Arbitrary<Long> qty = qtys().injectNull(0.4);
        return Combinators.combine(ids(), price, qty).as(Command.Amend::new);
    }

    public static Arbitrary<List<Command>> allCommands(int maxSize) {
        return Arbitraries.frequencyOf(Tuple.of(6, places()), Tuple.of(1, cancels()), Tuple.of(2, amends()))
                .list().ofMaxSize(maxSize);
    }
}
