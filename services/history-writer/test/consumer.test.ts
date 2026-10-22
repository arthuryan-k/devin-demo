import { Kafka, type Producer } from "kafkajs";
import pg from "pg";
import { afterAll, beforeAll, describe, expect, inject, it } from "vitest";

import { HistoryConsumer } from "../src/consumer.js";
import { HistoryWriter } from "../src/writer.js";

let pool: pg.Pool;
let producer: Producer;
let consumer: HistoryConsumer;
const skipped: string[] = [];

beforeAll(async () => {
  pool = new pg.Pool({ connectionString: inject("databaseUrl") });
  const kafka = new Kafka({ clientId: "history-writer-test", brokers: [inject("kafkaBrokers")] });
  const admin = kafka.admin();
  await admin.connect();
  await admin.createTopics({
    waitForLeaders: true,
    topics: ["ACME", "GLOBX"].map((s) => ({ topic: `events.${s}`, numPartitions: 1 })),
  });
  await admin.disconnect();
  producer = kafka.producer();
  await producer.connect();
  consumer = new HistoryConsumer(new HistoryWriter(pool), {
    brokers: [inject("kafkaBrokers")],
    groupId: "history-writer-test",
    discoveryIntervalMs: 500,
    log: (m) => { if (m.startsWith("skipping")) skipped.push(m); },
  });
  await consumer.start();
});

afterAll(async () => {
  await consumer?.stop();
  await producer?.disconnect();
  await pool?.end();
});

async function publish(symbol: string, ...records: unknown[]) {
  await producer.send({
    topic: `events.${symbol}`,
    messages: records.map((r) => ({ key: symbol, value: typeof r === "string" ? r : JSON.stringify(r) })),
  });
}

async function eventually<T>(query: () => Promise<T>, done: (v: T) => boolean, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const v = await query();
    if (done(v) || Date.now() > deadline) return v;
    await new Promise((r) => setTimeout(r, 200));
  }
}

const count = async (table: string, symbol: string) =>
  Number((await pool.query(`SELECT count(*) FROM ${table} WHERE symbol = $1`, [symbol])).rows[0].count);

describe("events.<symbol> → history tables", () => {
  it("writes order lifecycle and fills, resolving the maker from its earlier placement", async () => {
    const resting = {
      symbol: "ACME", seq: 1, participantId: 1001, ts: 1792670000000,
      events: [{ type: "OrderPlaced", orderId: 1, side: "SELL", orderType: "LIMIT", timeInForce: "GTC", price: 10100, qty: 5 }],
    };
    const taking = {
      symbol: "ACME", seq: 2, participantId: 1002, correlationId: "gw-77", ts: 1792670000500,
      events: [
        { type: "OrderPlaced", orderId: 2, side: "BUY", orderType: "LIMIT", timeInForce: "IOC", price: 10150, qty: 8 },
        { type: "TradeExecuted", makerOrderId: 1, takerOrderId: 2, price: 10100, qty: 5 },
        { type: "OrderCancelled", orderId: 2, cancelledQty: 3, reason: "UNFILLED_REMAINDER" },
      ],
    };
    const rejected = { symbol: "ACME", seq: 3, participantId: 1002, events: [{ type: "OrderRejected", orderId: 3, reason: "NON_POSITIVE_QUANTITY" }] };
    await publish("ACME", resting, taking, rejected);

    await eventually(() => count("orders_history", "ACME"), (n) => n === 4);
    const orders = await pool.query(
      `SELECT seq, event_index, order_id, participant_id, event_type, side, price_ticks, qty, reason, details
       FROM orders_history WHERE symbol = 'ACME' ORDER BY seq, event_index`,
    );
    expect(orders.rows).toEqual([
      expect.objectContaining({ seq: "1", event_index: 0, order_id: "1", participant_id: "1001", event_type: "PLACED", side: "SELL", price_ticks: "10100", qty: "5" }),
      expect.objectContaining({ seq: "2", event_index: 0, order_id: "2", participant_id: "1002", event_type: "PLACED", side: "BUY", details: { correlationId: "gw-77" } }),
      expect.objectContaining({ seq: "2", event_index: 2, order_id: "2", event_type: "CANCELLED", qty: "3", reason: "UNFILLED_REMAINDER" }),
      expect.objectContaining({ seq: "3", event_index: 0, order_id: "3", participant_id: "1002", event_type: "REJECTED", reason: "NON_POSITIVE_QUANTITY" }),
    ]);

    const trades = await pool.query(`SELECT * FROM trades_history WHERE symbol = 'ACME'`);
    expect(trades.rows).toEqual([
      expect.objectContaining({
        seq: "2", event_index: 1, maker_order_id: "1", taker_order_id: "2", maker_participant_id: "1001",
        taker_participant_id: "1002", aggressor_side: "BUY", price_ticks: "10100", qty: "5",
        event_time: new Date(1792670000500),
      }),
    ]);
  });

  it("is idempotent under redelivery and skips poison messages without stalling", async () => {
    const fill = {
      symbol: "GLOBX", seq: 10, participantId: 1004,
      events: [
        { type: "OrderPlaced", orderId: 20, side: "SELL", price: 5000, qty: 2 },
        { type: "TradeExecuted", makerOrderId: 19, takerOrderId: 20, makerParticipantId: 1003, price: 5000, qty: 2 },
      ],
    };
    await publish("GLOBX", fill, "{not json", { symbol: "GLOBX", seq: "x", events: [] }, fill);
    const marker = { symbol: "GLOBX", seq: 11, participantId: 1004, events: [{ type: "OrderRejected", orderId: 21, reason: "BUSY" }] };
    await publish("GLOBX", marker);

    await eventually(() => count("orders_history", "GLOBX"), (n) => n >= 2);
    expect(await count("orders_history", "GLOBX")).toBe(2);
    expect(await count("trades_history", "GLOBX")).toBe(1);
    const trade = (await pool.query(`SELECT * FROM trades_history WHERE symbol = 'GLOBX'`)).rows[0];
    expect(trade).toMatchObject({ maker_participant_id: "1003", taker_participant_id: "1004", aggressor_side: "SELL" });
    expect(skipped.filter((m) => m.includes("events.GLOBX"))).toHaveLength(2);
  });
});

describe("topic discovery", () => {
  it("picks up an events.<symbol> topic created after the consumer started", async () => {
    expect(consumer.subscribed).toEqual(["events.ACME", "events.GLOBX"]);
    const admin = new Kafka({ clientId: "history-writer-test-admin", brokers: [inject("kafkaBrokers")] }).admin();
    await admin.connect();
    await admin.createTopics({ waitForLeaders: true, topics: [{ topic: "events.INITD", numPartitions: 1 }] });
    await admin.disconnect();
    await eventually(async () => consumer.subscribed.includes("events.INITD"), (v) => v);
    await publish("INITD", {
      symbol: "INITD", seq: 0, participantId: 1003,
      events: [{ type: "OrderPlaced", orderId: 1, side: "BUY", orderType: "LIMIT", timeInForce: "GTC", price: 5000, qty: 1 }],
    });
    expect(await eventually(() => count("orders_history", "INITD"), (n) => n === 1)).toBe(1);
  });
});
