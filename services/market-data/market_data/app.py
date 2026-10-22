from __future__ import annotations

import asyncio
import contextlib
from typing import Any, Optional, Protocol

from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect

from .feed import Subscriber, SymbolFeed
from .hub import FeedHub

UNKNOWN_SYMBOL = 4404


class Source(Protocol):
    async def start(self) -> None: ...
    async def stop(self) -> None: ...


def create_app(hub: FeedHub, source: Optional[Source] = None) -> FastAPI:
    @contextlib.asynccontextmanager
    async def lifespan(_app: FastAPI):
        if source is not None:
            await source.start()
        try:
            yield
        finally:
            if source is not None:
                await source.stop()

    app = FastAPI(title="market-data", lifespan=lifespan)
    app.state.hub = hub

    @app.get("/healthz")
    async def healthz() -> dict[str, Any]:
        return {"ok": True, "symbols": {s: {"seq": f.seq, "ready": f.ready, "subscribers": len(f.subscribers),
                                            "gaps": f.gaps} for s, f in hub.feeds.items()}}

    @app.get("/marketdata/symbols")
    async def symbols() -> dict[str, Any]:
        return {"symbols": [{"symbol": s, "tickSize": f.tick_size, "seq": f.seq} for s, f in hub.feeds.items()]}

    @app.get("/marketdata/{symbol}/snapshot")
    async def snapshot(symbol: str, prices: str = Query("decimal", pattern="^(decimal|ticks)$")) -> dict[str, Any]:
        feed = hub.feed(symbol)
        if feed is None:
            raise HTTPException(404, f"unknown symbol {symbol}")
        from .prices import PriceFormat

        return feed.snapshot(PriceFormat(feed.tick_size, prices == "decimal"), "request")

    @app.websocket("/marketdata/{symbol}/ws")
    async def stream(websocket: WebSocket, symbol: str, prices: str = "decimal") -> None:
        await websocket.accept()
        feed = hub.feed(symbol)
        if feed is None:
            await websocket.close(code=UNKNOWN_SYMBOL, reason=f"unknown symbol {symbol}")
            return
        sub = feed.subscribe(decimal=prices != "ticks")
        tasks = [asyncio.create_task(_send(websocket, sub)), asyncio.create_task(_receive(websocket, feed, sub))]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            feed.unsubscribe(sub)
            for t in tasks:
                t.cancel()
            for t in tasks:
                with contextlib.suppress(asyncio.CancelledError, WebSocketDisconnect, RuntimeError):
                    await t

    return app


async def _send(websocket: WebSocket, sub: Subscriber) -> None:
    while True:
        await websocket.send_json(await sub.queue.get())


async def _receive(websocket: WebSocket, feed: SymbolFeed, sub: Subscriber) -> None:
    """Clients may send {"type":"resync"} to get a fresh snapshot, e.g. after detecting a gap themselves."""
    while True:
        try:
            msg = await websocket.receive_json()
        except WebSocketDisconnect:
            return
        except ValueError:
            continue
        if isinstance(msg, dict) and msg.get("type") == "resync":
            feed.request_snapshot(sub)
