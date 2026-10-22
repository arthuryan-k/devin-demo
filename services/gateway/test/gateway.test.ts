import { afterEach, describe, expect, it } from "vitest";

import { harness, asAlice, asBob } from "./fakes.js";

type H = Awaited<ReturnType<typeof harness>>;
let h: H;

afterEach(async () => {
  await h?.app.close();
});

const place = (headers: Record<string, string>, body: Record<string, unknown>) =>
  h.app.inject({ method: "POST", url: "/api/orders", headers, payload: body });

describe("authentication", () => {
  it("rejects missing credentials with 401", async () => {
    h = await harness();
    const res = await place({}, { symbol: "ACME", side: "BUY", price: "10.00", qty: 1 });
    expect(res.statusCode).toBe(401);
    expect(res.json().reason).toBe("MISSING_CREDENTIALS");
    expect(h.bus.records("commands.ACME")).toHaveLength(0);
  });

  it("rejects an unknown API key and an invalid token with 401", async () => {
    h = await harness();
    const bad = await place({ "x-api-key": "nope" }, { symbol: "ACME", side: "BUY", price: "10", qty: 1 });
    expect(bad.statusCode).toBe(401);
    expect(bad.json().reason).toBe("INVALID_API_KEY");
    const token = await place({ authorization: "Bearer forged" }, { symbol: "ACME", side: "BUY", price: "10", qty: 1 });
    expect(token.statusCode).toBe(401);
    expect(token.json().reason).toBe("INVALID_TOKEN");
    for (const method of ["PATCH", "DELETE"] as const) {
      const r = await h.app.inject({ method, url: "/api/orders/5", payload: { qty: 1 } });
      expect(r.statusCode).toBe(401);
    }
  });

  it("accepts a bearer session token", async () => {
    h = await harness();
    const res = await place({ authorization: "Bearer jwt-alice" }, { symbol: "ACME", side: "BUY", price: "10", qty: 1 });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(h.bus.records("commands.ACME")[0]!.value).participantId).toBe(1001);
  });
});

describe("admission control", () => {
  it("returns 429 RATE_LIMITED once the burst is spent, then refills with time", async () => {
    let now = 0;
    h = await harness({ limits: { messagesPerSec: 10, burst: 3 }, clock: () => now });
    for (let i = 0; i < 3; i++) {
      expect((await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(200);
    }
    const limited = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(limited.statusCode).toBe(429);
    expect(limited.json().reason).toBe("RATE_LIMITED");
    // Buckets are per participant.
    expect((await place(asBob, { symbol: "ACME", side: "SELL", price: "50", qty: 1 })).statusCode).toBe(200);
    now += 100; // one token at 10/s
    expect((await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(200);
    expect(h.bus.records("commands.ACME")).toHaveLength(5);
  });

  it("returns 429 MAX_OPEN_ORDERS at the open-order cap; cancels free a slot", async () => {
    h = await harness({ limits: { maxOpenOrders: 2 } });
    const ids: number[] = [];
    for (let i = 0; i < 2; i++) {
      ids.push((await place(asAlice, { symbol: "GLOBX", side: "BUY", price: "1", qty: 1 })).json().orderId);
    }
    const capped = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(capped.statusCode).toBe(429);
    expect(capped.json().reason).toBe("MAX_OPEN_ORDERS");
    expect((await h.app.inject({ method: "DELETE", url: `/api/orders/${ids[0]}`, headers: asAlice })).statusCode).toBe(200);
    expect((await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(200);
  });

  it("blocks with 403 after maxViolations, for every call", async () => {
    h = await harness({ limits: { messagesPerSec: 1, burst: 1, maxViolations: 3 } });
    expect((await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(200);
    for (let i = 0; i < 3; i++) {
      expect((await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(429);
    }
    const blocked = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(blocked.statusCode).toBe(403);
    expect(blocked.json().reason).toBe("BLOCKED");
    expect((await h.app.inject({ method: "DELETE", url: "/api/orders/1?symbol=ACME", headers: asAlice })).statusCode).toBe(403);
    expect((await place(asBob, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(200);
  });
});

describe("synchronous risk", () => {
  it("surfaces a risk rejection to the caller and never publishes the command", async () => {
    h = await harness();
    h.risk.decide = () => ({ approved: false, reason: "INSUFFICIENT_FUNDS", message: "needs 1000.00, has 10.00" });
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", price: "100.00", qty: 10 });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.seq).toBeUndefined();
    expect(body.events).toEqual([
      expect.objectContaining({ type: "RiskRejected", reason: "INSUFFICIENT_FUNDS", message: "needs 1000.00, has 10.00" }),
    ]);
    expect(h.bus.records("commands.ACME")).toHaveLength(0);
    expect(h.risk.checks[0]).toMatchObject({ action: "PLACE", participantId: 1001, symbol: "ACME", side: "BUY", price: 10000, qty: 10 });
  });

  it("checks amends with the new price and qty in ticks", async () => {
    h = await harness();
    const id = (await place(asAlice, { symbol: "ACME", side: "BUY", price: "10.00", qty: 5 })).json().orderId;
    h.risk.decide = (r) => (r.action === "AMEND" ? { approved: false, reason: "INSUFFICIENT_FUNDS", message: "no" } : { approved: true });
    const res = await h.app.inject({ method: "PATCH", url: `/api/orders/${id}`, headers: asAlice, payload: { price: "12.50", qty: 9 } });
    expect(res.json().events[0].type).toBe("RiskRejected");
    expect(h.risk.checks[1]).toMatchObject({ action: "AMEND", orderId: id, price: 1250, qty: 9 });
    expect(h.bus.records("commands.ACME")).toHaveLength(1);
  });

  it("fails closed with 503 when the risk service is down", async () => {
    h = await harness();
    const { RiskUnavailable } = await import("../src/risk.js");
    h.risk.check = async () => {
      throw new RiskUnavailable("connection refused");
    };
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(res.statusCode).toBe(503);
    expect(res.json().reason).toBe("RISK_UNAVAILABLE");
    expect(h.bus.records("commands.ACME")).toHaveLength(0);
  });

  it("releases the reservation when the command cannot be published", async () => {
    h = await harness();
    h.bus.failNextPublish = new Error("broker down");
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(res.statusCode).toBe(503);
    expect(res.json().reason).toBe("BUS_UNAVAILABLE");
    expect(h.risk.releases).toEqual([expect.objectContaining({ orderId: res.json().orderId ?? h.risk.checks[0]!.orderId })]);
  });
});

describe("risk contract details", () => {
  it("sends a protected MARKET buy as LIMIT IOC at risk's limitPrice", async () => {
    h = await harness();
    await place(asBob, { symbol: "ACME", side: "SELL", price: "10.00", qty: 3 });
    h.risk.decide = (r) => (r.orderType === "MARKET" ? { approved: true, limitPrice: 1050 } : { approved: true });
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", type: "MARKET", qty: 2 });
    expect(h.risk.checks[1]).toMatchObject({ action: "PLACE", orderType: "MARKET", price: null, qty: 2 });
    expect(JSON.parse(h.bus.records("commands.ACME")[1]!.value)).toMatchObject({ orderType: "LIMIT", timeInForce: "IOC", price: 1050 });
    expect(res.json().events).toEqual([
      expect.objectContaining({ type: "OrderPlaced", price: "10.50" }),
      expect.objectContaining({ type: "TradeExecuted", price: "10.00", qty: 2 }),
    ]);
  });

  it("asks risk to confirm cancels and surfaces its rejection", async () => {
    h = await harness();
    const id = (await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).json().orderId;
    h.risk.decide = (r) => (r.action === "CANCEL" ? { approved: false, reason: "NOT_OWN_OPEN_ORDER", message: "not yours" } : { approved: true });
    const res = await h.app.inject({ method: "DELETE", url: `/api/orders/${id}`, headers: asAlice });
    expect(res.json().events[0]).toMatchObject({ type: "RiskRejected", reason: "NOT_OWN_OPEN_ORDER" });
    expect(h.risk.checks[1]).toMatchObject({ action: "CANCEL", orderId: id, symbol: "ACME" });
    expect(h.bus.records("commands.ACME")).toHaveLength(1);
  });

  it("releases an amend reservation when the amend cannot be published", async () => {
    h = await harness();
    const id = (await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).json().orderId;
    h.bus.failNextPublish = new Error("broker down");
    const res = await h.app.inject({ method: "PATCH", url: `/api/orders/${id}`, headers: asAlice, payload: { qty: 5 } });
    expect(res.statusCode).toBe(503);
    expect(h.risk.releases).toEqual([expect.objectContaining({ orderId: id, kind: "amend" })]);
  });

  it("does not release on an engine timeout (the portfolio consumer settles from events)", async () => {
    h = await harness({ timeoutMs: 30 });
    h.engine.paused = true;
    expect((await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).statusCode).toBe(504);
    expect(h.risk.releases).toHaveLength(0);
  });
});

describe("command -> event correlation", () => {
  it("returns the engine result for the caller's own command, with seq and decimal prices", async () => {
    h = await harness();
    const ask = await place(asBob, { symbol: "ACME", side: "SELL", price: "100.25", qty: 5 });
    const bid = await place(asAlice, { symbol: "ACME", side: "BUY", price: "100.50", qty: 3 });
    expect(ask.json().seq).toBe(1);
    const body = bid.json();
    expect(body.seq).toBe(2);
    expect(body.symbol).toBe("ACME");
    expect(body.events).toEqual([
      expect.objectContaining({ type: "OrderPlaced", orderId: body.orderId, price: "100.50", qty: 3 }),
      expect.objectContaining({ type: "TradeExecuted", price: "100.25", qty: 3, makerOrderId: ask.json().orderId, takerOrderId: body.orderId }),
    ]);
    // On the bus the engine only ever sees integer ticks.
    expect(JSON.parse(h.bus.records("commands.ACME")[1]!.value)).toMatchObject({ type: "Place", price: 10050, qty: 3 });
  });

  it("matches concurrent responses to the right callers even when they arrive out of order", async () => {
    h = await harness();
    h.engine.paused = true;
    const pending = [
      place(asAlice, { symbol: "ACME", side: "BUY", price: "10.00", qty: 1 }),
      place(asBob, { symbol: "ACME", side: "SELL", price: "20.00", qty: 2 }),
      place(asAlice, { symbol: "GLOBX", side: "BUY", price: "30.00", qty: 3 }),
    ];
    await new Promise((r) => setTimeout(r, 20));
    expect(h.engine.held).toHaveLength(3);
    await h.engine.release("lifo");
    const [a, b, c] = (await Promise.all(pending)).map((r) => r.json());
    expect(a.events[0]).toMatchObject({ type: "OrderPlaced", price: "10.00", qty: 1 });
    expect(b.events[0]).toMatchObject({ type: "OrderPlaced", price: "20.00", qty: 2 });
    expect(c.events[0]).toMatchObject({ type: "OrderPlaced", price: "30.00", qty: 3 });
    expect(c.symbol).toBe("GLOBX");
    expect([c.seq, b.seq, a.seq]).toEqual([1, 2, 3]);
    expect(new Set([a.correlationId, b.correlationId, c.correlationId]).size).toBe(3);
  });

  it("ignores event records for commands it did not send", async () => {
    h = await harness();
    await h.bus.publish("events.ACME", "ACME", JSON.stringify({ seq: 99, symbol: "ACME", correlationId: "someone-else", participantId: 7, events: [] }));
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(res.json().seq).toBe(1);
  });

  it("times out with 504 when no correlated event arrives", async () => {
    h = await harness({ timeoutMs: 50 });
    h.engine.paused = true;
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 });
    expect(res.statusCode).toBe(504);
    expect(res.json()).toMatchObject({ reason: "ENGINE_TIMEOUT" });
    expect(res.json().correlationId).toBeTypeOf("string");
  });
});

describe("orders REST shape", () => {
  it("cancels and amends by id, routed to the order's symbol", async () => {
    h = await harness();
    const id = (await place(asAlice, { symbol: "NVTA", side: "SELL", price: "5.00", qty: 4 })).json().orderId;
    const amend = await h.app.inject({ method: "PATCH", url: `/api/orders/${id}`, headers: asAlice, payload: { qty: 2 } });
    expect(amend.json()).toMatchObject({ symbol: "NVTA", events: [{ type: "OrderAmended", oldQty: 4, newQty: 2, oldPrice: "5.00", newPrice: "5.00", priorityRetained: true }] });
    const cancel = await h.app.inject({ method: "DELETE", url: `/api/orders/${id}`, headers: asAlice });
    expect(cancel.json()).toMatchObject({ symbol: "NVTA", events: [{ type: "OrderCancelled", orderId: id, cancelledQty: 2 }] });
    expect(h.bus.records("commands.NVTA").map((r) => JSON.parse(r.value).type)).toEqual(["Place", "Amend", "Cancel"]);
  });

  it("accepts form-encoded bodies like the Java API", async () => {
    h = await harness();
    const res = await h.app.inject({
      method: "POST",
      url: "/api/orders",
      headers: { ...asAlice, "content-type": "application/x-www-form-urlencoded" },
      payload: "symbol=acme&side=buy&type=limit&price=12.34&qty=7&timeInForce=ioc",
    });
    expect(res.statusCode).toBe(200);
    expect(JSON.parse(h.bus.records("commands.ACME")[0]!.value)).toMatchObject({ side: "BUY", orderType: "LIMIT", timeInForce: "IOC", price: 1234, qty: 7 });
  });

  it("rejects touching another participant's order without publishing", async () => {
    h = await harness();
    const id = (await place(asAlice, { symbol: "ACME", side: "BUY", price: "1", qty: 1 })).json().orderId;
    const res = await h.app.inject({ method: "DELETE", url: `/api/orders/${id}`, headers: asBob });
    expect(res.json().events[0]).toMatchObject({ type: "Rejected", reason: "NOT_OWN_OPEN_ORDER" });
    expect(h.bus.records("commands.ACME")).toHaveLength(1);
  });

  it("validates input with 400 before admission", async () => {
    h = await harness();
    const cases: [Record<string, unknown>, string][] = [
      [{ side: "BUY", price: "1", qty: 1 }, "symbol is required"],
      [{ symbol: "XXX", side: "BUY", price: "1", qty: 1 }, "unknown symbol"],
      [{ symbol: "ACME", side: "BUY", price: "1.005", qty: 1 }, "multiple of 0.01"],
      [{ symbol: "ACME", side: "BUY", price: "abc", qty: 1 }, "decimal number"],
      [{ symbol: "ACME", side: "BUY", price: "1", qty: "1.5" }, "positive integer"],
      [{ symbol: "ACME", side: "HOLD", price: "1", qty: 1 }, "side must be"],
      [{ symbol: "ACME", side: "BUY", qty: 1 }, "price is required"],
    ];
    for (const [body, message] of cases) {
      const res = await place(asAlice, body);
      expect(res.statusCode, JSON.stringify(body)).toBe(400);
      expect(res.json().error).toContain(message);
    }
    expect((await h.app.inject({ method: "DELETE", url: "/api/orders/424242", headers: asAlice })).statusCode).toBe(404);
    expect(h.risk.checks).toHaveLength(0);
  });

  it("places market orders without a price", async () => {
    h = await harness();
    await place(asBob, { symbol: "ACME", side: "SELL", price: "9.99", qty: 2 });
    const res = await place(asAlice, { symbol: "ACME", side: "BUY", type: "MARKET", qty: 2 });
    expect(res.json().events).toEqual([
      expect.objectContaining({ type: "OrderPlaced", price: null }),
      expect.objectContaining({ type: "TradeExecuted", price: "9.99", qty: 2 }),
    ]);
  });

  it("lists symbols with trading parameters", async () => {
    h = await harness();
    const res = await h.app.inject({ method: "GET", url: "/api/symbols" });
    expect(res.json().symbols.map((s: { symbol: string }) => s.symbol)).toEqual(["ACME", "GLOBX", "INITD", "NVTA"]);
  });
});
