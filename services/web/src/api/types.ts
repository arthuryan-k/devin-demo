export type Side = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type TimeInForce = "GTC" | "IOC" | "FOK";
export type OrderStatus = "NEW" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED" | "REJECTED";

/** Tradable symbol and its trading parameters (reference-data service). */
export interface Instrument {
  symbol: string;
  name: string;
  /** Minimum price increment in currency units, e.g. 0.01. */
  tickSize: number;
  lotSize: number;
}

export interface Session {
  token: string;
  username: string;
  participantId: number;
  accountId: string;
}

export interface Position {
  symbol: string;
  qty: number;
  reservedQty: number;
  availableQty: number;
  avgCost: number | null;
  markPrice: number | null;
  marketValue: number;
  realizedPnl: number;
  unrealizedPnl: number;
}

/** Shared cash balance plus per-symbol positions. */
export interface Portfolio {
  cash: number;
  reservedCash: number;
  availableCash: number;
  startingCash: number;
  equity: number;
  realizedPnl: number;
  unrealizedPnl: number;
  sessionPnl: number;
  positions: Position[];
}

export interface OpenOrder {
  id: number;
  symbol: string;
  side: Side;
  type: OrderType;
  timeInForce: TimeInForce;
  price: number | null;
  /** Remaining (unfilled) quantity. */
  qty: number;
  filledQty: number;
  reservedCash: number;
  createdAt: number;
}

export interface AccountView {
  portfolio: Portfolio;
  openOrders: OpenOrder[];
}

export interface OrderRequest {
  symbol: string;
  side: Side;
  type: OrderType;
  timeInForce: TimeInForce;
  price?: number;
  qty: number;
}

export interface OrderResult {
  orderId: number;
  status: OrderStatus;
  filledQty: number;
  reason?: string;
  seq?: number;
}

export interface OrderHistoryRow {
  orderId: number;
  symbol: string;
  side: Side;
  type: OrderType;
  timeInForce: TimeInForce;
  price: number | null;
  qty: number;
  filledQty: number;
  avgFillPrice: number | null;
  status: OrderStatus;
  reason?: string;
  createdAt: number;
  updatedAt: number;
}

export interface TradeHistoryRow {
  tradeId: string;
  orderId: number;
  symbol: string;
  side: Side;
  price: number;
  qty: number;
  liquidity: "MAKER" | "TAKER";
  time: number;
}

export interface HistoryQuery {
  symbol?: string;
  limit?: number;
}

export interface PricePoint {
  /** Epoch milliseconds. */
  time: number;
  price: number;
  qty: number;
}
