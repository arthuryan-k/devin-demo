"""Pytest fixtures shared by the portfolio and risk test suites: a throwaway Postgres (docker) with migrations."""
from __future__ import annotations

import os
import socket
import subprocess
import time
import uuid

import psycopg
import pytest

from pathlib import Path

from portfolio.db import open_pool

MIGRATIONS = Path(__file__).resolve().parents[3] / "db" / "migrations"
TABLES = "portfolio.fills, portfolio.reservations, portfolio.positions, portfolio.accounts, portfolio.marks, " \
         "portfolio.consumer_offsets, public.users"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def database_url():
    """``TEST_DATABASE_URL`` if set, else a disposable ``postgres:16-alpine`` container."""
    url = os.environ.get("TEST_DATABASE_URL")
    if url:
        yield url
        return
    name = f"portfolio-test-{uuid.uuid4().hex[:8]}"
    port = _free_port()
    subprocess.run(
        ["docker", "run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=postgres", "-e",
         "POSTGRES_DB=brokerage", "-p", f"127.0.0.1:{port}:5432", "postgres:16-alpine"],
        check=True, capture_output=True,
    )
    url = f"postgresql://postgres:postgres@127.0.0.1:{port}/brokerage"
    try:
        deadline = time.time() + 60
        while True:
            try:
                psycopg.connect(url, connect_timeout=2).close()
                break
            except psycopg.OperationalError:
                if time.time() > deadline:
                    raise
                time.sleep(0.5)
        yield url
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)


def migrate(url: str) -> None:
    """Runs the repo's Flyway migrations (db/migrations) against ``url``, exactly as docker compose does."""
    info = psycopg.conninfo.conninfo_to_dict(url)
    subprocess.run(
        ["docker", "run", "--rm", "--network", "host", "-v", f"{MIGRATIONS}:/flyway/sql:ro", "flyway/flyway:10-alpine",
         f"-url=jdbc:postgresql://{info['host']}:{info.get('port', 5432)}/{info['dbname']}",
         f"-user={info['user']}", f"-password={info.get('password', '')}", "-connectRetries=10", "migrate"],
        check=True, capture_output=True,
    )


def auth_account(pool, participant_id: int, starting_cash_dollars: str) -> None:
    """Inserts the user + account rows auth-service would create at signup."""
    with pool.connection() as conn:
        conn.execute(
            "WITH u AS (INSERT INTO users (username, kind) VALUES (%s, 'simulated') RETURNING id) "
            "INSERT INTO accounts (user_id, participant_id, label, starting_cash) SELECT id, %s, %s, %s FROM u",
            (f"user{participant_id}", participant_id, f"P{participant_id}", starting_cash_dollars),
        )
        conn.commit()


@pytest.fixture(scope="session")
def pool(database_url):
    migrate(database_url)
    p = open_pool(database_url, max_size=20)
    yield p
    p.close()


@pytest.fixture(autouse=True)
def clean(pool):
    with pool.connection() as conn:
        conn.execute(f"TRUNCATE {TABLES} CASCADE")
        conn.commit()
    yield
