"""Settles fills from ``events.<symbol>`` into Postgres with exactly-once effect.

Offsets live in ``portfolio.consumer_offsets`` and are advanced in the same transaction as the settlement, so a
crash or redelivery never double-applies a batch. On partition assignment the consumer seeks to the stored offset.
Per-symbol ordering comes from the topic partition (the engine is the single writer of each symbol's topic).
"""
from __future__ import annotations

import logging
import threading

import psycopg
from psycopg_pool import ConnectionPool

from portfolio import config, events, ledger
from portfolio.db import transaction

log = logging.getLogger("portfolio.consumer")
TOPIC_PATTERN = "^events\\..*"


def process(pool: ConnectionPool, topic: str, partition: int, offset: int, value: bytes | str) -> bool:
    """Applies one message unless it was already applied. Returns True if it changed the ledger state."""
    batch = events.decode(value, topic)
    with transaction(pool) as conn:
        row = conn.execute(
            "SELECT next_offset FROM portfolio.consumer_offsets WHERE topic = %s AND partition = %s FOR UPDATE",
            (topic, partition),
        ).fetchone()
        if row is not None and offset < row["next_offset"]:
            return False
        if batch.symbol is None:
            raise ValueError(f"message at {topic}[{partition}]@{offset} has no symbol")
        ledger.settle(conn, batch.symbol, batch.events, batch.seq)
        conn.execute(
            "INSERT INTO portfolio.consumer_offsets (topic, partition, next_offset) VALUES (%s, %s, %s) "
            "ON CONFLICT (topic, partition) DO UPDATE SET next_offset = EXCLUDED.next_offset",
            (topic, partition, offset + 1),
        )
    return True


def stored_offsets(pool: ConnectionPool) -> dict[tuple[str, int], int]:
    with pool.connection() as conn:
        rows = conn.execute("SELECT topic, partition, next_offset FROM portfolio.consumer_offsets").fetchall()
        conn.rollback()
    return {(r["topic"], r["partition"]): r["next_offset"] for r in rows}


class SettlementConsumer:
    def __init__(self, pool: ConnectionPool, bootstrap: str | None = None, group_id: str = "portfolio-settlement"):
        from confluent_kafka import Consumer

        self._pool = pool
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._consumer = Consumer({
            "bootstrap.servers": bootstrap or config.kafka_bootstrap(),
            "group.id": group_id,
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
            "topic.metadata.refresh.interval.ms": 5000,
        })

    def _on_assign(self, consumer, partitions) -> None:
        offsets = stored_offsets(self._pool)
        for p in partitions:
            if (p.topic, p.partition) in offsets:
                p.offset = offsets[(p.topic, p.partition)]
        consumer.assign(partitions)

    def run(self) -> None:
        self._consumer.subscribe([TOPIC_PATTERN], on_assign=self._on_assign)
        try:
            while not self._stop.is_set():
                msg = self._consumer.poll(0.5)
                if msg is None:
                    continue
                if msg.error():
                    log.warning("consumer error: %s", msg.error())
                    continue
                self._process_with_retry(msg)
                self._consumer.commit(message=msg, asynchronous=True)
        finally:
            self._consumer.close()

    def _process_with_retry(self, msg) -> None:
        for attempt in range(5):
            try:
                process(self._pool, msg.topic(), msg.partition(), msg.offset(), msg.value())
                return
            except (psycopg.errors.DeadlockDetected, psycopg.errors.SerializationFailure):
                log.info("retrying %s[%s]@%s after lock conflict", msg.topic(), msg.partition(), msg.offset())
        raise RuntimeError(f"could not settle {msg.topic()}[{msg.partition()}]@{msg.offset()}")

    def start(self) -> "SettlementConsumer":
        self._thread = threading.Thread(target=self.run, name="settlement-consumer", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=10)


def main() -> None:
    from portfolio.db import open_pool

    logging.basicConfig(level=logging.INFO)
    SettlementConsumer(open_pool()).run()


if __name__ == "__main__":
    main()
