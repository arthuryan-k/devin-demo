"""Decoding of ``events.<symbol>`` messages into ledger events.

A message is one engine batch: ``{"seq": N, "symbol": "ACME", "events": [ ... ]}``. A message holding one event
with ``seq``/``symbol`` alongside it is also accepted. Event objects carry a ``type`` matching the engine's
``Event`` records (``OrderPlaced``, ``OrderRejected``, ``OrderCancelled``, ``OrderAmended``, ``TradeExecuted``) and
their fields in camelCase; a trade may be nested under ``"trade"`` or flattened.
"""

from __future__ import annotations

import json
from dataclasses import dataclass

from portfolio.ledger import Amended, Cancelled, EngineEvent, Placed, Rejected, Trade


@dataclass(frozen=True)
class Batch:
    symbol: str | None
    seq: int | None
    events: list[EngineEvent]


def _kind(raw: dict) -> str:
    kind = str(raw.get("type") or raw.get("event") or "")
    kind = "".join(ch for ch in kind.lower() if ch.isalnum())
    return kind[len("order") :] if kind.startswith("order") else kind


def _get(raw: dict, *names: str):
    for name in names:
        if name in raw and raw[name] is not None:
            return raw[name]
    return None


def decode_event(raw: dict) -> EngineEvent | None:
    kind = _kind(raw)
    order_id = _get(raw, "orderId", "order_id", "id")
    if kind == "placed":
        return Placed(int(order_id))
    if kind == "rejected":
        return Rejected(int(order_id), str(_get(raw, "reason") or ""))
    if kind == "cancelled":
        return Cancelled(
            int(order_id),
            int(_get(raw, "cancelledQty", "cancelled_qty", "qty")),
            str(_get(raw, "reason") or "REQUESTED"),
        )
    if kind == "amended":
        return Amended(int(order_id), int(_get(raw, "newPrice", "new_price")), int(_get(raw, "newQty", "new_qty")))
    if kind in ("tradeexecuted", "trade"):
        t = raw.get("trade") if isinstance(raw.get("trade"), dict) else raw
        return Trade(
            int(_get(t, "makerOrderId", "maker_order_id")),
            int(_get(t, "takerOrderId", "taker_order_id")),
            int(_get(t, "price")),
            int(_get(t, "qty", "quantity")),
        )
    return None


def decode(value: bytes | str | dict, topic: str | None = None) -> Batch:
    msg = json.loads(value) if isinstance(value, bytes | str) else value
    raw_events = msg["events"] if isinstance(msg.get("events"), list) else [msg]
    symbol = msg.get("symbol")
    if symbol is None and topic and topic.startswith("events."):
        symbol = topic[len("events.") :]
    seq = msg.get("seq")
    decoded = [e for e in (decode_event(r) for r in raw_events) if e is not None]
    return Batch(symbol, int(seq) if seq is not None else None, decoded)
