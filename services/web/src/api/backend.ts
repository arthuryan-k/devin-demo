import type { MarketDataMessage } from "../marketdata/wire";
import type {
  AccountView,
  HistoryQuery,
  Instrument,
  OrderHistoryRow,
  OrderRequest,
  OrderResult,
  PricePoint,
  Session,
  TradeHistoryRow,
} from "./types";

export type Unsubscribe = () => void;
export type FeedStatus = "connecting" | "live" | "resyncing" | "disconnected";

export interface MarketSubscription {
  close(): void;
  /** Ask the feed for a fresh snapshot (called by the client on a sequence gap). */
  resync(): void;
}

export interface MarketFeedHandlers {
  onMessage(msg: MarketDataMessage): void;
  onStatus(status: FeedStatus): void;
}

/**
 * Everything the UI needs from the trading backend. `HttpBackend` talks to the gateway (REST) and the market-data
 * service (WebSocket); `MockBackend` is an in-browser fake used by the static GitHub Pages build.
 */
export interface Backend {
  readonly kind: "live" | "mock";
  /** Session restored from storage, if any. */
  restoreSession(): Session | null;
  login(username: string, password: string): Promise<Session>;
  signup(username: string, password: string): Promise<Session>;
  logout(): void;

  instruments(): Promise<Instrument[]>;
  subscribeMarket(symbol: string, handlers: MarketFeedHandlers): MarketSubscription;
  /** Earlier prints for the chart; may be empty when the backend keeps no price history. */
  priceHistory(symbol: string): Promise<PricePoint[]>;

  /** Portfolio and open orders, pushed (mock) or polled (live); fires immediately and after every change. */
  subscribeAccount(onUpdate: (view: AccountView) => void, onError?: (err: Error) => void): Unsubscribe;
  placeOrder(order: OrderRequest): Promise<OrderResult>;
  amendOrder(orderId: number, change: { price?: number; qty?: number }): Promise<OrderResult>;
  cancelOrder(orderId: number): Promise<OrderResult>;

  orderHistory(query?: HistoryQuery): Promise<OrderHistoryRow[]>;
  tradeHistory(query?: HistoryQuery): Promise<TradeHistoryRow[]>;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly reason: string,
    message?: string,
  ) {
    super(message ?? reason);
    this.name = "ApiError";
  }
}
