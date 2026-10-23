import type { FastifyInstance } from "fastify";
import pg from "pg";
import { afterAll, beforeAll, describe, expect, inject, it } from "vitest";

import { buildApi } from "../src/api.js";
import { toRows } from "../src/events.js";
import { HistoryWriter } from "../src/writer.js";

let pool: pg.Pool;
let app: FastifyInstance;

// Participants 2001..2003 and symbol NVTA are used only here, so the consumer tests' rows don't interfere.
beforeAll(async () => {
  pool = new pg.Pool({ connectionString: inject("databaseUrl"), max: 2 });
  app = await buildApi(pool);
  const writer = new HistoryWriter(pool);
  const at = (ms: number) => new Date(1792670000000 + ms);
  await writer.write(
    toRows(
      {
        symbol: "NVTA",
        seq: 0,
        participantId: 2001,
        events: [
          {
            type: "OrderPlaced",
            orderId: 1,
            side: "SELL",
            orderType: "LIMIT",
            timeInForce: "GTC",
            price: 5000,
            qty: 10,
          },
        ],
      },
      "NVTA",
      at(0),
    ),
  );
  await writer.write(
    toRows(
      {
        symbol: "NVTA",
        seq: 1,
        participantId: 2002,
        events: [
          { type: "OrderPlaced", orderId: 2, side: "BUY", orderType: "LIMIT", timeInForce: "IOC", price: 5000, qty: 4 },
          {
            type: "TradeExecuted",
            makerOrderId: 1,
            takerOrderId: 2,
            makerParticipantId: 2001,
            takerParticipantId: 2002,
            takerSide: "BUY",
            price: 5000,
            qty: 4,
          },
        ],
      },
      "NVTA",
      at(1000),
    ),
  );
  await writer.write(
    toRows(
      {
        symbol: "NVTA",
        seq: 2,
        participantId: 2001,
        events: [{ type: "OrderCancelled", orderId: 1, cancelledQty: 6, reason: "USER_REQUESTED" }],
      },
      "NVTA",
      at(2000),
    ),
  );
});
afterAll(async () => {
  await app?.close();
  await pool?.end();
});

const iso = (ms: number) => new Date(1792670000000 + ms).toISOString();
const get = async (url: string) => app.inject({ method: "GET", url });

describe("history read API", () => {
  it("lists a participant's order lifecycle newest first", async () => {
    const res = await get("/v1/orders?participantId=2001");
    expect(res.statusCode).toBe(200);
    const { orders } = res.json();
    expect(orders.map((o: { eventType: string }) => o.eventType)).toEqual(["CANCELLED", "PLACED"]);
    expect(orders[1]).toEqual({
      symbol: "NVTA",
      seq: 0,
      eventIndex: 0,
      orderId: 1,
      participantId: 2001,
      eventType: "PLACED",
      side: "SELL",
      orderType: "LIMIT",
      timeInForce: "GTC",
      priceTicks: 5000,
      qty: 10,
      reason: null,
      details: {},
      eventTime: iso(0),
    });
  });

  it("returns trades where the participant is maker or taker", async () => {
    for (const pid of [2001, 2002]) {
      const { trades } = (await get(`/v1/trades?participantId=${pid}`)).json();
      expect(trades).toEqual([
        {
          symbol: "NVTA",
          seq: 1,
          eventIndex: 1,
          makerOrderId: 1,
          takerOrderId: 2,
          makerParticipantId: 2001,
          takerParticipantId: 2002,
          aggressorSide: "BUY",
          priceTicks: 5000,
          qty: 4,
          eventTime: iso(1000),
        },
      ]);
    }
    expect((await get("/v1/trades?participantId=2003")).json()).toEqual({ trades: [] });
  });

  it("filters by symbol and caps with limit", async () => {
    expect((await get("/v1/orders?participantId=2001&symbol=ACME")).json()).toEqual({ orders: [] });
    const { orders } = (await get("/v1/orders?participantId=2001&symbol=NVTA&limit=1")).json();
    expect(orders).toHaveLength(1);
    expect(orders[0].eventType).toBe("CANCELLED");
  });

  it("rejects unscoped or malformed queries with 400", async () => {
    for (const url of [
      "/v1/orders",
      "/v1/trades",
      "/v1/orders?participantId=abc",
      "/v1/trades?participantId=0",
      "/v1/orders?participantId=1&symbol=bad sym",
      "/v1/orders?participantId=1&limit=5000",
    ]) {
      const res = await get(url);
      expect(res.statusCode, url).toBe(400);
      expect(res.json().error).toBe("bad_request");
    }
  });
});

describe("single order lookup", () => {
  it("returns an order's lifecycle, scoped to its owner", async () => {
    const res = await get("/v1/orders/1?participantId=2001&symbol=NVTA");
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.orderId).toBe(1);
    expect(body.events[0]).toMatchObject({ symbol: "NVTA", orderId: 1, eventType: "PLACED", side: "SELL" });
    expect((await get("/v1/orders/1?participantId=2002")).statusCode).toBe(404);
  });

  it("rejects bad ids", async () => {
    const res = await get("/v1/orders/abc?participantId=2001");
    expect(res.statusCode).toBe(400);
    expect(res.json().error).toBeTruthy();
  });

  it("requires a participant", async () => {
    expect((await get("/v1/orders/1")).statusCode).toBeGreaterThanOrEqual(400);
  });
});
