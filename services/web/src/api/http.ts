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
const numOrNull = (v: unknown): number | null => (v == null || v === "" ? null : num(v, NaN));
const time = (v: unknown): number => (typeof v === "string" && Number.isNaN(Number(v)) ? Date.parse(v) : num(v));

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
    const user = (body.user as Json | undefined) ?? body;
    const session: Session = {
      token: String(body.token ?? body.accessToken ?? ""),
      username: String(user.username ?? username),
      participantId: num(user.participantId ?? body.participantId),
      accountId: String(user.accountId ?? body.accountId ?? ""),
    };
    this.session = session;
    this.storage?.setItem(SESSION_KEY, JSON.stringify(session));
    return session;
  }

  // ---- reference + market data ---------------------------------------------------------------------------------

  async instruments(): Promise<Instrument[]> {
    const body = await this.request<Json[] | Json>("GET", "/api/reference/symbols");
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
        ws?.send(JSON.stringify({ op: "subscribe", symbol }));
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
        if (ws?.readyState === this.WS.OPEN) ws.send(JSON.stringify({ op: "resync", symbol }));
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
        const [portfolio, open] = await Promise.all([
          this.request<Json>("GET", "/api/portfolio"),
          this.request<Json[] | Json>("GET", "/api/orders?status=open"),
        ]);
        if (!stopped) onUpdate({ portfolio: toPortfolio(portfolio), openOrders: toOpenOrders(open) });
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
    const body: Json = { ...order };
    if (order.type === "MARKET") delete body.price;
    return this.orderCall("POST", "/api/orders", body);
  }

  amendOrder(orderId: number, change: { price?: number; qty?: number }): Promise<OrderResult> {
    return this.orderCall("PATCH", `/api/orders/${orderId}`, change);
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

  async orderHistory(query: HistoryQuery = {}): Promise<OrderHistoryRow[]> {
    const body = await this.request<Json[] | Json>("GET", `/api/history/orders${qs(query)}`);
    return list(body, "orders").map((r) => ({
      orderId: num(r.orderId ?? r.id),
      symbol: String(r.symbol),
      side: r.side as OrderHistoryRow["side"],
      type: (r.type ?? r.orderType ?? "LIMIT") as OrderHistoryRow["type"],
      timeInForce: (r.timeInForce ?? r.tif ?? "GTC") as OrderHistoryRow["timeInForce"],
      price: numOrNull(r.price),
      qty: num(r.qty ?? r.quantity),
      filledQty: num(r.filledQty),
      avgFillPrice: numOrNull(r.avgFillPrice),
      status: String(r.status ?? "NEW") as OrderStatus,
      reason: r.reason ? String(r.reason) : undefined,
      createdAt: time(r.createdAt),
      updatedAt: time(r.updatedAt ?? r.createdAt),
    }));
  }

  async tradeHistory(query: HistoryQuery = {}): Promise<TradeHistoryRow[]> {
    const body = await this.request<Json[] | Json>("GET", `/api/history/trades${qs(query)}`);
    return list(body, "trades").map((r) => ({
      tradeId: String(r.tradeId ?? r.id),
      orderId: num(r.orderId),
      symbol: String(r.symbol),
      side: r.side as TradeHistoryRow["side"],
      price: num(r.price),
      qty: num(r.qty ?? r.quantity),
      liquidity: (r.liquidity ?? "TAKER") as TradeHistoryRow["liquidity"],
      time: time(r.time ?? r.executedAt ?? r.createdAt),
    }));
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
      const reason = String(data.reason ?? data.error ?? res.statusText ?? "ERROR");
      if (res.status === 401 && auth) this.logout();
      throw new ApiError(res.status, reason, String(data.message ?? reason));
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

export function toPortfolio(p: Json): Portfolio {
  const positions = ((p.positions ?? []) as Json[]).map(
    (r): Position => ({
      symbol: String(r.symbol),
      qty: num(r.qty ?? r.shares),
      reservedQty: num(r.reservedQty ?? r.reservedShares),
      availableQty: num(r.availableQty ?? r.availableShares, num(r.qty ?? r.shares) - num(r.reservedQty ?? r.reservedShares)),
      avgCost: numOrNull(r.avgCost),
      markPrice: numOrNull(r.markPrice),
      marketValue: num(r.marketValue, num(r.qty ?? r.shares) * num(r.markPrice)),
      realizedPnl: num(r.realizedPnl),
      unrealizedPnl: num(r.unrealizedPnl),
    }),
  );
  return {
    cash: num(p.cash),
    reservedCash: num(p.reservedCash),
    availableCash: num(p.availableCash, num(p.cash) - num(p.reservedCash)),
    startingCash: num(p.startingCash),
    equity: num(p.equity),
    realizedPnl: num(p.realizedPnl, positions.reduce((s, x) => s + x.realizedPnl, 0)),
    unrealizedPnl: num(p.unrealizedPnl, positions.reduce((s, x) => s + x.unrealizedPnl, 0)),
    sessionPnl: num(p.sessionPnl),
    positions,
  };
}

function toOpenOrders(body: Json[] | Json): OpenOrder[] {
  return list(body, "orders").map((o) => ({
    id: num(o.id ?? o.orderId),
    symbol: String(o.symbol),
    side: o.side as OpenOrder["side"],
    type: (o.type ?? "LIMIT") as OpenOrder["type"],
    timeInForce: (o.timeInForce ?? "GTC") as OpenOrder["timeInForce"],
    price: numOrNull(o.price),
    qty: num(o.qty ?? o.remainingQty),
    filledQty: num(o.filledQty),
    reservedCash: num(o.reservedCash),
    createdAt: time(o.createdAt ?? Date.now()),
  }));
}

function toOrderResult(r: Json): OrderResult {
  return {
    orderId: num(r.orderId ?? r.id),
    status: String(r.status ?? "NEW") as OrderStatus,
    filledQty: num(r.filledQty),
    reason: r.reason ? String(r.reason) : undefined,
    seq: r.seq == null ? undefined : num(r.seq),
  };
}
