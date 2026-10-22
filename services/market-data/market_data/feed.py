"""One symbol's L2 book replica, rebuilt from the bus, and its WebSocket subscribers.

Upstream (`marketdata.<symbol>`) carries the engine's feed: `snapshot` and `delta` messages with a per-symbol `seq`,
integer-tick prices and the same shapes as the Java SSE feed. A feed applies deltas only when `seq` is exactly
`last + 1`; a delta with seq 0 (the engine's first command) on an empty replica starts from an empty book. Anything else is an upstream gap: the
feed asks its source to replay from the last snapshot (or the start of the log), applies the replay silently and,
once it has caught up past the gap, sends every subscriber a fresh snapshot (`reason: "resync"`).

Downstream, each subscriber has a bounded queue. Its first message is a snapshot taken synchronously with
registration on the event loop, so deltas after snapshot `S` are `S+1, S+2, ...` with no holes. A subscriber that
falls `max_backlog` messages behind has its backlog replaced by one snapshot (`reason: "slow-consumer"`).
"""

from __future__ import annotations

import asyncio
import logging
from collections import deque
from typing import Callable, Optional

from .prices import PriceFormat

log = logging.getLogger(__name__)

RECENT = 50


class Subscriber:
    def __init__(self, fmt: PriceFormat, max_backlog: int):
        self.fmt = fmt
        self.queue: asyncio.Queue[dict] = asyncio.Queue(maxsize=max_backlog)
        self.snapshots_sent = 0


FIRST_SEQ = 0  # events.<symbol>/marketdata.<symbol> seqs are contiguous from 0; a pre-trading snapshot has seq -1


class SymbolFeed:
    def __init__(
        self,
        symbol: str,
        tick_size: str = "0.01",
        max_backlog: int = 1024,
        replay: Optional[Callable[[str, int], None]] = None,
    ):
        self.symbol = symbol
        self.tick_size = tick_size
        self.max_backlog = max_backlog
        self.replay = replay
        self.seq: Optional[int] = None
        self.bids: dict[int, tuple[int, int]] = {}
        self.asks: dict[int, tuple[int, int]] = {}
        self.trades: deque[dict] = deque(maxlen=RECENT)
        self.events: deque[dict] = deque(maxlen=RECENT)
        self.subscribers: set[Subscriber] = set()
        self.snapshot_offset = 0
        self.resync_target: Optional[int] = None
        self.gaps = 0

    # ---- upstream ---------------------------------------------------------------------------------------------

    def apply(self, msg: dict, offset: int = -1) -> None:
        kind = msg.get("type")
        if msg.get("symbol") not in (None, self.symbol):
            log.warning("%s: dropping message for %s", self.symbol, msg.get("symbol"))
            return
        seq = msg.get("seq")
        if not isinstance(seq, int):
            log.warning("%s: dropping message without seq: %s", self.symbol, kind)
            return
        if kind == "snapshot":
            if self.seq is not None and seq <= self.seq and self.resync_target is None:
                return
            self._load(msg)
            if offset >= 0:
                self.snapshot_offset = offset
            self._after_advance(reason=str(msg.get("reason", "snapshot")))
        elif kind == "delta":
            expected = FIRST_SEQ if self.seq is None else self.seq + 1
            if seq < expected:
                return
            if seq > expected:
                self._gap(expected, seq)
                return
            self._apply_delta(msg)
            self._after_advance(reason=None, delta=msg)
        else:
            log.debug("%s: ignoring %s", self.symbol, kind)

    def _gap(self, expected: int, got: int) -> None:
        self.gaps += 1
        if self.resync_target is not None:
            if self.seq is None:
                return  # replay requested but not started yet: these records are redelivered by the replay
            # Still a hole after replaying the retained log: nothing older to rebuild from, wait for a snapshot.
            log.error("%s: seq %d missing from the log (got %d); waiting for an upstream snapshot",
                      self.symbol, expected, got)
            return
        log.warning("%s: upstream gap, expected seq %d got %d; replaying from offset %d",
                    self.symbol, expected, got, self.snapshot_offset)
        self.resync_target = got
        self._reset()
        if self.replay is not None:
            self.replay(self.symbol, self.snapshot_offset)

    def _after_advance(self, reason: Optional[str], delta: Optional[dict] = None) -> None:
        if self.resync_target is not None:
            if self.seq is not None and self.seq >= self.resync_target:
                self.resync_target = None
                self._broadcast_snapshot("resync")
            return
        if delta is None:
            self._broadcast_snapshot(reason or "snapshot")
        else:
            self._broadcast_delta(delta)

    def _reset(self) -> None:
        self.seq = None
        self.bids.clear()
        self.asks.clear()
        self.trades.clear()
        self.events.clear()

    def _load(self, msg: dict) -> None:
        self.seq = msg["seq"]
        self.bids = {int(p): (int(q), int(n)) for p, q, n in msg.get("bids", [])}
        self.asks = {int(p): (int(q), int(n)) for p, q, n in msg.get("asks", [])}
        self.trades = deque(msg.get("trades", []), maxlen=RECENT)
        self.events = deque(msg.get("events", []), maxlen=RECENT)

    def _apply_delta(self, msg: dict) -> None:
        for op, side, price, qty, orders in msg.get("levels", []):
            book = self.bids if side == "B" else self.asks
            if op == "r":
                book.pop(price, None)
            else:
                book[price] = (qty, orders)
        for e in msg.get("events", []):
            (self.trades if e.get("type") == "TradeExecuted" else self.events).append(e)
        self.seq = msg["seq"]

    # ---- downstream -------------------------------------------------------------------------------------------

    @property
    def ready(self) -> bool:
        return self.seq is not None and self.resync_target is None

    def snapshot(self, fmt: PriceFormat, reason: str = "subscribe") -> dict:
        def levels(book: dict[int, tuple[int, int]], descending: bool):
            return [[fmt.price(p), q, n] for p, (q, n) in sorted(book.items(), reverse=descending)]

        return {
            "type": "snapshot",
            "symbol": self.symbol,
            "seq": self.seq if self.seq is not None else 0,
            "reason": reason,
            "bids": levels(self.bids, True),
            "asks": levels(self.asks, False),
            "trades": [fmt.event(t) for t in self.trades],
            "events": [fmt.event(e) for e in self.events],
        }

    def subscribe(self, decimal: bool = True) -> Subscriber:
        sub = Subscriber(PriceFormat(self.tick_size, decimal), self.max_backlog)
        self.subscribers.add(sub)
        if self.ready or self.seq is None and self.resync_target is None:
            self._offer(sub, self.snapshot(sub.fmt, "subscribe"))
        # Otherwise mid-resync: the "resync" snapshot is the first thing this subscriber gets.
        return sub

    def unsubscribe(self, sub: Subscriber) -> None:
        self.subscribers.discard(sub)

    def request_snapshot(self, sub: Subscriber) -> None:
        if self.ready:
            self._replace_backlog(sub, "requested")

    def _broadcast_snapshot(self, reason: str) -> None:
        for sub in list(self.subscribers):
            self._replace_backlog(sub, reason)

    def _broadcast_delta(self, delta: dict) -> None:
        for sub in list(self.subscribers):
            fmt = sub.fmt
            out = {
                "type": "delta",
                "symbol": self.symbol,
                "seq": delta["seq"],
                "levels": [[op, side, fmt.price(p), q, n] for op, side, p, q, n in delta.get("levels", [])],
                "events": [fmt.event(e) for e in delta.get("events", [])],
            }
            if sub.queue.full():
                self._replace_backlog(sub, "slow-consumer")
            else:
                sub.queue.put_nowait(out)

    def _replace_backlog(self, sub: Subscriber, reason: str) -> None:
        while not sub.queue.empty():
            sub.queue.get_nowait()
        self._offer(sub, self.snapshot(sub.fmt, reason))

    @staticmethod
    def _offer(sub: Subscriber, msg: dict) -> None:
        if msg["type"] == "snapshot":
            sub.snapshots_sent += 1
        sub.queue.put_nowait(msg)
