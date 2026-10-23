from portfolio import ledger
from portfolio.db import transaction


def open_account(pool, account_id, cash, **positions):
    with transaction(pool) as conn:
        ledger.open_account(
            conn,
            account_id,
            cash,
            [ledger.Grant(s, q[0], q[1]) if isinstance(q, tuple) else ledger.Grant(s, q) for s, q in positions.items()],
        )


def check(pool, account_id, symbol, order_id, side, qty, price=None, order_type="LIMIT", tif="GTC", **kw):
    with transaction(pool) as conn:
        return ledger.check_order(
            conn,
            ledger.OrderCheck(account_id, symbol, order_id, side, order_type, tif, qty, price, **kw),
            auto_open=False,
        )


def settle(pool, symbol, *events, seq=None):
    with transaction(pool) as conn:
        ledger.settle(conn, symbol, list(events), seq)


def view(pool, account_id):
    with transaction(pool) as conn:
        return ledger.account_view(conn, account_id)


def position(pool, account_id, symbol):
    return next((p for p in view(pool, account_id)["positions"] if p["symbol"] == symbol), None)


def violations(pool):
    with transaction(pool) as conn:
        return ledger.invariant_violations(conn)
