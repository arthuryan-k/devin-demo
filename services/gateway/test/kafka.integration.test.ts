import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { AdmissionControl, DEFAULT_LIMITS } from "../src/admission.js";
import { buildApp } from "../src/app.js";
import { commandsTopic, eventsTopic } from "../src/contracts.js";
import { Correlator } from "../src/correlator.js";
import { OrderIdAllocator } from "../src/ids.js";
import { KafkaBus } from "../src/kafkaBus.js";
import { OrderIndex } from "../src/orderIndex.js";
import { OrderService } from "../src/orders.js";
import { SymbolDirectory } from "../src/symbols.js";
import { FakeAuth, FakeEngine, FakeRisk, asAlice, asBob } from "./fakes.js";

const brokers = process.env.KAFKA_BROKERS?.split(",");

/** Runs against a real broker: `KAFKA_BROKERS=localhost:19092 npm test`. */
describe.skipIf(!brokers)("over Kafka (Redpanda)", () => {
  const symbol = `IT${Date.now().toString(36).toUpperCase()}`;
  const gatewayBus = new KafkaBus(brokers ?? [], "gateway-it");
  const engineBus = new KafkaBus(brokers ?? [], "engine-it");
  let app: Awaited<ReturnType<typeof buildApp>>;

  beforeAll(async () => {
    const topics = [commandsTopic(symbol), eventsTopic(symbol)];
    await gatewayBus.connect(topics);
    await engineBus.connect();
    new FakeEngine(engineBus, [symbol]);
    const symbols = new SymbolDirectory([{ symbol, tickSize: "0.01", lotSize: 1 }]);
    const orders = new OrderService({
      bus: gatewayBus,
      risk: new FakeRisk(),
      symbols,
      admission: new AdmissionControl(DEFAULT_LIMITS),
      index: new OrderIndex(),
      correlator: new Correlator(20_000),
      ids: new OrderIdAllocator(0),
    });
    await orders.start();
    app = await buildApp({ orders, auth: new FakeAuth(), symbols });
  }, 60_000);

  afterAll(async () => {
    await app?.close();
    await Promise.all([gatewayBus.close(), engineBus.close()]);
  });

  it("publishes to commands.<symbol> and correlates the events.<symbol> reply", async () => {
    const ask = await app.inject({
      method: "POST",
      url: "/api/orders",
      headers: asBob,
      payload: { symbol, side: "SELL", price: "10.10", qty: 4 },
    });
    expect(ask.statusCode).toBe(200);
    const [a, b] = await Promise.all([
      app.inject({
        method: "POST",
        url: "/api/orders",
        headers: asAlice,
        payload: { symbol, side: "BUY", price: "10.10", qty: 1 },
      }),
      app.inject({
        method: "POST",
        url: "/api/orders",
        headers: asAlice,
        payload: { symbol, side: "BUY", price: "9.00", qty: 2 },
      }),
    ]);
    expect(ask.json().seq).toBe(1);
    expect(new Set([a.json().seq, b.json().seq])).toEqual(new Set([2, 3]));
    expect(a.json().events.map((e: { type: string }) => e.type)).toEqual(["OrderPlaced", "TradeExecuted"]);
    expect(b.json().events).toEqual([expect.objectContaining({ type: "OrderPlaced", price: "9.00", qty: 2 })]);
    const cancel = await app.inject({ method: "DELETE", url: `/api/orders/${b.json().orderId}`, headers: asAlice });
    expect(cancel.json().events[0]).toMatchObject({ type: "OrderCancelled", cancelledQty: 2 });
  }, 60_000);
});
