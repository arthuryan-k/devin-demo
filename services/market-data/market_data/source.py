"""Bus sources: aiokafka for real deployments, an in-memory log for tests. Both support replay from an offset."""

from __future__ import annotations

import asyncio
import json
import logging

from .hub import FeedHub, topic_for

log = logging.getLogger(__name__)


class InMemorySource:
    """Append-only per-topic logs pumped into the hub; `replay` re-delivers from an offset like a Kafka seek."""

    def __init__(self, hub: FeedHub):
        self.hub = hub
        self.logs: dict[str, list[str]] = {}
        self.dropped: set[tuple[str, int]] = set()
        hub.replay = self._replay

    def append(self, symbol: str, msg: dict, deliver: bool = True) -> int:
        topic = topic_for(symbol)
        log_ = self.logs.setdefault(topic, [])
        log_.append(json.dumps(msg))
        offset = len(log_) - 1
        if deliver:
            self.hub.ingest(topic, log_[offset], offset)
        else:
            self.dropped.add((topic, offset))
        return offset

    def _replay(self, symbol: str, offset: int) -> None:
        topic = topic_for(symbol)
        # Like a Kafka seek: the replay is delivered after the current message, never re-entrantly, and runs to
        # the end of the log as it is then.
        asyncio.get_running_loop().call_soon(self._redeliver, topic, offset)

    def _redeliver(self, topic: str, start: int) -> None:
        entries = self.logs.get(topic, [])
        for i in range(start, len(entries)):
            self.hub.ingest(topic, entries[i], i)


class KafkaSource:
    """Reads `marketdata.<symbol>` (one partition each) from the beginning and seeks back on upstream gaps."""

    def __init__(self, hub: FeedHub, brokers: str):
        self.hub = hub
        self.brokers = brokers
        self.consumer = None
        self.task: asyncio.Task | None = None
        hub.replay = self._replay

    async def start(self) -> None:
        from aiokafka import AIOKafkaConsumer, TopicPartition

        self._tp = lambda symbol: TopicPartition(topic_for(symbol), 0)
        self.consumer = AIOKafkaConsumer(
            bootstrap_servers=self.brokers,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        await self.consumer.start()
        partitions = [self._tp(s) for s in self.hub.feeds]
        self.consumer.assign(partitions)
        await self.consumer.seek_to_beginning(*partitions)
        self.task = asyncio.create_task(self._run())

    async def _run(self) -> None:
        assert self.consumer is not None
        try:
            async for record in self.consumer:
                self.hub.ingest(record.topic, record.value, record.offset)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("market-data consumer stopped")
            raise

    def _replay(self, symbol: str, offset: int) -> None:
        if self.consumer is not None:
            self.consumer.seek(self._tp(symbol), offset)

    async def stop(self) -> None:
        if self.task is not None:
            self.task.cancel()
        if self.consumer is not None:
            await self.consumer.stop()
