"""``_run``: one ledger transaction per attempt, retried on lock conflicts."""

from contextlib import contextmanager

import psycopg
import pytest
from portfolio import ledger

from risk import api

APPROVAL = ledger.Approval(1, 100, 0, 10, 900, 0)


@pytest.fixture
def no_db(monkeypatch):
    opened = []

    @contextmanager
    def fake_transaction(pool):
        opened.append(pool)
        yield object()

    monkeypatch.setattr(api, "transaction", fake_transaction)
    return opened


def flaky(*errors):
    remaining = list(errors)

    def fn(conn):
        if remaining:
            raise remaining.pop(0)
        return APPROVAL

    return fn


def test_retries_deadlocks_and_serialization_failures(no_db):
    fn = flaky(psycopg.errors.DeadlockDetected("deadlock"), psycopg.errors.SerializationFailure("conflict"))
    r = api._run("pool", 1, fn)
    assert r["approved"] is True
    assert r["reservedCash"] == 100
    assert len(no_db) == 3


def test_gives_up_after_five_attempts(no_db):
    fn = flaky(*[psycopg.errors.DeadlockDetected("deadlock")] * 5)
    with pytest.raises(psycopg.errors.DeadlockDetected):
        api._run("pool", 1, fn)
    assert len(no_db) == 5


def test_duplicate_insert_race_retries_into_a_rejection(no_db):
    def fn(conn):
        if len(no_db) == 1:
            raise psycopg.errors.UniqueViolation("duplicate key")
        raise ledger.Reject(ledger.Reject.DUPLICATE_ORDER_ID, "order 1 already exists")

    r = api._run("pool", 1, fn)
    assert r == {
        "approved": False,
        "orderId": 1,
        "reason": "DUPLICATE_ORDER_ID",
        "message": "order 1 already exists",
    }


def test_rejections_are_not_retried(no_db):
    def fn(conn):
        raise ledger.Reject(ledger.Reject.NO_SHORTING, "no")

    assert api._run("pool", 7, fn)["reason"] == "NO_SHORTING"
    assert len(no_db) == 1


def test_other_database_errors_propagate(no_db):
    with pytest.raises(psycopg.errors.CheckViolation):
        api._run("pool", 1, flaky(psycopg.errors.CheckViolation("cash >= 0")))
    assert len(no_db) == 1
