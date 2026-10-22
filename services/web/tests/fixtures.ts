import type { MarketDataMessage, WireSnapshot } from "../src/marketdata/wire";

/** A recorded-style feed for ACME: connect snapshot at seq 41 followed by consecutive deltas. */
export const ACME_SNAPSHOT: WireSnapshot = {
  type: "snapshot",
  symbol: "ACME",
  seq: 41,
  reason: "connect",
  bids: [
    [10000, 300, 3],
    [9999, 150, 2],
    [9998, 500, 4],
  ],
  asks: [
    [10002, 200, 2],
    [10003, 120, 1],
    [10005, 800, 5],
  ],
  trades: [{ type: "TradeExecuted", n: 7, price: 10001, qty: 25, takerSide: "BUY", maker: "P1001", taker: "P1002" }],
  events: [],
};

export const ACME_DELTAS: MarketDataMessage[] = [
  // A taker lifts the whole best ask, then 10003 becomes the touch.
  {
    type: "delta",
    symbol: "ACME",
    seq: 42,
    levels: [["r", "S", 10002, 0, 0]],
    events: [{ type: "TradeExecuted", n: 8, price: 10002, qty: 200, takerSide: "BUY", maker: "P1001", taker: "P1003" }],
  },
  // A new bid improves the market and an existing bid level is resized.
  {
    type: "delta",
    symbol: "ACME",
    seq: 43,
    levels: [
      ["a", "B", 10001, 75, 1],
      ["u", "B", 9999, 90, 1],
    ],
    events: [{ type: "OrderPlaced", orderId: 77, participant: "P1004", side: "BUY", price: 10001, qty: 75 }],
  },
];
