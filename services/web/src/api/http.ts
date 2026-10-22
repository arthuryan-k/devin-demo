import type { MarketDataMessage } from "../marketdata/wire";
import { ApiError, type Backend, type MarketFeedHandlers, type MarketSubscription, type Unsubscribe } from "./backend";
import type {
  AccountView,
  HistoryQuery,
  Instrument,
  OpenOrder,
  OrderHistoryRow,
  OrderRequest,
  OrderResult,
  OrderStatus,
  Portfolio,
  Position,
  PricePoint,
  Session,
  TradeHistoryRow,
} from "./types";

const SESSION_KEY = "tws.session";

export interface HttpBackendOptions {
  /** Gateway origin; empty means same origin (the Vite dev server proxies `/api` to the gateway). */
  apiBase?: string;
  /** Market-data WebSocket URL template; `{symbol}` is replaced. Defaults to `<origin>/ws/marketdata/{symbol}`. */
  marketDataUrl?: string;
  accountPollMs?: number;
  storage?: Storage;
  fetchImpl?: typeof fetch;
  webSocketImpl?: typeof WebSocket;
}

type Json = Record<string, unknown>;

const num = (v: unknown, fallback = 0): number => {
  if (v == null || v === "") return fallback;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : fallback;
};
const time = (v: unknown): number => (typeof v === "string" && Number.isNaN(Number(v)) ? Date.parse(v) : num(v));
/** Portfolio, history and bus amounts are integer cents (ticks of 0.01); the UI works in decimal dollars. */
const CENTS = 100;
const px = (v: unknown): number => num(v) / CENTS;
const pxOrNull = (v: unknown): number | null => (v == null || v === "" ? null : px(v));
/** Decimal string on the tick grid, as the gateway expects (`"100.25"`). */
const decimal = (v: number): string => (Math.round(v * CENTS) / CENTS).toFixed(2);

/** Backend for the real stack: gateway/BFF REST API plus the market-data service's per-symbol WebSocket feed. */
export class HttpBackend implements Backend {
  readonly kind = "live" as const;
  private session: Session | null = null;
  private readonly apiBase: string;
  private readonly mdUrl: string;
  private readonly pollMs: number;
  private readonly storage: Storage | undefined;
  private readonly fetchImpl: typeof fetch;
  private readonly WS: typeof WebSocket;
  private readonly accountListeners = new Set<() => void>();
  /** The portfolio service has no order timestamps; remember when each open order was first seen. */
  private readonly firstSeen = new Map<number, number>();

  constructor(opts: HttpBackendOptions = {}) {
    this.apiBase = (opts.apiBase ?? "").replace(/\/$/, "");
    const origin = typeof location !== "undefined" ? location.origin.replace(/^http/, "ws") : "ws://localhost:5173";
    this.mdUrl = opts.marketDataUrl ?? `${origin}/ws/marketdata/{symbol}`;
    this.pollMs = opts.accountPollMs ?? 1000;
    this.storage = opts.storage ?? (typeof sessionStorage !== "undefined" ? sessionStorage : undefined);
    this.fetchImpl = opts.fetchImpl ?? fetch.bind(globalThis);
    this.WS = opts.webSocketImpl ?? WebSocket;
  }

  // ---- auth --------------------------------------------------------------------------------------------------

  restoreSession(): Session | null {
    const raw = this.storage?.getItem(SESSION_KEY);
    this.session = raw ? (JSON.parse(raw) as Session) : null;
    return this.session;
  }

  login(username: string, password: string): Promise<Session> {
    return this.authenticate("/api/auth/login", username, password);
  }

  signup(username: string, password: string): Promise<Session> {
    return this.authenticate("/api/auth/signup", username, password);
  }

  logout(): void {
    this.session = null;
    this.storage?.removeItem(SESSION_KEY);
  }

  private async authenticate(path: string, username: string, password: string): Promise<Session> {
    const body = await this.request<Json>("POST", path, { username, password }, false);
    // auth-service session body: {token, user: {id, username}, account: {id, participantId}}
    const user = (body.user as Json | undefined) ?? body;
    const account = (body.account as Json | undefined) ?? {};
    const session: Session = {
      token: String(body.token ?? body.accessToken ?? ""),
      username: String(user.username ?? username),
      participantId: num(account.participantId ?? user.participantId ?? body.participantId),
      accountId: String(account.id ?? user.accountId ?? body.accountId ?? ""),
    };
    this.session = session;
    this.storage?.setItem(SESSION_KEY, JSON.stringify(session));
    return session;
  }

  // ---- reference + market data ---------------------------------------------------------------------------------

  async instruments(): Promise<Instrument[]> {
    const body = await this.request<Json[] | Json>("GET", "/api/symbols");
    const rows = Array.isArray(body) ? body : ((body.symbols ?? body.instruments ?? []) as Json[]);
    return rows.map((r) => ({
      symbol: String(r.symbol),
      name: String(r.name ?? r.symbol),
      tickSize: num(r.tickSize, 0.01),
      lotSize: num(r.lotSize, 1),
    }));
  }

  async priceHistory(): Promise<PricePoint[]> {
    return [];
  }

  subscribeMarket(symbol: string, handlers: MarketFeedHandlers): MarketSubscription {
    let ws: WebSocket | null = null;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;
    const url = this.mdUrl.replace("{symbol}", encodeURIComponent(symbol));

    const connect = () => {
      handlers.onStatus("connecting");
      ws = new this.WS(url);
      ws.onopen = () => {
        attempt = 0;
      };
      ws.onmessage = (ev) => {
        const msg = JSON.parse(String(ev.data)) as MarketDataMessage;
        if (msg.symbol && msg.symbol !== symbol) return;
        if (msg.type === "snapshot") handlers.onStatus("live");
        handlers.onMessage(msg);
      };
      ws.onclose = () => {
        if (closed) return;
        handlers.onStatus("disconnected");
        retry = setTimeout(connect, Math.min(5000, 250 * 2 ** attempt++));
      };
    };
    connect();

    return {
      close: () => {
        closed = true;
        clearTimeout(retry);
        ws?.close();
      },
      resync: () => {
        handlers.onStatus("resyncing");
        if (ws?.readyState === this.WS.OPEN) ws.send(JSON.stringify({ type: "resync" }));
        else ws?.close();
      },
    };
  }

  // ---- account + orders ----------------------------------------------------------------------------------------

  subscribeAccount(onUpdate: (view: AccountView) => void, onError?: (err: Error) => void): Unsubscribe {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      clearTimeout(timer);
      try {
        const body = await this.request<Json>("GET", "/api/portfolio");
        if (!stopped) onUpdate({ portfolio: toPortfolio(body), openOrders: toOpenOrders(body, this.firstSeen) });
      } catch (err) {
        if (!stopped) onError?.(err as Error);
      }
      if (!stopped) timer = setTimeout(poll, this.pollMs);
    };
    const refresh = () => void poll();
    this.accountListeners.add(refresh);
    void poll();
    return () => {
      stopped = true;
      clearTimeout(timer);
      this.accountListeners.delete(refresh);
    };
  }

  async placeOrder(order: OrderRequest): Promise<OrderResult> {
    const body: Json = { symbol: order.symbol, side: order.side, type: order.type, qty: order.qty, timeInForce: order.timeInForce };
    if (order.type === "LIMIT" && order.price != null) body.price = decimal(order.price);
    return this.orderCall("POST", "/api/orders", body);
  }

  amendOrder(orderId: number, change: { price?: number; qty?: number }): Promise<OrderResult> {
    const body: Json = {};
    if (change.price != null) body.price = decimal(change.price);
    if (change.qty != null) body.qty = change.qty;
    return this.orderCall("PATCH", `/api/orders/${orderId}`, body);
  }

  cancelOrder(orderId: number): Promise<OrderResult> {
    return this.orderCall("DELETE", `/api/orders/${orderId}`);
  }

  private async orderCall(method: string, path: string, body?: Json): Promise<OrderResult> {
    try {
      return toOrderResult(await this.request<Json>(method, path, body));
    } finally {
      this.accountListeners.forEach((f) => f());
    }
  }

  /**
   * Order history folds the gateway's `orders_history` event rows (PLACED / AMENDED / CANCELLED / REJECTED) into one
   * row per order, with fills joined from `trades_history`.
   */
  async orderHistory(query: HistoryQuery = {}): Promise<OrderHistoryRow[]> {
    const [orders, trades] = await Promise.all([
      this.request<Json[] | Json>("GET", `/api/history/orders${qs(query)}`),
      this.tradeHistory(query),
    ]);
    return foldOrderHistory(list(orders, "orders"), trades);
  }

  async tradeHistory(query: HistoryQuery = {}): Promise<TradeHistoryRow[]> {
    const body = await this.request<Json[] | Json>("GET", `/api/history/trades${qs(query)}`);
    return list(body, "trades").map((r) => toTradeRow(r, this.session?.participantId ?? 0));
  }

  // ---- transport -----------------------------------------------------------------------------------------------

  private async request<T>(method: string, path: string, body?: unknown, auth = true): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (auth && this.session?.token) headers.Authorization = `Bearer ${this.session.token}`;
    const res = await this.fetchImpl(this.apiBase + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    const data = text ? (JSON.parse(text) as Json) : {};
    if (!res.ok) {
      // gateway errors are {error: message, reason: CODE}; auth-service errors are {error: CODE, message}
      const reason = String(data.reason ?? data.error ?? res.statusText ?? "ERROR");
      const message = data.message ?? (data.reason != null ? data.error : undefined) ?? reason;
      if (res.status === 401 && auth) this.logout();
      throw new ApiError(res.status, reason, String(message));
    }
    return data as T;
  }
}

function qs(q: HistoryQuery): string {
  const p = new URLSearchParams();
  if (q.symbol) p.set("symbol", q.symbol);
  if (q.limit) p.set("limit", String(q.limit));
  const s = p.toString();
  return s ? `?${s}` : "";
}

function list(body: Json[] | Json, key: string): Json[] {
  return Array.isArray(body) ? body : ((body[key] ?? body.items ?? []) as Json[]);
}

const pick = (r: Json, ...keys: string[]): unknown => {
  for (const k of keys) if (r[k] != null) return r[k];
  return undefined;
};

/** The portfolio service's `/v1/accounts/{participantId}` body (integer cents), as proxied by `GET /api/portfolio`. */
export function toPortfolio(p: Json): Portfolio {
  const positions = ((p.positions ?? []) as Json[]).map((r): Position => {
    const qty = num(pick(r, "shares", "qty"));
    const reservedQty = num(pick(r, "reservedShares", "reservedQty"));
    const markPrice = pxOrNull(r.markPrice);
    return {
      symbol: String(r.symbol),
      qty,
      reservedQty,
      availableQty: num(pick(r, "availableShares", "availableQty"), qty - reservedQty),
      avgCost: pxOrNull(r.avgCost),
      markPrice,
      marketValue: r.marketValue != null ? px(r.marketValue) : qty * (markPrice ?? 0),
      realizedPnl: px(r.realizedPnl),
      unrealizedPnl: px(r.unrealizedPnl),
    };
  });
  const realizedPnl = p.realizedPnl != null ? px(p.realizedPnl) : positions.reduce((s, x) => s + x.realizedPnl, 0);
  const unrealizedPnl = p.unrealizedPnl != null ? px(p.unrealizedPnl) : positions.reduce((s, x) => s + x.unrealizedPnl, 0);
  return {
    cash: px(p.cash),
    reservedCash: px(p.reservedCash),
    availableCash: p.availableCash != null ? px(p.availableCash) : px(p.cash) - px(p.reservedCash),
    startingCash: px(p.startingCash),
    equity: px(p.equity),
    realizedPnl,
    unrealizedPnl,
    sessionPnl: p.sessionPnl != null ? px(p.sessionPnl) : realizedPnl + unrealizedPnl,
    positions,
  };
}

/** Open orders are the portfolio service's live reservations (`openOrders`, status PENDING/OPEN). */
export function toOpenOrders(body: Json, firstSeen = new Map<number, number>()): OpenOrder[] {
  const rows = (body.openOrders ?? []) as Json[];
  const now = Date.now();
  const live = new Set<number>();
  const orders = rows.map((o): OpenOrder => {
    const id = num(pick(o, "orderId", "id"));
    live.add(id);
    if (!firstSeen.has(id)) firstSeen.set(id, now);
    return {
      id,
      symbol: String(o.symbol),
      side: o.side as OpenOrder["side"],
      type: (o.type ?? "LIMIT") as OpenOrder["type"],
      timeInForce: (pick(o, "tif", "timeInForce") ?? "GTC") as OpenOrder["timeInForce"],
      price: pxOrNull(pick(o, "limitPrice", "price")),
      qty: num(pick(o, "openQty", "qty")),
      filledQty: num(o.filledQty),
      reservedCash: px(o.reservedCash),
      createdAt: firstSeen.get(id)!,
    };
  });
  for (const id of firstSeen.keys()) if (!live.has(id)) firstSeen.delete(id);
  return orders;
}

/** The gateway's order reply: the engine events for this one command (prices as decimal strings), or a RiskRejected. */
export function toOrderResult(r: Json): OrderResult {
  const orderId = num(r.orderId);
  const events = (r.events ?? []) as Json[];
  let status: OrderStatus = "NEW";
  let qty: number | null = null;
  let filledQty = 0;
  let reason: string | undefined;
  for (const e of events) {
    switch (e.type) {
      case "OrderPlaced":
        qty = num(e.qty);
        break;
      case "OrderAmended":
        qty = num(e.newQty) + filledQty;
        break;
      case "TradeExecuted":
        if (num(e.takerOrderId) === orderId || num(e.makerOrderId) === orderId) filledQty += num(e.qty);
        break;
      case "OrderCancelled":
        status = "CANCELLED";
        if (e.reason && e.reason !== "USER_REQUESTED") reason = String(e.reason);
        break;
      default: // OrderRejected (engine), RiskRejected (risk service), Rejected (not your order)
        if (String(e.type).endsWith("Rejected")) {
          status = "REJECTED";
          reason = String(e.message ?? e.reason ?? e.type);
        }
    }
  }
  if (status === "NEW" && filledQty > 0) status = qty != null && filledQty >= qty ? "FILLED" : "PARTIALLY_FILLED";
  return { orderId, status, filledQty, reason, seq: r.seq == null ? undefined : num(r.seq) };
}

/** One `trades_history` row from the caller's point of view (maker or taker). */
export function toTradeRow(r: Json, participantId: number): TradeHistoryRow {
  const aggressor = String(pick(r, "aggressorSide", "side") ?? "BUY") as TradeHistoryRow["side"];
  const iAmTaker = num(pick(r, "takerParticipantId")) === participantId;
  const symbol = String(r.symbol);
  return {
    tradeId: String(pick(r, "id", "tradeId") ?? `${symbol}-${num(r.seq)}-${num(r.eventIndex)}`),
    orderId: num(iAmTaker ? pick(r, "takerOrderId") : pick(r, "makerOrderId")),
    symbol,
    side: iAmTaker ? aggressor : aggressor === "BUY" ? "SELL" : "BUY",
    price: px(pick(r, "priceTicks", "price")),
    qty: num(r.qty),
    liquidity: iAmTaker ? "TAKER" : "MAKER",
    time: time(pick(r, "eventTime", "time")),
  };
}

/** Folds `orders_history` lifecycle rows into one row per order; fills come from the caller's trade history. */
export function foldOrderHistory(rows: Json[], trades: TradeHistoryRow[]): OrderHistoryRow[] {
  const fills = new Map<string, { qty: number; notional: number }>();
  for (const t of trades) {
    const k = `${t.symbol}:${t.orderId}`;
    const f = fills.get(k) ?? { qty: 0, notional: 0 };
    f.qty += t.qty;
    f.notional += t.qty * t.price;
    fills.set(k, f);
  }
  const ordered = [...rows].sort((a, b) => num(a.seq) - num(b.seq) || num(a.eventIndex) - num(b.eventIndex));
  const byOrder = new Map<string, OrderHistoryRow>();
  for (const r of ordered) {
    const orderId = num(pick(r, "orderId"));
    const symbol = String(r.symbol);
    const k = `${symbol}:${orderId}`;
    const at = time(pick(r, "eventTime"));
    const price = pxOrNull(pick(r, "priceTicks"));
    let o = byOrder.get(k);
    if (!o) {
      o = {
        orderId, symbol,
        side: (r.side ?? "BUY") as OrderHistoryRow["side"],
        type: (pick(r, "orderType") ?? "LIMIT") as OrderHistoryRow["type"],
        timeInForce: (pick(r, "timeInForce") ?? "GTC") as OrderHistoryRow["timeInForce"],
        price: null, qty: 0, filledQty: 0, avgFillPrice: null, status: "NEW", createdAt: at, updatedAt: at,
      };
      byOrder.set(k, o);
    }
    o.updatedAt = at;
    switch (pick(r, "eventType")) {
      case "PLACED":
        o.price = price && price > 0 ? price : null;
        o.qty = num(r.qty);
        break;
      case "AMENDED":
        if (price && price > 0) o.price = price;
        break;
      case "CANCELLED":
        o.status = "CANCELLED";
        break;
      case "REJECTED":
        o.status = "REJECTED";
        o.reason = r.reason ? String(r.reason) : undefined;
        if (!o.qty) o.qty = num(r.qty);
        break;
    }
  }
  for (const [k, o] of byOrder) {
    const f = fills.get(k);
    if (f) {
      o.filledQty = f.qty;
      o.avgFillPrice = f.notional / f.qty;
    }
    if (o.status === "NEW" && o.filledQty > 0) o.status = o.filledQty >= o.qty ? "FILLED" : "PARTIALLY_FILLED";
  }
  return [...byOrder.values()].sort((a, b) => b.updatedAt - a.updatedAt);
}
