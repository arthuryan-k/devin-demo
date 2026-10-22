from __future__ import annotations

import json
import logging
from typing import Callable, Iterable, Optional

from .feed import SymbolFeed

log = logging.getLogger(__name__)

TOPIC_PREFIX = "marketdata."


def topic_for(symbol: str) -> str:
    return TOPIC_PREFIX + symbol


def symbol_for(topic: str) -> Optional[str]:
    return topic[len(TOPIC_PREFIX):] if topic.startswith(TOPIC_PREFIX) else None


class FeedHub:
    """All symbol feeds. Each symbol has its own book, sequence and subscriber set; nothing is shared across them."""

    def __init__(self, instruments: Iterable[tuple[str, str]], max_backlog: int = 1024):
        self.replay: Optional[Callable[[str, int], None]] = None
        self.feeds = {
            symbol: SymbolFeed(symbol, tick, max_backlog, replay=self._replay)
            for symbol, tick in instruments
        }

    def _replay(self, symbol: str, offset: int) -> None:
        if self.replay is not None:
            self.replay(symbol, offset)

    def feed(self, symbol: str) -> Optional[SymbolFeed]:
        return self.feeds.get(symbol.upper())

    def ingest(self, topic: str, value: bytes | str | dict, offset: int = -1) -> None:
        symbol = symbol_for(topic)
        feed = self.feeds.get(symbol) if symbol else None
        if feed is None:
            log.warning("no feed for topic %s", topic)
            return
        try:
            msg = value if isinstance(value, dict) else json.loads(value)
        except ValueError:
            log.warning("%s: undecodable message at offset %d", topic, offset)
            return
        feed.apply(msg, offset)
