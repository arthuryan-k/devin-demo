import { describe, expect, it, vi } from "vitest";
import { HttpBackend, foldOrderHistory, toOpenOrders, toOrderResult, toTradeRow } from "../src/api/http";
import type { MarketDataMessage } from "../src/marketdata/wire";

type Call = { url: string; method: string; body?: unknown; auth?: string };

function gateway(routes: Record<string, (c: Call) => unknown>) {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (url: string, init: RequestInit = {}) => {
    const headers = (init.headers ?? {}) as Record<string, string>;
    const call: Call = { url, method: init.method ?? "GET", body: init.body ? JSON.parse(String(init.body)) : undefined, auth: headers.Authorization };
    calls.push(call);
    const key = `${call.method} ${url.split("?")[0]}`;
    const handler = routes[key];
    if (!handler) return new Response(JSON.stringify({ error: "not found", reason: "NOT_FOUND" }), { status: 404 });
    const out = handler(call);
    return out instanceof Response ? out : new Response(JSON.stringify(out));
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

const SESSION = { token: "jwt", user: { id: 7, username: "alice" }, account: { id: 7, participantId: 1007 } };

async function signedIn(routes: Record<string, (c: Call) => unknown>) {
  const g = gateway({ "POST /api/auth/login": () => SESSION, ...routes });
  const b = new HttpBackend({ fetchImpl: g.fetchImpl, storage: sessionStorage, accountPollMs: 60_000 });
  await b.login("alice", "pw");
  return { b, calls: g.calls };
}

describe("HttpBackend against the Phase 5 gateway contract", () => {
  it("reads instruments from GET /api/symbols", async () => {
    const { b } = await signedIn({ "GET /api/symbols": () => ({ symbols: [{ symbol: "ACME", name: "Acme", tickSize: "0.01", lotSize: 1 }] }) });
    expect(await b.instruments()).toEqual([{ symbol: "ACME", name: "Acme", tickSize: 0.01, lotSize: 1 }]);
  });

  it("posts orders with decimal-string prices and maps the engine events to a result", async () => {
    const { b, calls } = await signedIn({
      "POST /api/orders": () => ({
        orderId: 4417558253568001, symbol: "ACME", seq: 42,
        events: [
          { type: "OrderPlaced", orderId: 4417558253568001, side: "BUY", price: "100.25", qty: 10 },
          { type: "TradeExecuted", price: "100.20", qty: 4, makerOrderId: 9, takerOrderId: 4417558253568001 },
        ],
      }),
    });
    const r = await b.placeOrder({ symbol: "ACME", side: "BUY", type: "LIMIT", timeInForce: "GTC", price: 100.25, qty: 10 });
    expect(calls.at(-1)).toMatchObject({ method: "POST", auth: "Bearer jwt", body: { symbol: "ACME", side: "BUY", type: "LIMIT", price: "100.25", qty: 10, timeInForce: "GTC" } });
    expect(r).toEqual({ orderId: 4417558253568001, status: "PARTIALLY_FILLED", filledQty: 4, reason: undefined, seq: 42 });
  });

  it("omits the price on MARKET orders and sends amends as decimal strings", async () => {
    const { b, calls } = await signedIn({
      "POST /api/orders": () => ({ orderId: 5, symbol: "ACME", seq: 1, events: [{ type: "OrderPlaced", orderId: 5, qty: 3 }, { type: "OrderCancelled", orderId: 5, cancelledQty: 3, reason: "IOC_REMAINDER" }] }),
      "PATCH /api/orders/5": () => ({ orderId: 5, symbol: "ACME", seq: 2, events: [{ type: "OrderAmended", orderId: 5, newPrice: "99.50", newQty: 2 }] }),
    });
    expect((await b.placeOrder({ symbol: "ACME", side: "SELL", type: "MARKET", timeInForce: "IOC", price: 99, qty: 3 })).status).toBe("CANCELLED");
    expect((calls.at(-1)!.body as Record<string, unknown>).price).toBeUndefined();
    await b.amendOrder(5, { price: 99.5 });
    expect(calls.at(-1)).toMatchObject({ method: "PATCH", body: { price: "99.50" } });
  });

  it("maps a 200 RiskRejected reply and gateway {error, reason} errors", async () => {
    expect(toOrderResult({ orderId: 8, symbol: "ACME", events: [{ type: "RiskRejected", orderId: 8, reason: "INSUFFICIENT_CASH", message: "needs 1,000.00" }] }))
      .toMatchObject({ status: "REJECTED", reason: "needs 1,000.00", seq: undefined });
    const { b } = await signedIn({
      "DELETE /api/orders/9": () => new Response(JSON.stringify({ error: "200 msgs/s per participant", reason: "RATE_LIMITED" }), { status: 429 }),
    });
    await expect(b.cancelOrder(9)).rejects.toMatchObject({ status: 429, reason: "RATE_LIMITED", message: "200 msgs/s per participant" });
  });

  it("converts the portfolio body (integer cents) and its open orders", async () => {
    const { b } = await signedIn({
      "GET /api/portfolio": () => ({
        accountId: 1007, cash: 9990000, reservedCash: 100250, availableCash: 9889750, startingCash: 10000000, equity: 10001000,
        realizedPnl: 0, unrealizedPnl: 1000, sessionPnl: 1000,
        positions: [{ symbol: "ACME", shares: 10, reservedShares: 0, availableShares: 10, costBasis: 100000, avgCost: 10000, markPrice: 10100, marketValue: 101000, realizedPnl: 0, unrealizedPnl: 1000 }],
        openOrders: [{ symbol: "ACME", orderId: 11, side: "BUY", type: "LIMIT", tif: "GTC", limitPrice: 10025, openQty: 10, filledQty: 0, reservedCash: 100250, reservedShares: 0, status: "OPEN" }],
      }),
    });
    const view = await new Promise<Parameters<Parameters<HttpBackend["subscribeAccount"]>[0]>[0]>((resolve) => {
      const stop = b.subscribeAccount((v) => (stop(), resolve(v)));
    });
    expect(view.portfolio).toMatchObject({ cash: 99900, reservedCash: 1002.5, availableCash: 98897.5, startingCash: 100000, sessionPnl: 10 });
    expect(view.portfolio.positions[0]).toMatchObject({ symbol: "ACME", qty: 10, avgCost: 100, markPrice: 101, marketValue: 1010, unrealizedPnl: 10 });
    expect(view.openOrders).toEqual([expect.objectContaining({ id: 11, timeInForce: "GTC", price: 100.25, qty: 10, reservedCash: 1002.5 })]);
  });

  it("keeps an open order's first-seen time across polls", () => {
    const seen = new Map<number, number>();
    const body = { openOrders: [{ symbol: "ACME", orderId: 1, side: "BUY", limitPrice: 100, openQty: 1 }] };
    const t0 = toOpenOrders(body, seen)[0].createdAt;
    expect(toOpenOrders(body, seen)[0].createdAt).toBe(t0);
    toOpenOrders({ openOrders: [] }, seen);
    expect(seen.size).toBe(0);
  });

  it("subscribes to /ws/marketdata/{symbol} and resyncs with {type: resync}", () => {
    const sockets: FakeSocket[] = [];
    class FakeSocket {
      static OPEN = 1;
      readyState = 1;
      sent: string[] = [];
      onopen?: () => void;
      onmessage?: (ev: { data: string }) => void;
      onclose?: () => void;
      constructor(public url: string) { sockets.push(this); }
      send(s: string) { this.sent.push(s); }
      close() {}
    }
    const b = new HttpBackend({ marketDataUrl: "ws://gw/ws/marketdata/{symbol}", webSocketImpl: FakeSocket as unknown as typeof WebSocket });
    const got: MarketDataMessage[] = [];
    const statuses: string[] = [];
    const sub = b.subscribeMarket("ACME", { onMessage: (m) => got.push(m), onStatus: (s) => statuses.push(s) });
    const ws = sockets[0];
    expect(ws.url).toBe("ws://gw/ws/marketdata/ACME");
    ws.onopen?.();
    ws.onmessage?.({ data: JSON.stringify({ type: "snapshot", symbol: "ACME", seq: -1, reason: "subscribe", bids: [], asks: [], trades: [], events: [] }) });
    expect(statuses.at(-1)).toBe("live");
    sub.resync();
    expect(ws.sent).toEqual([JSON.stringify({ type: "resync" })]);
    expect(got).toHaveLength(1);
  });
});

describe("history mapping (orders_history / trades_history rows)", () => {
  const trades = [
    { id: 1, symbol: "ACME", seq: 3, eventIndex: 1, makerOrderId: 50, takerOrderId: 60, makerParticipantId: 1007, takerParticipantId: 1001, aggressorSide: "SELL", priceTicks: 10010, qty: 4, eventTime: "2026-10-22T14:00:03Z" },
    { id: 2, symbol: "ACME", seq: 4, eventIndex: 1, makerOrderId: 70, takerOrderId: 50, makerParticipantId: 1002, takerParticipantId: 1007, aggressorSide: "BUY", priceTicks: 10020, qty: 6, eventTime: "2026-10-22T14:00:04Z" },
  ];

  it("maps trades from the caller's side as maker or taker", () => {
    const [maker, taker] = trades.map((t) => toTradeRow(t, 1007));
    expect(maker).toMatchObject({ tradeId: "1", orderId: 50, side: "BUY", liquidity: "MAKER", price: 100.1, qty: 4 });
    expect(taker).toMatchObject({ orderId: 50, side: "BUY", liquidity: "TAKER", price: 100.2, time: Date.parse("2026-10-22T14:00:04Z") });
  });

  it("folds lifecycle rows into one row per order with fills from trades", () => {
    const rows = foldOrderHistory(
      [
        { symbol: "ACME", seq: 4, eventIndex: 0, orderId: 50, eventType: "AMENDED", priceTicks: 10020, qty: 6, eventTime: "2026-10-22T14:00:04Z" },
        { symbol: "ACME", seq: 2, eventIndex: 0, orderId: 50, eventType: "PLACED", side: "BUY", orderType: "LIMIT", timeInForce: "GTC", priceTicks: 10010, qty: 10, eventTime: "2026-10-22T14:00:02Z" },
        { symbol: "ACME", seq: 5, eventIndex: 0, orderId: 51, eventType: "REJECTED", side: "SELL", qty: 5, reason: "NO_POSITION", eventTime: "2026-10-22T14:00:05Z" },
      ],
      trades.map((t) => toTradeRow(t, 1007)),
    );
    expect(rows.map((r) => [r.orderId, r.status])).toEqual([[51, "REJECTED"], [50, "FILLED"]]);
    expect(rows[1]).toMatchObject({ side: "BUY", timeInForce: "GTC", price: 100.2, qty: 10, filledQty: 10, createdAt: Date.parse("2026-10-22T14:00:02Z") });
    expect(rows[1].avgFillPrice).toBeCloseTo(100.16, 6);
    expect(rows[0].reason).toBe("NO_POSITION");
  });
});
