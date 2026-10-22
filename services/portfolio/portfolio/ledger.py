"""The portfolio ledger: one shared cash balance per account, positions keyed by symbol, and per-order reservations.

All amounts are integer ticks (price ticks x shares); quantities are whole shares. Every operation runs inside a
caller-supplied transaction and takes row locks in one global order -- accounts (ascending id), then positions, then
reservations -- so the synchronous risk checks and the fill settlement consumer serialize per account without
deadlocking. Invariants (also enforced by CHECK constraints): cash, reserved cash, shares and reserved shares are
never negative, reserved cash <= cash and reserved shares <= shares per symbol.

Reservation rules (lock on placement, release on fill / cancel / reject):

* Limit buy: locks ``limit x qty``. A fill at price p debits ``p x q`` and releases ``limit x q``, so a fill below
  the limit refunds the difference.
* Market buy: there is no price to lock against, so risk turns it into a protected order. It locks
  ``protection x qty`` where protection = anchor x (1 + collar), the anchor being the larger of the last trade and
  the caller's reference price (e.g. best ask). The caller must send it to the engine as a LIMIT at that price.
* Sell (limit or market): locks the shares. No shorting: qty must not exceed the available shares of that symbol.
* Amend: price-only amends lock just the extra cost. Amends that set a quantity lock the whole replacement until
  the engine confirms, because the engine's new quantity replaces the remaining quantity even if fills land
  in between.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable

import psycopg

from portfolio import config

INT64_MAX = 2**63 - 1
LIVE = ("PENDING", "OPEN")


class Reject(Exception):
    """A pre-trade check failure with a machine-readable reason."""

    INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
    NO_SHORTING = "NO_SHORTING"
    UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT"
    UNKNOWN_SYMBOL = "UNKNOWN_SYMBOL"
    INVALID_ORDER = "INVALID_ORDER"
    DUPLICATE_ORDER_ID = "DUPLICATE_ORDER_ID"
    NOT_OWN_OPEN_ORDER = "NOT_OWN_OPEN_ORDER"
    AMEND_PENDING = "AMEND_PENDING"
    NO_REFERENCE_PRICE = "NO_REFERENCE_PRICE"
    NOTHING_TO_RELEASE = "NOTHING_TO_RELEASE"

    def __init__(self, reason: str, message: str):
        super().__init__(f"{reason}: {message}")
        self.reason = reason
        self.message = message


class AccountExists(Exception):
    pass


class InvariantBreach(RuntimeError):
    """Settlement would break a no-negative invariant. Unreachable when every order went through risk."""


# ---- accounts ------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Grant:
    symbol: str
    shares: int
    avg_cost: int = 0


def open_account(conn: psycopg.Connection, account_id: int, cash: int | None, grants: Iterable[Grant] = ()) -> None:
    """Opens a ledger account; ``cash=None`` takes the grant from the participant's ``public.accounts`` row."""
    if cash is None:
        row = conn.execute(
            "SELECT (starting_cash * 100)::bigint AS cash FROM public.accounts WHERE participant_id = %s",
            (account_id,),
        ).fetchone()
        if row is None:
            raise Reject(Reject.UNKNOWN_ACCOUNT, f"participant {account_id} has no account")
        cash = row["cash"]
    if cash < 0:
        raise ValueError("cash must be non-negative")
    inserted = conn.execute(
        "INSERT INTO portfolio.accounts (account_id, cash, starting_cash) VALUES (%s, %s, %s) "
        "ON CONFLICT (account_id) DO NOTHING",
        (account_id, cash, cash),
    ).rowcount
    if not inserted:
        raise AccountExists(account_id)
    for g in grants:
        _check_symbol(conn, g.symbol)
        if g.shares < 0 or g.avg_cost < 0:
            raise ValueError("granted shares and cost must be non-negative")
        conn.execute(
            "INSERT INTO portfolio.positions (account_id, symbol, shares, cost_basis) VALUES (%s, %s, %s, %s)",
            (account_id, g.symbol, g.shares, g.shares * g.avg_cost),
        )


def _lock_account(conn: psycopg.Connection, account_id: int, auto_open: bool) -> dict:
    row = conn.execute(
        "SELECT * FROM portfolio.accounts WHERE account_id = %s FOR UPDATE", (account_id,)
    ).fetchone()
    if row is None and auto_open:
        # The account's cash grant comes from auth-service's account (NUMERIC dollars -> integer cents/ticks).
        conn.execute(
            "INSERT INTO portfolio.accounts (account_id, cash, starting_cash) "
            "SELECT participant_id, (starting_cash * 100)::bigint, (starting_cash * 100)::bigint "
            "FROM public.accounts WHERE participant_id = %s "
            "ON CONFLICT (account_id) DO NOTHING",
            (account_id,),
        )
        row = conn.execute(
            "SELECT * FROM portfolio.accounts WHERE account_id = %s FOR UPDATE", (account_id,)
        ).fetchone()
    if row is None:
        raise Reject(Reject.UNKNOWN_ACCOUNT, f"account {account_id} does not exist")
    return row


def _lock_position(conn: psycopg.Connection, account_id: int, symbol: str) -> dict:
    conn.execute(
        "INSERT INTO portfolio.positions (account_id, symbol) VALUES (%s, %s) ON CONFLICT DO NOTHING",
        (account_id, symbol),
    )
    return conn.execute(
        "SELECT * FROM portfolio.positions WHERE account_id = %s AND symbol = %s FOR UPDATE", (account_id, symbol)
    ).fetchone()


def _check_symbol(conn: psycopg.Connection, symbol: str) -> None:
    listed = conn.execute(
        "SELECT 1 FROM public.reference_data WHERE symbol = %s AND active", (symbol,)
    ).fetchone()
    if listed is None:
        raise Reject(Reject.UNKNOWN_SYMBOL, f"unknown symbol {symbol!r}")


def _mul(a: int, b: int) -> int:
    product = a * b
    if product > INT64_MAX:
        raise Reject(Reject.INVALID_ORDER, "notional overflows")
    return product


# ---- pre-trade checks (risk) ---------------------------------------------------------------------------------------


@dataclass(frozen=True)
class OrderCheck:
    account_id: int
    symbol: str
    order_id: int
    side: str  # BUY | SELL
    order_type: str  # LIMIT | MARKET
    tif: str
    qty: int
    price: int | None = None  # limit price in ticks; ignored for MARKET
    reference_price: int | None = None  # optional anchor for market buys (e.g. best ask)


@dataclass(frozen=True)
class Approval:
    order_id: int
    reserved_cash: int
    reserved_shares: int
    limit_price: int | None
    available_cash: int
    available_shares: int


def protection_price(anchor: int, collar_percent: int) -> int:
    return -(-anchor * (100 + collar_percent) // 100)


def check_order(conn: psycopg.Connection, req: OrderCheck, *, auto_open: bool | None = None) -> Approval:
    """Validates a new order and reserves cash (buy) or shares (sell) atomically. Raises :class:`Reject`."""
    _check_symbol(conn, req.symbol)
    if req.side not in ("BUY", "SELL") or req.order_type not in ("LIMIT", "MARKET"):
        raise Reject(Reject.INVALID_ORDER, "side must be BUY/SELL and type LIMIT/MARKET")
    if req.qty <= 0:
        raise Reject(Reject.INVALID_ORDER, "qty must be positive")
    if req.order_type == "LIMIT" and (req.price is None or req.price <= 0):
        raise Reject(Reject.INVALID_ORDER, "limit price must be positive")
    account = _lock_account(conn, req.account_id, config.auto_open_accounts() if auto_open is None else auto_open)
    if conn.execute(
        "SELECT 1 FROM portfolio.reservations WHERE symbol = %s AND order_id = %s", (req.symbol, req.order_id)
    ).fetchone():
        raise Reject(Reject.DUPLICATE_ORDER_ID, f"order {req.order_id} already exists")

    available_cash = account["cash"] - account["reserved_cash"]
    reserved_cash = reserved_shares = 0
    limit_price = req.price if req.order_type == "LIMIT" else None
    if req.side == "BUY":
        if limit_price is None:
            limit_price = _market_protection(conn, req)
        reserved_cash = _mul(limit_price, req.qty)
        if reserved_cash > available_cash:
            raise Reject(
                Reject.INSUFFICIENT_FUNDS, f"needs {reserved_cash} ticks but only {available_cash} available"
            )
        conn.execute(
            "UPDATE portfolio.accounts SET reserved_cash = reserved_cash + %s, updated_at = now() "
            "WHERE account_id = %s",
            (reserved_cash, req.account_id),
        )
        available_cash -= reserved_cash
        position = _lock_position(conn, req.account_id, req.symbol)
        available_shares = position["shares"] - position["reserved_shares"]
    else:
        position = _lock_position(conn, req.account_id, req.symbol)
        available_shares = position["shares"] - position["reserved_shares"]
        if req.qty > available_shares:
            raise Reject(
                Reject.NO_SHORTING,
                f"selling {req.qty} {req.symbol} but only {available_shares} available (no short selling)",
            )
        reserved_shares = req.qty
        conn.execute(
            "UPDATE portfolio.positions SET reserved_shares = reserved_shares + %s, updated_at = now() "
            "WHERE account_id = %s AND symbol = %s",
            (reserved_shares, req.account_id, req.symbol),
        )
        available_shares -= reserved_shares
    conn.execute(
        "INSERT INTO portfolio.reservations (symbol, order_id, account_id, side, order_type, tif, limit_price, "
        "open_qty, reserved_cash, reserved_shares, status) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'PENDING')",
        (req.symbol, req.order_id, req.account_id, req.side, req.order_type, req.tif, limit_price, req.qty,
         reserved_cash, reserved_shares),
    )
    return Approval(req.order_id, reserved_cash, reserved_shares, limit_price, available_cash, available_shares)


def _market_protection(conn: psycopg.Connection, req: OrderCheck) -> int:
    mark = conn.execute("SELECT last_price FROM portfolio.marks WHERE symbol = %s", (req.symbol,)).fetchone()
    anchors = [p for p in (mark and mark["last_price"], req.reference_price) if p and p > 0]
    if not anchors:
        raise Reject(
            Reject.NO_REFERENCE_PRICE,
            f"no last trade or reference price for {req.symbol}; send a limit order",
        )
    return protection_price(max(anchors), config.market_collar_percent())


def _lock_own_live_order(conn: psycopg.Connection, account_id: int, symbol: str, order_id: int) -> dict:
    res = conn.execute(
        "SELECT * FROM portfolio.reservations WHERE symbol = %s AND order_id = %s FOR UPDATE", (symbol, order_id)
    ).fetchone()
    if res is None or res["account_id"] != account_id or res["status"] not in LIVE:
        raise Reject(Reject.NOT_OWN_OPEN_ORDER, f"order {order_id} is not one of your open {symbol} orders")
    return res


def check_amend(
    conn: psycopg.Connection, account_id: int, symbol: str, order_id: int, new_price: int | None,
    new_qty: int | None,
) -> Approval:
    """Reserves for an amend until the engine confirms (``OrderAmended``) or rejects it. Raises :class:`Reject`."""
    _check_symbol(conn, symbol)
    if (new_price is not None and new_price <= 0) or (new_qty is not None and new_qty <= 0):
        raise Reject(Reject.INVALID_ORDER, "new price and qty must be positive")
    account = _lock_account(conn, account_id, auto_open=False)
    position = _lock_position(conn, account_id, symbol)
    res = _lock_own_live_order(conn, account_id, symbol, order_id)
    if res["amend_pending"]:
        raise Reject(Reject.AMEND_PENDING, f"order {order_id} already has an amend in flight")
    if res["limit_price"] is None or res["order_type"] != "LIMIT":
        raise Reject(Reject.INVALID_ORDER, "only resting limit orders can be amended")
    available_cash = account["cash"] - account["reserved_cash"]
    available_shares = position["shares"] - position["reserved_shares"]
    pending_cash = pending_shares = 0
    price = new_price if new_price is not None else res["limit_price"]
    if res["side"] == "BUY":
        if new_qty is None:
            pending_cash = max(0, _mul(price, res["open_qty"]) - res["reserved_cash"])
        else:
            pending_cash = _mul(price, new_qty)
        if pending_cash > available_cash:
            raise Reject(
                Reject.INSUFFICIENT_FUNDS, f"amend needs {pending_cash} more ticks but only {available_cash} available"
            )
        available_cash -= pending_cash
    else:
        pending_shares = new_qty or 0
        if pending_shares > available_shares:
            raise Reject(
                Reject.NO_SHORTING,
                f"amend needs {pending_shares} {symbol} held until confirmed but only {available_shares} available",
            )
        available_shares -= pending_shares
    conn.execute(
        "UPDATE portfolio.accounts SET reserved_cash = reserved_cash + %s, updated_at = now() WHERE account_id = %s",
        (pending_cash, account_id),
    )
    conn.execute(
        "UPDATE portfolio.positions SET reserved_shares = reserved_shares + %s, updated_at = now() "
        "WHERE account_id = %s AND symbol = %s",
        (pending_shares, account_id, symbol),
    )
    conn.execute(
        "UPDATE portfolio.reservations SET amend_pending = true, pending_cash = %s, pending_shares = %s, "
        "updated_at = now() WHERE symbol = %s AND order_id = %s",
        (pending_cash, pending_shares, symbol, order_id),
    )
    return Approval(order_id, pending_cash, pending_shares, price, available_cash, available_shares)


def check_cancel(conn: psycopg.Connection, account_id: int, symbol: str, order_id: int) -> None:
    """Ownership check only; the reservation is released when the engine reports ``OrderCancelled``."""
    _check_symbol(conn, symbol)
    _lock_own_live_order(conn, account_id, symbol, order_id)


def release(conn: psycopg.Connection, account_id: int, symbol: str, order_id: int, kind: str) -> Approval:
    """Undoes a check whose command never reached the engine. ``kind`` is ``place`` or ``amend``."""
    account = _lock_account(conn, account_id, auto_open=False)
    position = _lock_position(conn, account_id, symbol)
    res = conn.execute(
        "SELECT * FROM portfolio.reservations WHERE symbol = %s AND order_id = %s FOR UPDATE", (symbol, order_id)
    ).fetchone()
    if res is None or res["account_id"] != account_id:
        raise Reject(Reject.NOT_OWN_OPEN_ORDER, f"order {order_id} is not yours")
    if kind == "place":
        if res["status"] != "PENDING":
            raise Reject(Reject.NOTHING_TO_RELEASE, f"order {order_id} is {res['status']}, not PENDING")
        cash = res["reserved_cash"] + res["pending_cash"]
        shares = res["reserved_shares"] + res["pending_shares"]
        status = "RELEASED"
    elif kind == "amend":
        if not res["amend_pending"]:
            raise Reject(Reject.NOTHING_TO_RELEASE, f"order {order_id} has no amend in flight")
        cash, shares, status = res["pending_cash"], res["pending_shares"], res["status"]
    else:
        raise Reject(Reject.INVALID_ORDER, "kind must be 'place' or 'amend'")
    _apply_release(conn, account_id, symbol, cash, shares)
    if kind == "place":
        conn.execute(
            "UPDATE portfolio.reservations SET status = %s, reserved_cash = 0, reserved_shares = 0, pending_cash = 0,"
            " pending_shares = 0, amend_pending = false, updated_at = now() WHERE symbol = %s AND order_id = %s",
            (status, symbol, order_id),
        )
    else:
        conn.execute(
            "UPDATE portfolio.reservations SET pending_cash = 0, pending_shares = 0, amend_pending = false, "
            "updated_at = now() WHERE symbol = %s AND order_id = %s",
            (symbol, order_id),
        )
    return Approval(
        order_id, cash, shares, res["limit_price"],
        account["cash"] - account["reserved_cash"] + cash,
        position["shares"] - position["reserved_shares"] + shares,
    )


def _apply_release(conn: psycopg.Connection, account_id: int, symbol: str, cash: int, shares: int) -> None:
    if cash:
        conn.execute(
            "UPDATE portfolio.accounts SET reserved_cash = reserved_cash - %s, updated_at = now() "
            "WHERE account_id = %s",
            (cash, account_id),
        )
    if shares:
        conn.execute(
            "UPDATE portfolio.positions SET reserved_shares = reserved_shares - %s, updated_at = now() "
            "WHERE account_id = %s AND symbol = %s",
            (shares, account_id, symbol),
        )


# ---- settlement (portfolio consumer) -------------------------------------------------------------------------------


@dataclass(frozen=True)
class Placed:
    order_id: int


@dataclass(frozen=True)
class Rejected:
    order_id: int
    reason: str


@dataclass(frozen=True)
class Cancelled:
    order_id: int
    qty: int
    reason: str = "REQUESTED"


@dataclass(frozen=True)
class Amended:
    order_id: int
    new_price: int
    new_qty: int


@dataclass(frozen=True)
class Trade:
    maker_order_id: int
    taker_order_id: int
    price: int
    qty: int


EngineEvent = Placed | Rejected | Cancelled | Amended | Trade


@dataclass
class _Batch:
    accounts: dict[int, dict] = field(default_factory=dict)
    positions: dict[int, dict] = field(default_factory=dict)
    reservations: dict[int, dict] = field(default_factory=dict)
    fills: list[tuple] = field(default_factory=list)
    mark: int | None = None


def _order_ids(events: Iterable[EngineEvent]) -> set[int]:
    ids: set[int] = set()
    for e in events:
        if isinstance(e, Trade):
            ids.update((e.maker_order_id, e.taker_order_id))
        else:
            ids.add(e.order_id)
    return ids


def settle(conn: psycopg.Connection, symbol: str, events: list[EngineEvent], seq: int | None = None) -> None:
    """Applies one engine batch for ``symbol``: marks, fills, cancels, amends and rejections of reserved orders.

    Orders the ledger never reserved (e.g. from producers that bypass risk) are ignored, except that their trades
    still move the symbol's mark.
    """
    ids = sorted(_order_ids(events))
    batch = _Batch()
    if ids:
        owners = conn.execute(
            "SELECT DISTINCT account_id FROM portfolio.reservations WHERE symbol = %s AND order_id = ANY(%s) "
            "ORDER BY account_id",
            (symbol, ids),
        ).fetchall()
        account_ids = [r["account_id"] for r in owners]
        for row in conn.execute(
            "SELECT * FROM portfolio.accounts WHERE account_id = ANY(%s) ORDER BY account_id FOR UPDATE",
            (account_ids,),
        ):
            batch.accounts[row["account_id"]] = dict(row)
        for account_id in account_ids:
            batch.positions[account_id] = dict(_lock_position(conn, account_id, symbol))
        for row in conn.execute(
            "SELECT * FROM portfolio.reservations WHERE symbol = %s AND order_id = ANY(%s) ORDER BY order_id "
            "FOR UPDATE",
            (symbol, ids),
        ):
            batch.reservations[row["order_id"]] = dict(row)

    for event in events:
        _apply(batch, event, seq)

    for a in batch.accounts.values():
        if a["cash"] < 0 or a["reserved_cash"] < 0 or a["reserved_cash"] > a["cash"]:
            raise InvariantBreach(f"account {a['account_id']} would break cash invariants: {a}")
        conn.execute(
            "UPDATE portfolio.accounts SET cash = %(cash)s, reserved_cash = %(reserved_cash)s, updated_at = now() "
            "WHERE account_id = %(account_id)s",
            a,
        )
    for p in batch.positions.values():
        if p["shares"] < 0 or p["reserved_shares"] < 0 or p["reserved_shares"] > p["shares"]:
            raise InvariantBreach(f"position {p['account_id']}/{symbol} would break share invariants: {p}")
        conn.execute(
            "UPDATE portfolio.positions SET shares = %(shares)s, reserved_shares = %(reserved_shares)s, "
            "cost_basis = %(cost_basis)s, realized_pnl = %(realized_pnl)s, updated_at = now() "
            "WHERE account_id = %(account_id)s AND symbol = %(symbol)s",
            p,
        )
    for r in batch.reservations.values():
        conn.execute(
            "UPDATE portfolio.reservations SET status = %(status)s, limit_price = %(limit_price)s, "
            "open_qty = %(open_qty)s, filled_qty = %(filled_qty)s, reserved_cash = %(reserved_cash)s, "
            "reserved_shares = %(reserved_shares)s, amend_pending = %(amend_pending)s, "
            "pending_cash = %(pending_cash)s, pending_shares = %(pending_shares)s, updated_at = now() "
            "WHERE symbol = %(symbol)s AND order_id = %(order_id)s",
            r,
        )
    if batch.fills:
        with conn.cursor() as cur:
            cur.executemany(
                "INSERT INTO portfolio.fills (account_id, symbol, order_id, side, price, qty, seq) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                batch.fills,
            )
    if batch.mark is not None:
        conn.execute(
            "INSERT INTO portfolio.marks (symbol, last_price) VALUES (%s, %s) ON CONFLICT (symbol) "
            "DO UPDATE SET last_price = EXCLUDED.last_price, updated_at = now()",
            (symbol, batch.mark),
        )


def _live(batch: _Batch, order_id: int) -> dict | None:
    res = batch.reservations.get(order_id)
    return res if res is not None and res["status"] in LIVE else None


def _release_all(batch: _Batch, res: dict, status: str) -> None:
    batch.accounts[res["account_id"]]["reserved_cash"] -= res["reserved_cash"] + res["pending_cash"]
    batch.positions[res["account_id"]]["reserved_shares"] -= res["reserved_shares"] + res["pending_shares"]
    res.update(status=status, reserved_cash=0, reserved_shares=0, pending_cash=0, pending_shares=0,
               amend_pending=False)


def _release_pending(batch: _Batch, res: dict) -> None:
    batch.accounts[res["account_id"]]["reserved_cash"] -= res["pending_cash"]
    batch.positions[res["account_id"]]["reserved_shares"] -= res["pending_shares"]
    res.update(pending_cash=0, pending_shares=0, amend_pending=False)


def _apply(batch: _Batch, event: EngineEvent, seq: int | None) -> None:
    if isinstance(event, Trade):
        batch.mark = event.price
        _fill(batch, event.maker_order_id, event, seq)
        _fill(batch, event.taker_order_id, event, seq)
        return
    res = _live(batch, event.order_id)
    if res is None:
        return
    if isinstance(event, Placed):
        res["status"] = "OPEN"
    elif isinstance(event, Rejected):
        if res["status"] == "PENDING":
            _release_all(batch, res, "REJECTED")
        elif res["amend_pending"]:
            _release_pending(batch, res)
    elif isinstance(event, Cancelled):
        qty = min(event.qty, res["open_qty"])
        res["open_qty"] -= qty
        if res["open_qty"] == 0:
            _release_all(batch, res, "CANCELLED")
        elif res["side"] == "BUY":
            release = min(res["reserved_cash"], res["limit_price"] * qty)
            res["reserved_cash"] -= release
            batch.accounts[res["account_id"]]["reserved_cash"] -= release
        else:
            res["reserved_shares"] -= qty
            batch.positions[res["account_id"]]["reserved_shares"] -= qty
    elif isinstance(event, Amended):
        _confirm_amend(batch, res, event)


def _confirm_amend(batch: _Batch, res: dict, event: Amended) -> None:
    account = batch.accounts[res["account_id"]]
    position = batch.positions[res["account_id"]]
    if res["side"] == "BUY":
        target = event.new_price * event.new_qty
        held = res["reserved_cash"] + res["pending_cash"]
        if target > held and target - held > account["cash"] - account["reserved_cash"]:
            raise InvariantBreach(f"amend of order {res['order_id']} needs {target - held} unreserved ticks")
        account["reserved_cash"] += target - held
        res["reserved_cash"] = target
    else:
        held = res["reserved_shares"] + res["pending_shares"]
        if event.new_qty > held and event.new_qty - held > position["shares"] - position["reserved_shares"]:
            raise InvariantBreach(f"amend of order {res['order_id']} needs {event.new_qty - held} unreserved shares")
        position["reserved_shares"] += event.new_qty - held
        res["reserved_shares"] = event.new_qty
    res.update(limit_price=event.new_price, open_qty=event.new_qty, pending_cash=0, pending_shares=0,
               amend_pending=False)


def _fill(batch: _Batch, order_id: int, trade: Trade, seq: int | None) -> None:
    res = _live(batch, order_id)
    if res is None:
        return
    account = batch.accounts[res["account_id"]]
    position = batch.positions[res["account_id"]]
    qty = trade.qty
    if qty > res["open_qty"]:
        raise InvariantBreach(f"fill of {qty} exceeds open qty {res['open_qty']} of order {order_id}")
    value = trade.price * qty
    if res["side"] == "BUY":
        release = res["limit_price"] * qty
        if trade.price > res["limit_price"] or release > res["reserved_cash"]:
            raise InvariantBreach(f"fill of order {order_id} at {trade.price} exceeds its reservation")
        account["cash"] -= value
        account["reserved_cash"] -= release
        res["reserved_cash"] -= release
        position["shares"] += qty
        position["cost_basis"] += value
    else:
        if qty > res["reserved_shares"]:
            raise InvariantBreach(f"fill of order {order_id} exceeds its reserved shares")
        sold_cost = position["cost_basis"] * qty // position["shares"]
        position["cost_basis"] -= sold_cost
        position["realized_pnl"] += value - sold_cost
        position["shares"] -= qty
        position["reserved_shares"] -= qty
        res["reserved_shares"] -= qty
        account["cash"] += value
    res["open_qty"] -= qty
    res["filled_qty"] += qty
    batch.fills.append((res["account_id"], res["symbol"], order_id, res["side"], trade.price, qty, seq))
    if res["open_qty"] == 0:
        _release_all(batch, res, "FILLED")


# ---- queries -------------------------------------------------------------------------------------------------------


def account_view(conn: psycopg.Connection, account_id: int) -> dict | None:
    """Balances, positions with average-cost P&L marked at each symbol's last trade, and live reservations."""
    account = conn.execute("SELECT * FROM portfolio.accounts WHERE account_id = %s", (account_id,)).fetchone()
    if account is None:
        return None
    positions = []
    equity = account["cash"]
    realized = unrealized = 0
    for p in conn.execute(
        "SELECT p.*, m.last_price FROM portfolio.positions p LEFT JOIN portfolio.marks m USING (symbol) "
        "WHERE p.account_id = %s AND (p.shares > 0 OR p.realized_pnl <> 0 OR p.reserved_shares > 0) "
        "ORDER BY p.symbol",
        (account_id,),
    ):
        mark = p["last_price"]
        market_value = p["shares"] * mark if mark is not None else None
        upnl = market_value - p["cost_basis"] if market_value is not None else 0
        equity += market_value or 0
        realized += p["realized_pnl"]
        unrealized += upnl
        positions.append({
            "symbol": p["symbol"],
            "shares": p["shares"],
            "reservedShares": p["reserved_shares"],
            "availableShares": p["shares"] - p["reserved_shares"],
            "costBasis": p["cost_basis"],
            "avgCost": round(p["cost_basis"] / p["shares"]) if p["shares"] else None,
            "markPrice": mark,
            "marketValue": market_value,
            "realizedPnl": p["realized_pnl"],
            "unrealizedPnl": upnl,
        })
    orders = [
        {
            "symbol": r["symbol"], "orderId": r["order_id"], "side": r["side"], "type": r["order_type"],
            "tif": r["tif"], "limitPrice": r["limit_price"], "openQty": r["open_qty"], "filledQty": r["filled_qty"],
            "reservedCash": r["reserved_cash"] + r["pending_cash"],
            "reservedShares": r["reserved_shares"] + r["pending_shares"],
            "status": r["status"], "amendPending": r["amend_pending"],
        }
        for r in conn.execute(
            "SELECT * FROM portfolio.reservations WHERE account_id = %s AND status IN ('PENDING', 'OPEN') "
            "ORDER BY created_at, symbol, order_id",
            (account_id,),
        )
    ]
    return {
        "accountId": account_id,
        "cash": account["cash"],
        "reservedCash": account["reserved_cash"],
        "availableCash": account["cash"] - account["reserved_cash"],
        "startingCash": account["starting_cash"],
        "equity": equity,
        "realizedPnl": realized,
        "unrealizedPnl": unrealized,
        "sessionPnl": realized + unrealized,
        "positions": positions,
        "openOrders": orders,
    }


def invariant_violations(conn: psycopg.Connection) -> list[str]:
    """Cross-checks balances against live reservations; empty when the ledger is consistent."""
    problems = [
        f"account {r['account_id']}: reserved_cash {r['reserved_cash']} != live reservations {r['held']}"
        for r in conn.execute(
            "SELECT a.account_id, a.reserved_cash, COALESCE(SUM(r.reserved_cash + r.pending_cash), 0) AS held "
            "FROM portfolio.accounts a LEFT JOIN portfolio.reservations r "
            "ON r.account_id = a.account_id AND r.status IN ('PENDING', 'OPEN') "
            "GROUP BY a.account_id, a.reserved_cash HAVING a.reserved_cash <> COALESCE(SUM(r.reserved_cash + "
            "r.pending_cash), 0)"
        )
    ]
    problems += [
        f"position {r['account_id']}/{r['symbol']}: reserved_shares {r['reserved_shares']} != {r['held']}"
        for r in conn.execute(
            "SELECT p.account_id, p.symbol, p.reserved_shares, COALESCE(SUM(r.reserved_shares + r.pending_shares), 0)"
            " AS held FROM portfolio.positions p LEFT JOIN portfolio.reservations r ON r.account_id = p.account_id "
            "AND r.symbol = p.symbol AND r.status IN ('PENDING', 'OPEN') GROUP BY p.account_id, p.symbol, "
            "p.reserved_shares HAVING p.reserved_shares <> COALESCE(SUM(r.reserved_shares + r.pending_shares), 0)"
        )
    ]
    problems += [
        f"negative balance: {dict(r)}"
        for r in conn.execute(
            "SELECT account_id, cash, reserved_cash FROM portfolio.accounts "
            "WHERE cash < 0 OR reserved_cash < 0 OR reserved_cash > cash"
        )
    ]
    problems += [
        f"negative position: {dict(r)}"
        for r in conn.execute(
            "SELECT account_id, symbol, shares, reserved_shares FROM portfolio.positions "
            "WHERE shares < 0 OR reserved_shares < 0 OR reserved_shares > shares"
        )
    ]
    return problems
