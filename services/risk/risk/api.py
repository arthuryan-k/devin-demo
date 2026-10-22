"""Synchronous pre-trade risk service.

The gateway calls it before publishing a command to ``commands.<symbol>``. Each check runs in one Postgres
transaction against the portfolio ledger (shared cash row locked ``FOR UPDATE``, then the symbol's position), so the
reservation is atomic with the portfolio state and concurrent orders from one account cannot over-commit it.
"""
from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from typing import Literal

import psycopg
from fastapi import FastAPI, Request
from pydantic import AliasChoices, BaseModel, Field, model_validator

from portfolio import ledger
from portfolio.db import open_pool, transaction

log = logging.getLogger("risk")


class Target(BaseModel):
    """Whose ledger: ``participantId`` (the id the engine stamps on orders); ``accountId`` is accepted when it is
    the only one sent. The ledger is keyed by participant id, not by auth-service's ``accounts.id``."""
    participantId: int | None = None
    accountId: int | None = None
    symbol: str
    orderId: int

    @model_validator(mode="after")
    def _owner(self):
        if self.participantId is None and self.accountId is None:
            raise ValueError("participantId is required")
        return self

    @property
    def owner(self) -> int:
        return self.participantId if self.participantId is not None else self.accountId


class OrderCheckIn(Target):
    side: Literal["BUY", "SELL"]
    type: Literal["LIMIT", "MARKET"] = Field("LIMIT", validation_alias=AliasChoices("type", "orderType"))
    tif: Literal["GTC", "IOC", "FOK"] = Field("GTC", validation_alias=AliasChoices("tif", "timeInForce"))
    price: int | None = Field(None, description="Limit price in integer ticks; omit for MARKET")
    qty: int
    referencePrice: int | None = Field(None, description="Optional market-buy anchor in ticks, e.g. best ask")


class AmendCheckIn(Target):
    newPrice: int | None = None
    newQty: int | None = None


class CancelCheckIn(Target):
    pass


class ReleaseIn(Target):
    kind: Literal["place", "amend"] = "place"


def _approved(a: ledger.Approval) -> dict:
    return {
        "approved": True,
        "orderId": a.order_id,
        "reservedCash": a.reserved_cash,
        "reservedShares": a.reserved_shares,
        "limitPrice": a.limit_price,
        "availableCash": a.available_cash,
        "availableShares": a.available_shares,
    }


def _rejected(order_id: int, e: ledger.Reject) -> dict:
    return {"approved": False, "orderId": order_id, "reason": e.reason, "message": e.message}


def _run(pool, order_id: int, fn):
    for attempt in range(5):
        try:
            with transaction(pool) as conn:
                return _approved(fn(conn))
        except ledger.Reject as e:
            return _rejected(order_id, e)
        except (psycopg.errors.DeadlockDetected, psycopg.errors.SerializationFailure):
            if attempt == 4:
                raise
        except psycopg.errors.UniqueViolation:
            # Two concurrent checks with the same order id: the loser sees the winner's row on retry.
            if attempt == 4:
                raise


def create_app(pool=None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        owned = pool is None
        if owned:
            app.state.pool = open_pool(max_size=int(os.environ.get("DB_POOL_SIZE", "20")))
        else:
            app.state.pool = pool
        yield
        if owned:
            app.state.pool.close()

    app = FastAPI(title="risk", version="0.1.0", lifespan=lifespan)

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.post("/v1/orders/check")
    @app.post("/v1/check", include_in_schema=False)
    def check_order(body: OrderCheckIn, request: Request):
        req = ledger.OrderCheck(body.owner, body.symbol, body.orderId, body.side, body.type, body.tif, body.qty,
                                body.price, body.referencePrice)
        return _run(request.app.state.pool, body.orderId, lambda conn: ledger.check_order(conn, req))

    @app.post("/v1/orders/check-amend")
    @app.post("/v1/check-amend", include_in_schema=False)
    def check_amend(body: AmendCheckIn, request: Request):
        return _run(request.app.state.pool, body.orderId, lambda conn: ledger.check_amend(
            conn, body.owner, body.symbol, body.orderId, body.newPrice, body.newQty))

    @app.post("/v1/orders/check-cancel")
    @app.post("/v1/check-cancel", include_in_schema=False)
    def check_cancel(body: CancelCheckIn, request: Request):
        def run(conn):
            ledger.check_cancel(conn, body.owner, body.symbol, body.orderId)
            return ledger.Approval(body.orderId, 0, 0, None, 0, 0)

        result = _run(request.app.state.pool, body.orderId, run)
        return {k: result[k] for k in ("approved", "orderId", "reason", "message") if k in result}

    @app.post("/v1/orders/release")
    @app.post("/v1/release", include_in_schema=False)
    def release(body: ReleaseIn, request: Request):
        return _run(request.app.state.pool, body.orderId, lambda conn: ledger.release(
            conn, body.owner, body.symbol, body.orderId, body.kind))

    return app


app = create_app()
