import pytest

from portfolio import ledger
from portfolio.db import transaction
from portfolio.ledger import Amended, Cancelled, Placed, Reject, Rejected, Trade
from tests.helpers import check, open_account, position, settle, view, violations

ALICE, BOB = 1, 2


@pytest.fixture(autouse=True)
def no_violations(pool):
    yield
    assert violations(pool) == []


def test_buy_acme_locks_cash_and_sell_globx_locks_shares(pool):
    open_account(pool, ALICE, 100_000, GLOBX=50)
    buy = check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000)
    sell = check(pool, ALICE, "GLOBX", 1, "SELL", 20, 2_000)
    assert (buy.reserved_cash, buy.limit_price) == (10_000, 1_000)
    assert sell.reserved_shares == 20
    v = view(pool, ALICE)
    assert (v["cash"], v["reservedCash"], v["availableCash"]) == (100_000, 10_000, 90_000)
    globx = position(pool, ALICE, "GLOBX")
    assert (globx["shares"], globx["reservedShares"], globx["availableShares"]) == (50, 20, 30)
    assert {(o["symbol"], o["orderId"], o["status"]) for o in v["openOrders"]} == {("ACME", 1, "PENDING"),
                                                                                  ("GLOBX", 1, "PENDING")}


def test_rejects_insufficient_funds_and_leaves_state_untouched(pool):
    open_account(pool, ALICE, 9_999)
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000)
    assert e.value.reason == Reject.INSUFFICIENT_FUNDS
    assert view(pool, ALICE)["reservedCash"] == 0
    assert view(pool, ALICE)["openOrders"] == []


def test_rejects_shorting(pool):
    open_account(pool, ALICE, 100_000, ACME=5)
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "ACME", 1, "SELL", 6, 1_000)
    assert e.value.reason == Reject.NO_SHORTING
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "GLOBX", 1, "SELL", 1, 1_000)  # holds no GLOBX at all
    assert e.value.reason == Reject.NO_SHORTING
    check(pool, ALICE, "ACME", 2, "SELL", 5, 1_000)
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "ACME", 3, "SELL", 1, 1_000)  # all 5 already reserved by order 2
    assert e.value.reason == Reject.NO_SHORTING


def test_shared_cash_across_symbols(pool):
    open_account(pool, ALICE, 50_000)
    check(pool, ALICE, "ACME", 1, "BUY", 30, 1_000)
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "GLOBX", 1, "BUY", 21, 1_000)  # 30k locked by ACME leaves 20k
    assert e.value.reason == Reject.INSUFFICIENT_FUNDS
    check(pool, ALICE, "GLOBX", 1, "BUY", 20, 1_000)
    assert view(pool, ALICE)["availableCash"] == 0
    # Filling the GLOBX buy and cancelling the ACME one frees ACME's lock for anything, including NVTA.
    settle(pool, "ACME", Placed(1))
    settle(pool, "GLOBX", Placed(1), Trade(99, 1, 1_000, 20))
    settle(pool, "ACME", Cancelled(1, 30))
    v = view(pool, ALICE)
    assert (v["cash"], v["reservedCash"]) == (30_000, 0)
    check(pool, ALICE, "NVTA", 1, "BUY", 30, 1_000)


def test_same_order_id_on_different_symbols_is_independent(pool):
    open_account(pool, ALICE, 100_000, GLOBX=10)
    check(pool, ALICE, "ACME", 7, "BUY", 1, 100)
    check(pool, ALICE, "GLOBX", 7, "SELL", 1, 100)
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "ACME", 7, "BUY", 1, 100)
    assert e.value.reason == Reject.DUPLICATE_ORDER_ID
    settle(pool, "ACME", Placed(7), Cancelled(7, 1))
    assert position(pool, ALICE, "GLOBX")["reservedShares"] == 1


def test_fill_below_limit_refunds_difference_and_partial_fill_keeps_rest_locked(pool):
    open_account(pool, ALICE, 100_000)
    check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000)
    settle(pool, "ACME", Placed(1), Trade(50, 1, 900, 4))
    v = view(pool, ALICE)
    assert v["cash"] == 100_000 - 3_600
    assert v["reservedCash"] == 6_000  # 6 still open at the 1_000 limit
    assert v["availableCash"] == 100_000 - 3_600 - 6_000  # the 400 saved on the fill is available again
    acme = position(pool, ALICE, "ACME")
    assert (acme["shares"], acme["costBasis"], acme["avgCost"], acme["markPrice"]) == (4, 3_600, 900, 900)
    settle(pool, "ACME", Trade(51, 1, 950, 6))
    v = view(pool, ALICE)
    assert (v["cash"], v["reservedCash"], v["openOrders"]) == (100_000 - 3_600 - 5_700, 0, [])


def test_cancel_releases_remaining_reservation(pool):
    open_account(pool, ALICE, 100_000, GLOBX=10)
    check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000)
    check(pool, ALICE, "GLOBX", 2, "SELL", 8, 2_000)
    settle(pool, "ACME", Placed(1), Trade(50, 1, 1_000, 3))
    settle(pool, "GLOBX", Placed(2), Trade(2, 60, 2_000, 5))
    settle(pool, "ACME", Cancelled(1, 7))
    settle(pool, "GLOBX", Cancelled(2, 3))
    v = view(pool, ALICE)
    assert (v["cash"], v["reservedCash"]) == (100_000 - 3_000 + 10_000, 0)
    globx = position(pool, ALICE, "GLOBX")
    assert (globx["shares"], globx["reservedShares"]) == (5, 0)
    assert v["openOrders"] == []


def test_ioc_remainder_and_engine_rejection_release(pool):
    open_account(pool, ALICE, 100_000)
    check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000, tif="IOC")
    settle(pool, "ACME", Placed(1), Trade(50, 1, 990, 2), Cancelled(1, 8, "UNFILLED_REMAINDER"))
    check(pool, ALICE, "ACME", 2, "BUY", 10, 1_000, tif="FOK")
    settle(pool, "ACME", Rejected(2, "FOK_NOT_FILLABLE"))
    v = view(pool, ALICE)
    assert (v["cash"], v["reservedCash"], v["openOrders"]) == (100_000 - 1_980, 0, [])


def test_trade_between_two_accounts_settles_both_and_pnl_at_average_cost(pool):
    open_account(pool, ALICE, 100_000)
    open_account(pool, BOB, 0, ACME=(20, 1_000))
    check(pool, BOB, "ACME", 10, "SELL", 20, 1_100)
    settle(pool, "ACME", Placed(10))
    check(pool, ALICE, "ACME", 11, "BUY", 15, 1_200)
    settle(pool, "ACME", Placed(11), Trade(10, 11, 1_100, 15), Cancelled(11, 0))
    alice, bob = view(pool, ALICE), view(pool, BOB)
    assert (alice["cash"], alice["reservedCash"]) == (100_000 - 16_500, 0)
    assert (bob["cash"], bob["realizedPnl"]) == (16_500, 15 * 100)
    bob_acme = position(pool, BOB, "ACME")
    assert (bob_acme["shares"], bob_acme["reservedShares"], bob_acme["costBasis"]) == (5, 5, 5_000)
    assert bob_acme["unrealizedPnl"] == 5 * 1_100 - 5_000
    assert bob["sessionPnl"] == 1_500 + 500
    # A later print elsewhere re-marks Alice's ACME position.
    settle(pool, "ACME", Trade(900, 901, 1_000, 1))
    alice = view(pool, ALICE)
    assert alice["unrealizedPnl"] == 15 * 1_000 - 16_500
    assert alice["equity"] == alice["cash"] + 15_000


def test_average_cost_across_buys_then_partial_sell(pool):
    open_account(pool, ALICE, 1_000_000)
    check(pool, ALICE, "INITD", 1, "BUY", 10, 100)
    check(pool, ALICE, "INITD", 2, "BUY", 10, 200)
    settle(pool, "INITD", Placed(1), Trade(90, 1, 100, 10))
    settle(pool, "INITD", Placed(2), Trade(91, 2, 200, 10))
    check(pool, ALICE, "INITD", 3, "SELL", 5, 300)
    settle(pool, "INITD", Placed(3), Trade(3, 92, 300, 5))
    p = position(pool, ALICE, "INITD")
    assert (p["shares"], p["costBasis"], p["avgCost"], p["realizedPnl"]) == (15, 2_250, 150, 5 * 300 - 750)
    assert p["unrealizedPnl"] == 15 * 300 - 2_250


def test_market_buy_is_protected_by_collar_over_last_trade(pool):
    open_account(pool, ALICE, 100_000)
    with pytest.raises(Reject) as e:
        check(pool, ALICE, "ACME", 1, "BUY", 10, order_type="MARKET", tif="IOC")
    assert e.value.reason == Reject.NO_REFERENCE_PRICE
    settle(pool, "ACME", Trade(900, 901, 1_000, 1))
    a = check(pool, ALICE, "ACME", 1, "BUY", 10, order_type="MARKET", tif="IOC")
    assert (a.limit_price, a.reserved_cash) == (1_050, 10_500)
    b = check(pool, ALICE, "ACME", 2, "BUY", 1, order_type="MARKET", tif="IOC", reference_price=1_100)
    assert b.limit_price == 1_155
    settle(pool, "ACME", Placed(1), Trade(50, 1, 1_020, 10))
    assert view(pool, ALICE)["cash"] == 100_000 - 10_200


def test_market_sell_reserves_shares(pool):
    open_account(pool, ALICE, 0, NVTA=4)
    a = check(pool, ALICE, "NVTA", 1, "SELL", 4, order_type="MARKET", tif="IOC")
    assert (a.reserved_shares, a.limit_price) == (4, None)
    settle(pool, "NVTA", Placed(1), Trade(77, 1, 500, 3), Cancelled(1, 1, "UNFILLED_REMAINDER"))
    p = position(pool, ALICE, "NVTA")
    assert (p["shares"], p["reservedShares"], view(pool, ALICE)["cash"]) == (1, 0, 1_500)


def amend(pool, account_id, symbol, order_id, new_price=None, new_qty=None):
    with transaction(pool) as conn:
        return ledger.check_amend(conn, account_id, symbol, order_id, new_price, new_qty)


def test_amend_price_up_locks_delta_and_confirmation_settles_it(pool):
    open_account(pool, ALICE, 20_000)
    check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000)
    settle(pool, "ACME", Placed(1))
    with pytest.raises(Reject) as e:
        amend(pool, ALICE, "ACME", 1, new_price=2_100)
    assert e.value.reason == Reject.INSUFFICIENT_FUNDS
    assert amend(pool, ALICE, "ACME", 1, new_price=1_500).reserved_cash == 5_000
    with pytest.raises(Reject) as e:
        amend(pool, ALICE, "ACME", 1, new_price=1_600)
    assert e.value.reason == Reject.AMEND_PENDING
    # A fill lands before the amend, then the engine re-prices the 6 left.
    settle(pool, "ACME", Trade(50, 1, 1_000, 4))
    settle(pool, "ACME", Amended(1, 1_500, 6))
    v = view(pool, ALICE)
    assert (v["cash"], v["reservedCash"]) == (16_000, 9_000)
    assert v["openOrders"][0]["limitPrice"] == 1_500


def test_amend_rejected_by_engine_releases_pending(pool):
    open_account(pool, ALICE, 100_000, GLOBX=10)
    check(pool, ALICE, "GLOBX", 1, "SELL", 4, 1_000)
    settle(pool, "GLOBX", Placed(1))
    amend(pool, ALICE, "GLOBX", 1, new_qty=6)
    assert position(pool, ALICE, "GLOBX")["reservedShares"] == 10
    settle(pool, "GLOBX", Rejected(1, "UNKNOWN_ORDER_ID"))
    assert position(pool, ALICE, "GLOBX")["reservedShares"] == 4


def test_quantity_amend_racing_a_fill_stays_covered(pool):
    open_account(pool, ALICE, 0, GLOBX=10)
    check(pool, ALICE, "GLOBX", 1, "SELL", 5, 1_000)
    settle(pool, "GLOBX", Placed(1))
    amend(pool, ALICE, "GLOBX", 1, new_qty=5)  # price change via qty re-entry; the whole replacement is held
    settle(pool, "GLOBX", Trade(1, 70, 1_000, 3))  # fills before the engine sees the amend
    settle(pool, "GLOBX", Amended(1, 1_000, 5))  # the engine replaces the remaining 2 with 5
    settle(pool, "GLOBX", Trade(1, 71, 1_000, 5))
    p = position(pool, ALICE, "GLOBX")
    assert (p["shares"], p["reservedShares"]) == (2, 0)
    assert view(pool, ALICE)["cash"] == 8_000


def test_cancel_check_requires_own_live_order(pool):
    open_account(pool, ALICE, 100_000)
    open_account(pool, BOB, 100_000)
    check(pool, ALICE, "ACME", 1, "BUY", 1, 100)
    with transaction(pool) as conn:
        ledger.check_cancel(conn, ALICE, "ACME", 1)
    with pytest.raises(Reject) as e, transaction(pool) as conn:
        ledger.check_cancel(conn, BOB, "ACME", 1)
    assert e.value.reason == Reject.NOT_OWN_OPEN_ORDER


def test_release_after_failed_publish(pool):
    open_account(pool, ALICE, 100_000)
    check(pool, ALICE, "ACME", 1, "BUY", 10, 1_000)
    with transaction(pool) as conn:
        released = ledger.release(conn, ALICE, "ACME", 1, "place")
    assert released.reserved_cash == 10_000
    assert view(pool, ALICE)["reservedCash"] == 0
    with pytest.raises(Reject) as e, transaction(pool) as conn:
        ledger.release(conn, ALICE, "ACME", 1, "place")
    assert e.value.reason == Reject.NOTHING_TO_RELEASE


def test_unknown_symbol_and_invalid_orders(pool):
    open_account(pool, ALICE, 100_000)
    for args, reason in [
        (("XXX", 1, "BUY", 1, 100), Reject.UNKNOWN_SYMBOL),
        (("ACME", 1, "BUY", 0, 100), Reject.INVALID_ORDER),
        (("ACME", 1, "BUY", 1, 0), Reject.INVALID_ORDER),
        (("ACME", 1, "BUY", 2**62, 2**62), Reject.INVALID_ORDER),
    ]:
        with pytest.raises(Reject) as e:
            check(pool, ALICE, *args)
        assert e.value.reason == reason
    with pytest.raises(Reject) as e:
        check(pool, 99, "ACME", 1, "BUY", 1, 100)
    assert e.value.reason == Reject.UNKNOWN_ACCOUNT


def test_orders_that_skipped_risk_only_move_the_mark(pool):
    open_account(pool, ALICE, 100_000)
    settle(pool, "ACME", Placed(500), Trade(500, 501, 1_234, 5), Cancelled(500, 1))
    assert view(pool, ALICE)["cash"] == 100_000
    with transaction(pool) as conn:
        assert conn.execute("SELECT last_price FROM portfolio.marks WHERE symbol = 'ACME'").fetchone()[
            "last_price"] == 1_234


def test_db_constraints_reject_negative_balances(pool):
    open_account(pool, ALICE, 100)
    with pytest.raises(Exception), transaction(pool) as conn:
        conn.execute("UPDATE portfolio.accounts SET cash = -1 WHERE account_id = 1")
    with pytest.raises(Exception), transaction(pool) as conn:
        conn.execute("UPDATE portfolio.accounts SET reserved_cash = 101 WHERE account_id = 1")
