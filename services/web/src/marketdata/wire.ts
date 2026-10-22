/**
 * Market-data wire format (see ARCHITECTURE.md "Market data"): one message per engine sequence number, prices in
 * integer ticks. The first message on a subscription is a snapshot; deltas follow as seq S+1, S+2, ... and anything
 * else is a gap that requires a resync. A book that has never traded snapshots at seq -1.
 */
export type WireLevel = [price: number, qty: number, orders: number];
export type WireLevelChange = [op: "a" | "u" | "r", side: "B" | "S", price: number, qty: number, orders: number];

export interface WireTrade {
  type: "TradeExecuted";
  n?: number;
  price: number;
  qty: number;
  takerSide: "BUY" | "SELL" | null;
  maker?: string;
  taker?: string;
  makerOrderId?: number;
  takerOrderId?: number;
  /** Epoch ms if the feed stamps it; otherwise the receive time is used. */
  ts?: number;
}

export interface WireOtherEvent {
  type: "OrderPlaced" | "OrderCancelled" | "OrderAmended" | "OrderRejected";
  orderId: number;
  [key: string]: unknown;
}

export type WireEvent = WireTrade | WireOtherEvent;

export interface WireSnapshot {
  type: "snapshot";
  symbol?: string;
  seq: number;
  /** connect/resync/reset (fake exchange); subscribe/resync/slow-consumer/requested (market-data service). */
  reason?: string;
  bids: WireLevel[];
  asks: WireLevel[];
  trades: WireTrade[];
  events: WireEvent[];
}

export interface WireDelta {
  type: "delta";
  symbol?: string;
  seq: number;
  levels: WireLevelChange[];
  events: WireEvent[];
}

export type MarketDataMessage = WireSnapshot | WireDelta;

export function isTrade(e: WireEvent): e is WireTrade {
  return e.type === "TradeExecuted";
}
