import pytest
from fastapi.testclient import TestClient

from market_data.app import create_app
from market_data.hub import FeedHub
from market_data.source import InMemorySource

INSTRUMENTS = [("ACME", "0.01"), ("GLOBX", "0.05")]


def delta(seq, levels=(), events=()):
    return {"type": "delta", "seq": seq, "levels": [list(l) for l in levels], "events": list(events)}


def snapshot(seq, bids=(), asks=(), reason="engine"):
    return {"type": "snapshot", "seq": seq, "reason": reason, "bids": [list(b) for b in bids],
            "asks": [list(a) for a in asks], "trades": [], "events": []}


class Exchange:
    """Test client plus an in-memory `marketdata.<symbol>` log, appended to on the app's event loop."""

    def __init__(self, client: TestClient, source: InMemorySource):
        self.client = client
        self.source = source

    def publish(self, symbol, msg, deliver=True):
        return self.client.portal.call(self.source.append, symbol, msg, deliver)

    def settle(self):
        """Let call_soon'd replays run."""
        import asyncio

        self.client.portal.call(asyncio.sleep, 0.01)


@pytest.fixture
def exchange():
    hub = FeedHub(INSTRUMENTS, max_backlog=8)
    source = InMemorySource(hub)
    with TestClient(create_app(hub)) as client:
        yield Exchange(client, source)
