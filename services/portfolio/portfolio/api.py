"""Portfolio service: read API over the ledger plus the ``events.<symbol>`` settlement consumer."""
from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from portfolio import config, ledger
from portfolio.db import open_pool, transaction

log = logging.getLogger("portfolio.api")


class GrantIn(BaseModel):
    symbol: str
    shares: int = Field(ge=0)
    avgCost: int = Field(0, ge=0)


class OpenAccountIn(BaseModel):
    accountId: int
    cash: int | None = Field(None, ge=0, description="Cash grant in ticks; defaults to the auth-service account's starting_cash")
    positions: list[GrantIn] = []


def create_app(pool=None, *, run_consumer: bool | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        owned = pool is None
        if owned:
            app.state.pool = open_pool()
        else:
            app.state.pool = pool
        consumer = None
        if run_consumer if run_consumer is not None else config.kafka_bootstrap() is not None:
            from portfolio.consumer import SettlementConsumer

            consumer = SettlementConsumer(app.state.pool).start()
        yield
        if consumer is not None:
            consumer.stop()
        if owned:
            app.state.pool.close()

    app = FastAPI(title="portfolio", version="0.1.0", lifespan=lifespan)

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.post("/v1/accounts", status_code=201)
    def open_account(body: OpenAccountIn, request: Request):
        cash = body.cash
        try:
            with transaction(request.app.state.pool) as conn:
                ledger.open_account(conn, body.accountId, cash,
                                    [ledger.Grant(g.symbol, g.shares, g.avgCost) for g in body.positions])
                return ledger.account_view(conn, body.accountId)
        except ledger.AccountExists:
            raise HTTPException(409, f"account {body.accountId} already exists")
        except ledger.Reject as e:
            raise HTTPException(400, {"reason": e.reason, "message": e.message})

    @app.get("/v1/accounts/{account_id}")
    def get_account(account_id: int, request: Request):
        with transaction(request.app.state.pool) as conn:
            view = ledger.account_view(conn, account_id)
        if view is None:
            raise HTTPException(404, f"account {account_id} not found")
        return view

    @app.get("/v1/accounts/{account_id}/fills")
    def get_fills(account_id: int, request: Request, limit: int = 100):
        with transaction(request.app.state.pool) as conn:
            rows = conn.execute(
                "SELECT fill_id, symbol, order_id, side, price, qty, seq, settled_at FROM portfolio.fills "
                "WHERE account_id = %s ORDER BY fill_id DESC LIMIT %s",
                (account_id, min(max(limit, 1), 1000)),
            ).fetchall()
        return [
            {"fillId": r["fill_id"], "symbol": r["symbol"], "orderId": r["order_id"], "side": r["side"],
             "price": r["price"], "qty": r["qty"], "seq": r["seq"], "settledAt": r["settled_at"].isoformat()}
            for r in rows
        ]

    @app.get("/v1/marks")
    def marks(request: Request):
        with transaction(request.app.state.pool) as conn:
            rows = conn.execute("SELECT symbol, last_price FROM portfolio.marks ORDER BY symbol").fetchall()
        return {r["symbol"]: r["last_price"] for r in rows}

    return app


app = create_app()
