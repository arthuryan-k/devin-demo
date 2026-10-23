"""Connection pool and transactions. The schema is migrated by Flyway from the repo's ``db/migrations``."""

from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from portfolio import config


def open_pool(url: str | None = None, *, min_size: int = 1, max_size: int = 10) -> ConnectionPool:
    pool = ConnectionPool(
        url or config.database_url(),
        min_size=min_size,
        max_size=max_size,
        kwargs={"row_factory": dict_row, "autocommit": False},
        open=True,
    )
    pool.wait()
    return pool


@contextmanager
def transaction(pool: ConnectionPool) -> Iterator[psycopg.Connection]:
    """One READ COMMITTED transaction; row locks (``FOR UPDATE``) give per-account serialization."""
    with pool.connection() as conn, conn.transaction():
        yield conn
