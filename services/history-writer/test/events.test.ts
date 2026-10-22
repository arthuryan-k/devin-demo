import { describe, expect, it } from "vitest";

import { MalformedRecordError, toRows } from "../src/events.js";

const NOW = new Date("2026-10-22T12:00:00Z");

describe("toRows", () => {
  it("maps placement, fill and remainder cancel of one command", () => {
    const rows = toRows(
      {
        symbol: "ACME", seq: 42, participantId: 1001, correlationId: "c-1", ts: 1792670000000,
        events: [
          { type: "OrderPlaced", orderId: 7, side: "BUY", orderType: "LIMIT", timeInForce: "IOC", price: 10125, qty: 10, seqNum: 9 },
          { type: "TradeExecuted", makerOrderId: 3, takerOrderId: 7, makerParticipantId: 1002, takerSide: "BUY", price: 10100, qty: 4 },
          { type: "OrderCancelled", orderId: 7, cancelledQty: 6, reason: "UNFILLED_REMAINDER" },
        ],
      },
      "ACME",
      NOW,
    );
    expect(rows.orders).toHaveLength(2);
    expect(rows.orders[0]).toMatchObject({
      symbol: "ACME", seq: 42n, eventIndex: 0, orderId: 7n, participantId: 1001n, eventType: "PLACED", side: "BUY",
      orderType: "LIMIT", timeInForce: "IOC", priceTicks: 10125n, qty: 10n,
      details: { correlationId: "c-1", bookSeqNum: "9" }, eventTime: new Date(1792670000000),
    });
    expect(rows.orders[1]).toMatchObject({ eventIndex: 2, eventType: "CANCELLED", qty: 6n, reason: "UNFILLED_REMAINDER" });
    expect(rows.trades).toEqual([
      expect.objectContaining({
        eventIndex: 1, makerOrderId: 3n, takerOrderId: 7n, makerParticipantId: 1002n, takerParticipantId: 1001n,
        aggressorSide: "BUY", priceTicks: 10100n, qty: 4n,
      }),
    ]);
  });

  it("accepts decimal-string prices, string ids, a nested trade object and amend fields", () => {
    const rows = toRows(
      {
        seq: "5", participantId: "1003",
        events: [
          { type: "OrderAmended", orderId: "9", oldPrice: "100.5", newPrice: "101.25", oldQty: "5", newQty: "3", priorityRetained: false },
          { type: "TradeExecuted", trade: { makerOrderId: "1", takerOrderId: "9", price: "101.25", qty: "3" } },
          { type: "OrderPlaced", orderId: 10, side: "SELL", price: 0, qty: 1 },
        ],
      },
      "GLOBX",
      NOW,
    );
    expect(rows.orders[0]).toMatchObject({
      symbol: "GLOBX", eventType: "AMENDED", priceTicks: 10125n, qty: 3n, eventTime: NOW,
      details: { oldPriceTicks: "10050", oldQty: "5", priorityRetained: false },
    });
    expect(rows.trades[0]).toMatchObject({ priceTicks: 10125n, makerParticipantId: null, aggressorSide: null });
    expect(rows.orders[1]).toMatchObject({ eventType: "PLACED", priceTicks: null }); // market order
  });

  it("rejects malformed records", () => {
    expect(() => toRows({ seq: 1 }, "ACME", NOW)).toThrow(MalformedRecordError);
    expect(() => toRows({ seq: 1, symbol: "GLOBX", events: [] }, "ACME", NOW)).toThrow(/topic of ACME/);
    expect(() => toRows({ seq: 1.5, events: [] }, "ACME", NOW)).toThrow(/seq/);
    expect(() => toRows({ seq: 1, events: [{ type: "OrderPlaced", orderId: 1, price: "1.234", qty: 1 }] }, "ACME", NOW))
      .toThrow(/tick price/);
  });
});
