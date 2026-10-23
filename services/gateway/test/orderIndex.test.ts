import { describe, expect, it } from "vitest";

import type { EventRecord } from "../src/contracts.js";
import { OrderIndex } from "../src/orderIndex.js";

function placed(participantId: number, orderId: number, qty = 10): EventRecord {
  return {
    symbol: "ACME",
    seq: orderId,
    participantId,
    command: { orderType: "LIMIT" },
    events: [{ type: "OrderPlaced", orderId, side: "BUY", orderType: "LIMIT", timeInForce: "GTC", price: 100, qty }],
  } as unknown as EventRecord;
}

describe("OrderIndex", () => {
  it("can be constructed", () => {
    const index = new OrderIndex();
    expect(index).toBeDefined();
    expect(index.openOrders(1)).toBe(0);
  });

  it("counts resting orders and in-flight places per participant", () => {
    const index = new OrderIndex();
    index.apply(placed(1, 1));
    index.apply(placed(1, 2));
    index.apply(placed(2, 3));
    index.placeStarted(1);
    expect(index.openOrders(1)).toBe(3);
    expect(index.openCount(1)).toBe(2);
    expect(index.openOrders(2)).toBe(1);
    index.placeFinished(1);
    expect(index.openOrders(1)).toBe(2);
    expect(index.owner(3)).toBe(2);
    expect(index.symbol(3)).toBe("ACME");
  });

  it("closes orders when they are cancelled", () => {
    const index = new OrderIndex();
    index.apply(placed(1, 1));
    index.apply({
      symbol: "ACME",
      seq: 9,
      participantId: 1,
      events: [{ type: "OrderCancelled", orderId: 1, qty: 10 }],
    } as unknown as EventRecord);
    expect(index.openOrders(1)).toBe(0);
    expect(index.owner(1)).toBeUndefined();
    expect(index.symbol(1)).toBe("ACME");
  });

  it("answers the open-order cap quickly with a few thousand resting orders", () => {
    const index = new OrderIndex();
    for (let i = 1; i <= 5_000; i++) {
      index.apply(placed(i % 50, i));
    }
    const started = performance.now();
    for (let i = 0; i < 200; i++) {
      index.openOrders(7);
    }
    expect(performance.now() - started).toBeLessThan(250);
    expect(index.openOrders(7)).toBe(100);
  });
});
