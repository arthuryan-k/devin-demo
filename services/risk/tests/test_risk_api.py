from concurrent.futures import ThreadPoolExecutor

from portfolio import ledger
from portfolio.db import transaction


def open_account(pool, account_id, cash, **shares):
    with transaction(pool) as conn:
        ledger.open_account(conn, account_id, cash, [ledger.Grant(s, q) for s, q in shares.items()])


def place(client, account_id, symbol, order_id, side, qty, price=None, **kw):
    return client.post(
        "/v1/orders/check",
        json={
            "accountId": account_id,
            "symbol": symbol,
            "orderId": order_id,
            "side": side,
            "qty": qty,
            "price": price,
            **kw,
        },
    ).json()


def test_approves_and_reports_reservation(client, pool):
    open_account(pool, 1, 100_000, GLOBX=30)
    buy = place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    assert buy == {
        "approved": True,
        "orderId": 1,
        "reservedCash": 10_000,
        "reservedShares": 0,
        "limitPrice": 1_000,
        "availableCash": 90_000,
        "availableShares": 0,
    }
    sell = place(client, 1, "GLOBX", 2, "SELL", 30, 500)
    assert (sell["approved"], sell["reservedShares"], sell["availableShares"]) == (True, 30, 0)


def test_rejections_carry_machine_readable_reasons(client, pool):
    open_account(pool, 1, 1_000, ACME=1)
    assert place(client, 1, "ACME", 1, "BUY", 2, 1_000)["reason"] == "INSUFFICIENT_FUNDS"
    assert place(client, 1, "ACME", 2, "SELL", 2, 1_000)["reason"] == "NO_SHORTING"
    assert place(client, 1, "NOPE", 3, "BUY", 1, 1)["reason"] == "UNKNOWN_SYMBOL"
    assert place(client, 1, "ACME", 4, "BUY", 1, type="MARKET", tif="IOC")["reason"] == "NO_REFERENCE_PRICE"
    market = place(client, 1, "ACME", 4, "BUY", 1, type="MARKET", tif="IOC", referencePrice=900)
    assert (market["approved"], market["limitPrice"]) == (True, 945)
    assert place(client, 1, "ACME", 4, "BUY", 1, 1)["reason"] == "DUPLICATE_ORDER_ID"
    assert (
        client.post(
            "/v1/orders/check", json={"accountId": 1, "symbol": "ACME", "orderId": 9, "side": "UP", "qty": 1}
        ).status_code
        == 422
    )


def test_auth_account_is_opened_with_its_starting_cash(client, pool):
    from portfolio.testing import auth_account

    auth_account(pool, 1001, "50.00")
    order = {
        "accountId": 1001,
        "symbol": "ACME",
        "orderId": 1,
        "side": "BUY",
        "type": "LIMIT",
        "tif": "GTC",
        "price": 1000,
        "qty": 5,
    }
    r = client.post("/v1/orders/check", json=order).json()
    assert r["approved"] and r["availableCash"] == 0
    r = client.post("/v1/orders/check", json={**order, "orderId": 2, "qty": 1}).json()
    assert (r["approved"], r["reason"]) == (False, "INSUFFICIENT_FUNDS")
    r = client.post("/v1/orders/check", json={**order, "accountId": 4242}).json()
    assert (r["approved"], r["reason"]) == (False, "UNKNOWN_ACCOUNT")


def test_amend_cancel_and_release_routes(client, pool):
    open_account(pool, 1, 100_000)
    open_account(pool, 2, 100_000)
    place(client, 1, "ACME", 1, "BUY", 10, 1_000)
    amend = client.post(
        "/v1/orders/check-amend", json={"accountId": 1, "symbol": "ACME", "orderId": 1, "newPrice": 1_200}
    ).json()
    assert (amend["approved"], amend["reservedCash"], amend["limitPrice"]) == (True, 2_000, 1_200)
    released = client.post(
        "/v1/orders/release", json={"accountId": 1, "symbol": "ACME", "orderId": 1, "kind": "amend"}
    ).json()
    assert (released["approved"], released["reservedCash"]) == (True, 2_000)
    assert client.post("/v1/orders/check-cancel", json={"accountId": 1, "symbol": "ACME", "orderId": 1}).json() == {
        "approved": True,
        "orderId": 1,
    }
    assert (
        client.post("/v1/orders/check-cancel", json={"accountId": 2, "symbol": "ACME", "orderId": 1}).json()["reason"]
        == "NOT_OWN_OPEN_ORDER"
    )
    released = client.post("/v1/orders/release", json={"accountId": 1, "symbol": "ACME", "orderId": 1}).json()
    assert (released["approved"], released["reservedCash"], released["availableCash"]) == (True, 10_000, 100_000)


def test_concurrent_checks_never_overcommit_shared_cash(client, pool):
    open_account(pool, 1, 100_000, ACME=50)
    symbols = ["ACME", "GLOBX", "INITD", "NVTA"]

    def buy(i):
        return place(client, 1, symbols[i % 4], i, "BUY", 1, 1_000)

    def sell(i):
        return place(client, 1, "ACME", 1_000 + i, "SELL", 1, 1_000)

    with ThreadPoolExecutor(16) as ex:
        buys = list(ex.map(buy, range(200)))
        sells = list(ex.map(sell, range(80)))
    assert sum(r["approved"] for r in buys) == 100
    assert {r["reason"] for r in buys if not r["approved"]} == {"INSUFFICIENT_FUNDS"}
    assert sum(r["approved"] for r in sells) == 50
    with transaction(pool) as conn:
        assert ledger.invariant_violations(conn) == []
        acct = ledger.account_view(conn, 1)
    assert (acct["cash"], acct["reservedCash"]) == (100_000, 100_000)


def test_gateway_payload_shape_and_route_aliases(client, pool):
    """Phase 5 gateway sends participantId (+ its own accountId), orderType/timeInForce, and uses /v1/<op>."""
    from portfolio.testing import auth_account

    auth_account(pool, 1001, "100.00")
    base = {"participantId": 1001, "accountId": 7, "symbol": "ACME", "orderId": 11}
    r = client.post(
        "/v1/check", json={**base, "side": "BUY", "orderType": "LIMIT", "timeInForce": "IOC", "price": 1000, "qty": 4}
    ).json()
    assert (r["approved"], r["reservedCash"], r["limitPrice"]) == (True, 4000, 1000)
    r = client.post("/v1/check-amend", json={**base, "newPrice": 900, "newQty": None}).json()
    assert r["approved"]
    r = client.post("/v1/release", json={**base, "kind": "amend"}).json()
    assert (r["approved"], r["reservedCash"]) == (True, 0)  # a lower price needed no extra reservation
    r = client.post("/v1/release", json=base).json()  # release reports what it released
    assert (r["approved"], r["reservedCash"], r["availableCash"]) == (True, 4000, 10000)
    r = client.post("/v1/release", json=base).json()
    assert (r["approved"], r["reason"]) == (False, "NOTHING_TO_RELEASE")
    assert client.post("/v1/check", json={"symbol": "ACME", "orderId": 1, "side": "BUY", "qty": 1}).status_code == 422
