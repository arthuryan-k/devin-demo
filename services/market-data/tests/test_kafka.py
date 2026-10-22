"""KafkaSource against a real broker: `KAFKA_BROKERS=localhost:19092 pytest`."""

import asyncio
import json
import os
import time

import pytest

from market_data.hub import FeedHub, topic_for
from market_data.source import KafkaSource

BROKERS = os.environ.get("KAFKA_BROKERS")
pytestmark = pytest.mark.skipif(not BROKERS, reason="KAFKA_BROKERS not set")


async def until(cond, timeout=20.0):
    deadline = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > deadline:
            raise AssertionError("timed out")
        await asyncio.sleep(0.05)


async def test_consumes_replays_and_resyncs_from_kafka():
    from aiokafka import AIOKafkaProducer

    symbol = f"MD{int(time.time() * 1000):X}"
    producer = AIOKafkaProducer(bootstrap_servers=BROKERS, enable_idempotence=True)
    await producer.start()

    async def send(msg):
        await producer.send_and_wait(topic_for(symbol), json.dumps(msg).encode(), key=symbol.encode())

    hub = FeedHub([(symbol, "0.01")])
    source = KafkaSource(hub, BROKERS)
    try:
        await send({"type": "delta", "seq": 1, "levels": [["a", "B", 10000, 1, 1]], "events": []})
        await send({"type": "delta", "seq": 2, "levels": [["a", "S", 10100, 2, 1]], "events": []})
        await source.start()
        feed = hub.feed(symbol)
        await until(lambda: feed.seq == 2)
        sub = feed.subscribe()
        assert (await sub.queue.get())["seq"] == 2

        # seq 3 is missing from the log itself: the replay cannot fill it, so the feed waits for a snapshot.
        await send({"type": "delta", "seq": 4, "levels": [["r", "S", 10100, 0, 0]], "events": []})
        await until(lambda: feed.gaps >= 2)
        assert not feed.ready
        await send({"type": "snapshot", "seq": 4, "reason": "engine", "bids": [[10000, 1, 1]], "asks": [],
                    "trades": [], "events": []})
        await until(lambda: feed.ready and feed.seq == 4)
        resync = await asyncio.wait_for(sub.queue.get(), 5)
        assert (resync["type"], resync["reason"], resync["seq"]) == ("snapshot", "resync", 4)
        await send({"type": "delta", "seq": 5, "levels": [["u", "B", 10000, 3, 2]], "events": []})
        nxt = await asyncio.wait_for(sub.queue.get(), 10)
        assert (nxt["type"], nxt["seq"], nxt["levels"]) == ("delta", 5, [["u", "B", "100.00", 3, 2]])
    finally:
        await source.stop()
        await producer.stop()
