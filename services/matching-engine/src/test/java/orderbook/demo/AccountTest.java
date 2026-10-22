package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import orderbook.Command;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;

class AccountTest {

    private static final long YOU = 1;
    private static final long OTHER = 2;

    private final MatchingEngine engine = new MatchingEngine();
    private long nextId = 1;

    /** Mirrors DemoServer: admit, then engine, then settle. */
    private Optional<Account.Rejection> user(Account account, Command command) {
        Optional<Account.Rejection> rejection = account.admit(command, engine.book());
        if (rejection.isEmpty()) {
            account.onEvents(engine.process(command));
        }
        return rejection;
    }

    private void other(Account account, Command command) {
        account.onEvents(engine.process(command));
    }

    private Command.Place limit(long participant, Side side, long price, long qty) {
        return new Command.Place(Order.limit(nextId++, participant, side, price, qty, TimeInForce.GTC));
    }

    private Command.Place market(long participant, Side side, long qty) {
        return new Command.Place(Order.market(nextId++, participant, side, qty, TimeInForce.IOC));
    }

    @Test
    void initialCashCoversAtLeastOneShareAtTheStartingReference() {
        for (long seed = 0; seed < 200; seed++) {
            Account a = Account.withRandomCash(YOU, new Random(seed), 100_00);
            assertTrue(a.cash() >= 2 * 100_00, "seed " + seed);
            assertEquals(0, a.sharesOwned());
        }
        Account expensive = Account.withRandomCash(YOU, new Random(1), 50_000_00);
        assertEquals(100_000_00, expensive.cash());
    }

    @Test
    void limitBuyLocksCashUntilCancelled() {
        Account a = new Account(YOU, 1_000_00, 0);
        Command.Place buy = limit(YOU, Side.BUY, 10_00, 30);
        assertTrue(user(a, buy).isEmpty());
        assertEquals(300_00, a.reservedCash());
        assertEquals(700_00, a.availableCash());
        assertEquals(1_000_00, a.cash());

        user(a, new Command.Cancel(buy.order().id()));
        assertEquals(0, a.reservedCash());
        assertEquals(1_000_00, a.availableCash());
        assertFalse(a.owns(buy.order().id()));
    }

    @Test
    void insufficientFundsIsRejectedBeforeTheEngine() {
        Account a = new Account(YOU, 100_00, 0);
        Command.Place buy = limit(YOU, Side.BUY, 10_00, 11);
        Optional<Account.Rejection> r = user(a, buy);
        assertEquals(Account.RejectReason.INSUFFICIENT_FUNDS, r.orElseThrow().reason());
        assertTrue(engine.book().find(buy.order().id()).isEmpty());
        assertEquals(0, a.reservedCash());

        assertTrue(user(a, limit(YOU, Side.BUY, 10_00, 10)).isEmpty());
        assertEquals(Account.RejectReason.INSUFFICIENT_FUNDS,
                user(a, limit(YOU, Side.BUY, 1, 1)).orElseThrow().reason(), "all cash is now reserved");
    }

    @Test
    void sellRejectedWhenQtyExceedsUnreservedShares() {
        Account a = new Account(YOU, 0, 10);
        assertEquals(Account.RejectReason.INSUFFICIENT_SHARES,
                user(a, limit(YOU, Side.SELL, 10_00, 11)).orElseThrow().reason());
        assertEquals(Account.RejectReason.INSUFFICIENT_SHARES,
                user(a, market(YOU, Side.SELL, 11)).orElseThrow().reason());
        assertTrue(user(a, limit(YOU, Side.SELL, 10_00, 6)).isEmpty());
        assertEquals(6, a.reservedShares());
        assertEquals(Account.RejectReason.INSUFFICIENT_SHARES,
                user(a, limit(YOU, Side.SELL, 11_00, 5)).orElseThrow().reason(), "overlapping sells can't oversell");
        assertTrue(user(a, limit(YOU, Side.SELL, 11_00, 4)).isEmpty());
        assertEquals(0, a.availableShares());
    }

    @Test
    void buyFillSettlesAtTradePriceAndRefundsTheDifference() {
        Account a = new Account(YOU, 1_000_00, 0);
        other(a, limit(OTHER, Side.SELL, 9_50, 4));
        Command.Place buy = limit(YOU, Side.BUY, 10_00, 10);
        assertTrue(user(a, buy).isEmpty());
        // 4 filled at 9.50 (cost 38.00, reserved 40.00 released for them); 6 rest at 10.00.
        assertEquals(4, a.sharesOwned());
        assertEquals(1_000_00 - 38_00, a.cash());
        assertEquals(60_00, a.reservedCash());

        other(a, limit(OTHER, Side.SELL, 10_00, 6)); // fills the rest as maker at 10.00
        assertEquals(10, a.sharesOwned());
        assertEquals(1_000_00 - 38_00 - 60_00, a.cash());
        assertEquals(0, a.reservedCash());
        assertFalse(a.owns(buy.order().id()));
        assertEquals(10_00, a.lastTradePrice().getAsLong());
        assertEquals(a.cash() + 10 * 10_00, a.equity());
    }

    @Test
    void sellFillDebitsSharesAndCreditsProceeds() {
        Account a = new Account(YOU, 0, 10);
        Command.Place sell = limit(YOU, Side.SELL, 10_00, 10);
        user(a, sell);
        other(a, limit(OTHER, Side.BUY, 10_50, 3));
        assertEquals(7, a.sharesOwned());
        assertEquals(7, a.reservedShares());
        assertEquals(30_00, a.cash(), "maker sells at its own price");

        user(a, new Command.Cancel(sell.order().id()));
        assertEquals(0, a.reservedShares());
        assertEquals(7, a.availableShares());
    }

    @Test
    void sessionPnlSplitsIntoRealizedAndUnrealizedAtAverageCost() {
        Account a = new Account(YOU, 1_000_00, 0);
        other(a, limit(OTHER, Side.SELL, 10_00, 10));
        assertTrue(user(a, limit(YOU, Side.BUY, 10_00, 10)).isEmpty());
        other(a, limit(OTHER, Side.SELL, 12_00, 10));
        assertTrue(user(a, limit(YOU, Side.BUY, 12_00, 10)).isEmpty());
        assertEquals(220_00, a.costBasis());
        assertEquals(11_00, a.averageCost().getAsLong());
        assertEquals(0, a.realizedPnl());
        assertEquals(20_00, a.unrealizedPnl());

        other(a, limit(OTHER, Side.BUY, 13_00, 5));
        assertTrue(user(a, limit(YOU, Side.SELL, 13_00, 5)).isEmpty());
        assertEquals(10_00, a.realizedPnl());
        assertEquals(165_00, a.costBasis());
        assertEquals(30_00, a.unrealizedPnl());
        assertEquals(40_00, a.sessionPnl());
        assertEquals(a.equity() - a.startingCash(), a.sessionPnl());

        other(a, limit(OTHER, Side.BUY, 9_00, 15));
        assertTrue(user(a, limit(YOU, Side.SELL, 9_00, 15)).isEmpty());
        assertEquals(0, a.sharesOwned());
        assertEquals(0, a.costBasis());
        assertTrue(a.averageCost().isEmpty());
        assertEquals(-20_00, a.realizedPnl());
        assertEquals(0, a.unrealizedPnl());
        assertEquals(-20_00, a.sessionPnl());
        assertEquals(980_00, a.cash());
    }

    @Test
    void amendAdjustsTheReservation() {
        Account a = new Account(YOU, 1_000_00, 20);
        Command.Place buy = limit(YOU, Side.BUY, 10_00, 50);
        user(a, buy);
        assertEquals(500_00, a.reservedCash());

        user(a, new Command.Amend(buy.order().id(), null, 20L));
        assertEquals(200_00, a.reservedCash(), "reduce releases part of the reservation");

        user(a, new Command.Amend(buy.order().id(), 20_00L, null));
        assertEquals(400_00, a.reservedCash(), "price increase reserves more");

        assertEquals(Account.RejectReason.INSUFFICIENT_FUNDS,
                user(a, new Command.Amend(buy.order().id(), null, 51L)).orElseThrow().reason());
        assertEquals(400_00, a.reservedCash());
        assertEquals(20, engine.book().find(buy.order().id()).orElseThrow().qtyRemaining());

        Command.Place sell = limit(YOU, Side.SELL, 30_00, 5);
        user(a, sell);
        user(a, new Command.Amend(sell.order().id(), null, 20L));
        assertEquals(20, a.reservedShares());
        assertEquals(Account.RejectReason.INSUFFICIENT_SHARES,
                user(a, new Command.Amend(sell.order().id(), null, 21L)).orElseThrow().reason());
        user(a, new Command.Amend(sell.order().id(), null, 2L));
        assertEquals(2, a.reservedShares());
    }

    @Test
    void marketBuyReservesConservativelyAndRefunds() {
        Account a = new Account(YOU, 1_000_00, 0);
        other(a, limit(OTHER, Side.SELL, 10_00, 5));
        other(a, limit(OTHER, Side.SELL, 12_00, 5));
        // Sweep cost 5 x 10.00 + 5 x 12.00 = 110.00 beats best ask x qty + 5% = 105.00.
        assertEquals(110_00, Account.marketBuyEstimate(engine.book(), 10, a.lastTradePrice()));
        assertEquals(10_50, Account.marketBuyEstimate(engine.book(), 1, a.lastTradePrice()));

        assertTrue(user(a, market(YOU, Side.BUY, 10)).isEmpty());
        assertEquals(10, a.sharesOwned());
        assertEquals(1_000_00 - 110_00, a.cash());
        assertEquals(0, a.reservedCash(), "unused reservation refunded");

        // Partial fill: the remainder of an IOC market order is cancelled, releasing the rest.
        other(a, limit(OTHER, Side.SELL, 12_00, 2));
        assertTrue(user(a, market(YOU, Side.BUY, 5)).isEmpty());
        assertEquals(12, a.sharesOwned());
        assertEquals(0, a.reservedCash());
    }

    @Test
    void marketBuyRejectedWhenEstimateExceedsCash() {
        Account a = new Account(YOU, 50_00, 0);
        other(a, limit(OTHER, Side.SELL, 10_00, 100));
        assertEquals(Account.RejectReason.INSUFFICIENT_FUNDS,
                user(a, market(YOU, Side.BUY, 5)).orElseThrow().reason(), "5 x 10.00 + 5% buffer > 50.00");
        assertTrue(user(a, market(YOU, Side.BUY, 4)).isEmpty());
        assertEquals(4, a.sharesOwned());
    }

    @Test
    void engineRejectionRollsBackTheReservation() {
        Account a = new Account(YOU, 1_000_00, 0);
        other(a, limit(OTHER, Side.SELL, 10_00, 3));
        Command.Place fok = new Command.Place(Order.limit(nextId++, YOU, Side.BUY, 10_00, 5, TimeInForce.FOK));
        assertTrue(user(a, fok).isEmpty());
        assertEquals(0, a.reservedCash());
        assertEquals(0, a.sharesOwned());
        assertEquals(1_000_00, a.cash());
    }

    @Test
    void cannotCancelOrAmendOthersOrders() {
        Account a = new Account(YOU, 1_000_00, 0);
        Command.Place theirs = limit(OTHER, Side.BUY, 10_00, 1);
        other(a, theirs);
        assertEquals(Account.RejectReason.NOT_OWN_OPEN_ORDER,
                user(a, new Command.Cancel(theirs.order().id())).orElseThrow().reason());
        assertEquals(Account.RejectReason.NOT_OWN_OPEN_ORDER,
                user(a, new Command.Amend(theirs.order().id(), null, 5L)).orElseThrow().reason());
        assertTrue(engine.book().find(theirs.order().id()).isPresent());
    }

    /** Random interleavings of user and counterparty flow never drive cash or shares negative. */
    @Property(tries = 200)
    void cashAndSharesNeverGoNegative(@ForAll long seed, @ForAll @IntRange(min = 20, max = 200) int steps,
            @ForAll @LongRange(min = 0, max = 2_000_00) long cash, @ForAll @IntRange(min = 0, max = 50) int shares) {
        MatchingEngine eng = new MatchingEngine();
        Account a = new Account(YOU, cash, shares);
        Random r = new Random(seed);
        long id = 1;
        for (int i = 0; i < steps; i++) {
            boolean mine = r.nextBoolean();
            long participant = mine ? YOU : OTHER;
            Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 9_90 + r.nextInt(21);
            long qty = 1 + r.nextInt(15);
            TimeInForce tif = TimeInForce.values()[r.nextInt(3)];
            Command command = switch (r.nextInt(5)) {
                case 0 -> new Command.Place(Order.market(id++, participant, side, qty, tif == TimeInForce.GTC ? TimeInForce.IOC : tif));
                case 1 -> new Command.Cancel(1 + r.nextInt((int) id));
                case 2 -> new Command.Amend(1 + r.nextInt((int) id), r.nextBoolean() ? price : null, qty);
                default -> new Command.Place(Order.limit(id++, participant, side, price, qty, tif));
            };
            if (mine) {
                if (a.admit(command, eng.book()).isPresent()) {
                    continue;
                }
                a.onEvents(eng.process(command));
            } else if (!(command instanceof Command.Place)) {
                continue; // counterparties only add liquidity / take
            } else {
                a.onEvents(eng.process(command));
            }
            assertTrue(a.cash() >= 0 && a.sharesOwned() >= 0);
            assertTrue(a.availableCash() >= 0 && a.availableShares() >= 0);
            long reservedShares = 0;
            for (Account.OpenOrder o : a.openOrders()) {
                if (o.side() == Side.SELL) {
                    reservedShares += o.qty();
                }
                assertEquals(o.qty(), eng.book().find(o.id()).map(Order::qtyRemaining).orElse(-1L), "tracked order rests");
            }
            assertEquals(reservedShares, a.reservedShares());
        }
    }
}
