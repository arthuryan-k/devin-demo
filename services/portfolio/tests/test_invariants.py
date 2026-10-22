"""Randomized multi-account, multi-symbol flow through a tiny reference matcher; the ledger must stay consistent."""
import random

import pytest

from portfolio import ledger
from portfolio.db import transaction
from portfolio.ledger import Amended, Cancelled, Placed, Reject, Rejected, Trade
from tests.helpers import open_account, violations

SYMBOLS = ["ACME", "GLOBX", "INITD", "NVTA"]


class Book:
    """Minimal price-time matcher emitting the engine's events (limit/IOC only, trades at the maker's price)."""

    def __init__(self):
        self.orders = {}  # order_id -> [side, price, qty, owner, seqno]
        self.seqno = 0

    def place(self, oid, owner, side, price, qty, tif):
        events = [Placed(oid)]
        for mid, m in sorted(self.orders.items(), key=lambda kv: (kv[1][1] if side == "BUY" else -kv[1][1],
                                                                  kv[1][4])):
            if qty == 0:
                break
            crosses = m[0] != side and (price >= m[1] if side == "BUY" else price <= m[1])
            if not crosses or m[3] == owner:
                continue
            take = min(qty, m[2])
            events.append(Trade(mid, oid, m[1], take))
            m[2] -= take
            qty -= take
            if m[2] == 0:
                del self.orders[mid]
        if qty:
            if tif == "IOC":
                events.append(Cancelled(oid, qty, "UNFILLED_REMAINDER"))
            else:
                self.seqno += 1
                self.orders[oid] = [side, price, qty, owner, self.seqno]
        return events

    def cancel(self, oid):
        o = self.orders.pop(oid, None)
        return [Cancelled(oid, o[2])] if o else [Rejected(oid, "UNKNOWN_ORDER_ID")]

    def amend(self, oid, new_price, new_qty):
        o = self.orders.get(oid)
        if o is None:
            return [Rejected(oid, "UNKNOWN_ORDER_ID")]
        price = new_price or o[1]
        qty = new_qty or o[2]
        if price == o[1] and qty <= o[2]:
            o[2] = qty
            return [Amended(oid, price, qty)]
        side, owner = o[0], o[3]
        del self.orders[oid]
        events = self.place(oid, owner, side, price, qty, "GTC")
        return [Amended(oid, price, qty)] + events[1:]


@pytest.mark.parametrize("seed", range(6))
def test_random_flow_never_breaks_invariants(pool, seed):
    rng = random.Random(seed)
    accounts = range(1, 6)
    for a in accounts:
        open_account(pool, a, rng.randint(0, 200_000), **{s: rng.randint(0, 40) for s in SYMBOLS})
    books = {s: Book() for s in SYMBOLS}
    next_id = 1
    rejects = 0
    for step in range(300):
        s = rng.choice(SYMBOLS)
        book = books[s]
        a = rng.choice(list(accounts))
        events = None
        with transaction(pool) as conn:
            try:
                action = rng.random()
                own = [oid for oid, o in book.orders.items() if o[3] == a]
                if action < 0.65 or not own:
                    side = rng.choice(["BUY", "SELL"])
                    price, qty, tif = rng.randint(90, 110), rng.randint(1, 30), rng.choice(["GTC", "GTC", "IOC"])
                    ledger.check_order(conn, ledger.OrderCheck(a, s, next_id, side, "LIMIT", tif, qty, price),
                                       auto_open=False)
                    events = ("place", next_id, a, side, price, qty, tif)
                    next_id += 1
                elif action < 0.85:
                    oid = rng.choice(own)
                    ledger.check_cancel(conn, a, s, oid)
                    events = ("cancel", oid)
                else:
                    oid = rng.choice(own)
                    new_price = rng.choice([None, rng.randint(90, 110)])
                    new_qty = rng.choice([None, rng.randint(1, 30)]) if new_price else rng.randint(1, 30)
                    ledger.check_amend(conn, a, s, oid, new_price, new_qty)
                    events = ("amend", oid, new_price, new_qty)
            except Reject:
                rejects += 1
        if events is None:
            continue
        if events[0] == "place":
            out = book.place(*events[1:])
        elif events[0] == "cancel":
            out = book.cancel(events[1])
        else:
            out = book.amend(*events[1:])
        with transaction(pool) as conn:
            ledger.settle(conn, s, out, step)
        if step % 25 == 0:
            assert violations(pool) == []
    assert violations(pool) == []
    assert rejects > 0
    with transaction(pool) as conn:
        assert conn.execute("SELECT count(*) AS n FROM portfolio.fills").fetchone()["n"] > 20
        # Cash is conserved across accounts: every fill moves cash between two ledger accounts.
        total = conn.execute("SELECT sum(cash) AS c FROM portfolio.accounts").fetchone()["c"]
        start = conn.execute("SELECT sum(starting_cash) AS c FROM portfolio.accounts").fetchone()["c"]
        assert total == start
