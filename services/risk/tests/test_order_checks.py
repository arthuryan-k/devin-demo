"""Pre-trade check edge cases, through the HTTP API against a real ledger."""

import pytest
from portfolio import ledger
from portfolio.db import transaction
from portfolio.testing import auth_account

from tests.test_risk_api import open_account, place


def check_amend(client, account_id, order_id, symbol="ACME", **changes):
    body = {"accountId": account_id, "symbol": symbol, "orderId": order_id, **changes}
    return client.post("/v1/orders/check-amend", json=body).json()


def check_cancel(client, account_id, order_id, symbol="ACME"):
    body = {"accountId": account_id, "symbol": symbol, "orderId": order_id}
    return client.post("/v1/orders/check-cancel", json=body).json()


def release(client, account_id, order_id, symbol="ACME", **kw):
    body = {"accountId": account_id, "symbol": symbol, "orderId": order_id, **kw}
    return client.post("/v1/orders/release", json=body)


def account(pool, account_id):
    with transaction(pool) as conn:
        return ledger.account_view(conn, account_id)


def set_mark(pool, symbol, price):
    with transaction(pool) as conn:
        conn.execute(
            "INSERT INTO portfolio.marks (symbol, last_price) VALUES (%s, %s) "
            "ON CONFLICT (symbol) DO UPDATE SET last_price = EXCLUDED.last_price",
            (symbol, price),
        )


def settle(pool, symbol, *events):
    with transaction(pool) as conn:
        ledger.settle(conn, symbol, list(events))


def assert_ledger_consistent(pool):
    with transaction(pool) as conn:
        assert ledger.invariant_violations(conn) == []


# ---- new orders ------------------------------------------------------------------------------------------------


@pytest.mark.parametrize("qty", [0, -5])
def test_non_positive_quantity_is_invalid(client, pool, qty):
    open_account(pool, 1, 100_000)
    r = place(client, 1, "ACME", 1, "BUY", qty, 1_000)
    assert (r["approved"], r["reason"]) == (False, "INVALID_ORDER")
    assert account(pool, 1)["reservedCash"] == 0


@pytest.mark.parametrize("price", [None, 0, -1])
def test_limit_order_needs_a_positive_price(client, pool, price):
    open_account(pool, 1, 100_000)
    r = place(client, 1, "ACME", 1, "BUY", 1, price)
    assert (r["approved"], r["reason"]) == (False, "INVALID_ORDER")


def test_rejected_checks_leave_no_reservation_behind(client, pool):
    open_account(pool, 1, 5_000, ACME=3)
    assert not place(client, 1, "ACME", 1, "BUY", 10, 1_000)["approved"]
    assert not place(client, 1, "ACME", 2, "SELL", 4, 1_000)["approved"]
    view = account(pool, 1)
    assert view["reservedCash"] == 0
    assert all(p["reservedShares"] == 0 for p in view["positions"])
    # The ids of rejected checks were never recorded, so they can be reused.
    assert place(client, 1, "ACME", 1, "BUY", 5, 1_000)["approved"]
    assert_ledger_consistent(pool)


def test_buy_can_spend_exactly_the_available_cash(client, pool):
    open_account(pool, 1, 10_000)
    first = place(client, 1, "ACME", 1, "BUY", 6, 1_000)
    assert (first["approved"], first["availableCash"]) == (True, 4_000)
    assert place(client, 1, "GLOBX", 2, "BUY", 4, 1_000)["availableCash"] == 0
    assert place(client, 1, "INITD", 3, "BUY", 1, 1)["reason"] == "INSUFFICIENT_FUNDS"


def test_sell_reserves_shares_per_symbol_and_never_goes_short(client, pool):
    open_account(pool, 1, 0, ACME=10, GLOBX=1)
    assert place(client, 1, "ACME", 1, "SELL", 7, 1_000)["availableShares"] == 3
    assert place(client, 1, "ACME", 2, "SELL", 3, 1_000)["availableShares"] == 0
    assert place(client, 1, "ACME", 3, "SELL", 1, 1_000)["reason"] == "NO_SHORTING"
    # Shares reserved on ACME do not affect GLOBX, and a sell needs no cash.
    assert place(client, 1, "GLOBX", 3, "SELL", 1, 1_000)["approved"]
    assert place(client, 1, "NVTA", 4, "SELL", 1, 1_000)["reason"] == "NO_SHORTING"
    assert_ledger_consistent(pool)


def test_order_ids_are_unique_per_symbol(client, pool):
    open_account(pool, 1, 100_000)
    assert place(client, 1, "ACME", 7, "BUY", 1, 1_000)["approved"]
    assert place(client, 1, "GLOBX", 7, "BUY", 1, 1_000)["approved"]
    assert place(client, 1, "ACME", 7, "BUY", 1, 1_000)["reason"] == "DUPLICATE_ORDER_ID"


def test_released_order_id_cannot_be_reused(client, pool):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 1, 1_000)
    assert release(client, 1, 1).json()["approved"]
    assert place(client, 1, "ACME", 1, "BUY", 1, 1_000)["reason"] == "DUPLICATE_ORDER_ID"


def test_notional_overflow_is_rejected_before_touching_the_ledger(client, pool):
    open_account(pool, 1, 100_000)
    r = place(client, 1, "ACME", 1, "BUY", 4, 2**62)
    assert (r["approved"], r["reason"]) == (False, "INVALID_ORDER")
    assert "overflow" in r["message"]


def test_inactive_symbols_are_unknown(client, pool):
    open_account(pool, 1, 100_000)
    with transaction(pool) as conn:
        conn.execute(
            "INSERT INTO reference_data (symbol, name, tick_size, lot_size, initial_cash_grant, active) "
            "VALUES ('DELIST', 'Delisted Co', 0.01, 1, 0, false)"
        )
    try:
        assert place(client, 1, "DELIST", 1, "BUY", 1, 100)["reason"] == "UNKNOWN_SYMBOL"
    finally:
        with transaction(pool) as conn:
            conn.execute("DELETE FROM reference_data WHERE symbol = 'DELIST'")


# ---- market orders ---------------------------------------------------------------------------------------------


def test_market_buy_collars_the_higher_of_mark_and_reference(client, pool):
    open_account(pool, 1, 100_000)
    set_mark(pool, "ACME", 1_000)
    on_mark = place(client, 1, "ACME", 1, "BUY", 2, type="MARKET", tif="IOC")
    assert (on_mark["limitPrice"], on_mark["reservedCash"]) == (1_050, 2_100)
    below = place(client, 1, "ACME", 2, "BUY", 1, type="MARKET", tif="IOC", referencePrice=900)
    assert below["limitPrice"] == 1_050
    above = place(client, 1, "ACME", 3, "BUY", 1, type="MARKET", tif="IOC", referencePrice=1_100)
    assert above["limitPrice"] == 1_155


def test_market_buy_ignores_a_non_positive_reference(client, pool):
    open_account(pool, 1, 100_000)
    r = place(client, 1, "ACME", 1, "BUY", 1, type="MARKET", tif="IOC", referencePrice=0)
    assert r["reason"] == "NO_REFERENCE_PRICE"


def test_market_buy_reserves_against_the_protection_price(client, pool):
    open_account(pool, 1, 10_000)
    set_mark(pool, "ACME", 1_000)
    # 10 x 1000 fits the cash, but the collared 10 x 1050 does not.
    assert place(client, 1, "ACME", 1, "BUY", 10, type="MARKET", tif="FOK")["reason"] == "INSUFFICIENT_FUNDS"
    assert place(client, 1, "ACME", 2, "BUY", 9, type="MARKET", tif="FOK")["reservedCash"] == 9_450


def test_market_collar_comes_from_the_environment(client, pool, monkeypatch):
    monkeypatch.setenv("MARKET_COLLAR_PERCENT", "10")
    open_account(pool, 1, 100_000)
    r = place(client, 1, "ACME", 1, "BUY", 1, type="MARKET", tif="IOC", referencePrice=1_000)
    assert r["limitPrice"] == 1_100


def test_market_sell_needs_shares_but_no_reference_price(client, pool):
    open_account(pool, 1, 0, ACME=5)
    r = place(client, 1, "ACME", 1, "SELL", 5, type="MARKET", tif="IOC")
    assert (r["approved"], r["limitPrice"], r["reservedShares"]) == (True, None, 5)
    assert place(client, 1, "ACME", 2, "SELL", 1, type="MARKET", tif="IOC")["reason"] == "NO_SHORTING"


# ---- accounts --------------------------------------------------------------------------------------------------


def test_auto_open_can_be_disabled(client, pool, monkeypatch):
    monkeypatch.setenv("AUTO_OPEN_ACCOUNTS", "false")
    auth_account(pool, 1001, "100.00")
    assert place(client, 1001, "ACME", 1, "BUY", 1, 100)["reason"] == "UNKNOWN_ACCOUNT"
    monkeypatch.setenv("AUTO_OPEN_ACCOUNTS", "true")
    assert place(client, 1001, "ACME", 1, "BUY", 1, 100)["approved"]


def test_auto_opened_account_converts_dollars_to_ticks(client, pool):
    auth_account(pool, 1001, "12.34")
    assert place(client, 1001, "ACME", 1, "BUY", 1, 34)["availableCash"] == 1_200
    view = account(pool, 1001)
    assert (view["cash"], view["reservedCash"]) == (1_234, 34)


# ---- amends ----------------------------------------------------------------------------------------------------


def test_second_amend_waits_for_the_first(client, pool):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    assert check_amend(client, 1, 1, newPrice=1_100)["approved"]
    assert check_amend(client, 1, 1, newPrice=1_200)["reason"] == "AMEND_PENDING"
    assert release(client, 1, 1, kind="amend").json()["approved"]
    assert check_amend(client, 1, 1, newPrice=1_200)["approved"]


@pytest.mark.parametrize("changes", [{"newPrice": 0}, {"newQty": 0}, {"newPrice": -1, "newQty": 1}])
def test_amend_values_must_be_positive(client, pool, changes):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    assert check_amend(client, 1, 1, **changes)["reason"] == "INVALID_ORDER"


def test_market_orders_cannot_be_amended(client, pool):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 1, type="MARKET", tif="IOC", referencePrice=1_000)
    assert check_amend(client, 1, 1, newQty=2)["reason"] == "INVALID_ORDER"


def test_buy_amend_must_be_covered_by_free_cash(client, pool):
    open_account(pool, 1, 15_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    r = check_amend(client, 1, 1, newPrice=1_200, newQty=10)
    assert (r["approved"], r["reason"]) == (False, "INSUFFICIENT_FUNDS")
    assert account(pool, 1)["reservedCash"] == 10_000


def test_sell_amend_holds_the_new_quantity_until_confirmed(client, pool):
    open_account(pool, 1, 0, ACME=15)
    place(client, 1, "ACME", 1, "SELL", 10, 1_000)
    r = check_amend(client, 1, 1, newQty=5)
    assert (r["approved"], r["reservedShares"], r["availableShares"]) == (True, 5, 0)
    assert check_amend(client, 2, 1, newQty=5).get("approved") is False
    assert_ledger_consistent(pool)


def test_amend_of_someone_elses_or_unknown_order(client, pool):
    open_account(pool, 1, 100_000)
    open_account(pool, 2, 100_000)
    place(client, 1, "ACME", 1, "BUY", 1, 1_000)
    assert check_amend(client, 2, 1, newPrice=900)["reason"] == "NOT_OWN_OPEN_ORDER"
    assert check_amend(client, 1, 99, newPrice=900)["reason"] == "NOT_OWN_OPEN_ORDER"
    assert check_amend(client, 1, 1, symbol="GLOBX", newPrice=900)["reason"] == "NOT_OWN_OPEN_ORDER"


# ---- cancels and releases --------------------------------------------------------------------------------------


def test_filled_orders_can_no_longer_be_cancelled(client, pool):
    open_account(pool, 1, 100_000)
    open_account(pool, 2, 0, ACME=10)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    place(client, 2, "ACME", 2, "SELL", 10, 1_000)
    settle(pool, "ACME", ledger.Placed(1))
    settle(pool, "ACME", ledger.Placed(2), ledger.Trade(1, 2, 1_000, 10))
    assert check_cancel(client, 1, 1)["reason"] == "NOT_OWN_OPEN_ORDER"
    assert account(pool, 1)["reservedCash"] == 0
    assert_ledger_consistent(pool)


def test_cancel_of_an_open_order_reserves_nothing(client, pool):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    settle(pool, "ACME", ledger.Placed(1))
    assert check_cancel(client, 1, 1) == {"approved": True, "orderId": 1}
    assert account(pool, 1)["reservedCash"] == 10_000


def test_release_only_undoes_orders_that_never_reached_the_engine(client, pool):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    settle(pool, "ACME", ledger.Placed(1))
    assert release(client, 1, 1).json()["reason"] == "NOTHING_TO_RELEASE"
    assert release(client, 1, 1, kind="amend").json()["reason"] == "NOTHING_TO_RELEASE"
    assert release(client, 1, 2).json()["reason"] == "NOT_OWN_OPEN_ORDER"
    assert account(pool, 1)["reservedCash"] == 10_000


def test_release_does_not_open_accounts(client, pool):
    auth_account(pool, 1001, "100.00")
    assert release(client, 1001, 1).json()["reason"] == "UNKNOWN_ACCOUNT"


def test_release_kind_is_validated(client, pool):
    open_account(pool, 1, 100_000)
    assert release(client, 1, 1, kind="everything").status_code == 422


def test_place_release_returns_the_pending_amend_too(client, pool):
    open_account(pool, 1, 100_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    check_amend(client, 1, 1, newQty=12)
    r = release(client, 1, 1).json()
    assert (r["approved"], r["reservedCash"], r["availableCash"]) == (True, 22_000, 100_000)
    assert_ledger_consistent(pool)
