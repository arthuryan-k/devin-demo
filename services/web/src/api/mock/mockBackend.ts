import { ApiError, type Backend, type MarketFeedHandlers, type MarketSubscription, type Unsubscribe } from "../backend";
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
} from "../types";
import { Account, FakeExchange } from "./fakeExchange";
import { DEMO_USER, INSTRUMENTS } from "./fixtures";

const USERS_KEY = "tws.mock.users";
const SESSION_KEY = "tws.mock.session";

export interface MockBackendOptions {
  seed?: number;
  /** Synthetic market step interval per symbol, ms; 0 disables the timer (tests step manually). */
  tickMs?: number;
  /** Simulated network latency for REST calls, ms. */
  latencyMs?: number;
  storage?: Storage;
  now?: () => number;
}

/**
 * Backend for the static GitHub Pages build: no gateway, bus or database. Auth, reference data, the market-data
 * feed, portfolio and history all come from an in-browser `FakeExchange` seeded with fixtures.
 */
export class MockBackend implements Backend {
  readonly kind = "mock" as const;
  readonly exchange: FakeExchange;
  private session: Session | null = null;
  private readonly accounts = new Map<string, Account>();
  private readonly storage: Storage | undefined;
  private readonly tickMs: number;
  private readonly latencyMs: number;
  private timer: ReturnType<typeof setInterval> | undefined;
  private feeds = 0;
  private lastAccountPush = 0;

  constructor(opts: MockBackendOptions = {}) {
    this.exchange = new FakeExchange({ seed: opts.seed, now: opts.now });
    this.storage = opts.storage ?? (typeof localStorage !== "undefined" ? localStorage : undefined);
    this.tickMs = opts.tickMs ?? 220;
    this.latencyMs = opts.latencyMs ?? 60;
  }

  private delay<T>(fn: () => T): Promise<T> {
    return new Promise((resolve, reject) => {
      const run = () => {
        try {
          resolve(fn());
        } catch (e) {
          reject(e);
        }
      };
      if (this.latencyMs > 0) setTimeout(run, this.latencyMs);
      else queueMicrotask(run);
    });
  }

  // ---- auth --------------------------------------------------------------------------------------------------

  private users(): Record<string, string> {
    const stored = JSON.parse(this.storage?.getItem(USERS_KEY) ?? "{}") as Record<string, string>;
    return { [DEMO_USER.username]: DEMO_USER.password, ...stored };
  }

  private open(username: string): Session {
    const ids = Object.keys(this.users()).sort();
    const participantId = 1000 + Math.max(1, ids.indexOf(username) + 1);
    this.session = { token: `mock.${btoa(username)}`, username, participantId, accountId: `DU${(participantId * 7919) % 10_000_000}` };
    this.storage?.setItem(SESSION_KEY, JSON.stringify(this.session));
    return this.session;
  }

  restoreSession(): Session | null {
    const raw = this.storage?.getItem(SESSION_KEY);
    this.session = raw ? (JSON.parse(raw) as Session) : null;
    return this.session;
  }

  login(username: string, password: string): Promise<Session> {
    return this.delay(() => {
      const u = username.trim().toLowerCase();
      if (this.users()[u] !== password) throw new ApiError(401, "INVALID_CREDENTIALS", "Invalid username or password");
      return this.open(u);
    });
  }

  signup(username: string, password: string): Promise<Session> {
    return this.delay(() => {
      const u = username.trim().toLowerCase();
      if (!/^[a-z0-9_.-]{3,32}$/.test(u)) throw new ApiError(400, "INVALID_USERNAME", "Username must be 3–32 letters, digits, . _ or -");
      if (password.length < 8) throw new ApiError(400, "WEAK_PASSWORD", "Password must be at least 8 characters");
      const users = this.users();
      if (users[u]) throw new ApiError(409, "USERNAME_TAKEN", "That username is already registered");
      const stored = JSON.parse(this.storage?.getItem(USERS_KEY) ?? "{}") as Record<string, string>;
      this.storage?.setItem(USERS_KEY, JSON.stringify({ ...stored, [u]: password }));
      return this.open(u);
    });
  }

  logout(): void {
    this.session = null;
    this.storage?.removeItem(SESSION_KEY);
  }

  private account(): Account {
    if (!this.session) throw new ApiError(401, "UNAUTHENTICATED", "Not logged in");
    const name = this.session.username;
    let a = this.accounts.get(name);
    if (!a) this.accounts.set(name, (a = this.exchange.newAccount(name === DEMO_USER.username)));
    return a;
  }

  // ---- market data ---------------------------------------------------------------------------------------------

  instruments(): Promise<Instrument[]> {
    return this.delay(() => INSTRUMENTS.map((i) => ({ ...i })));
  }

  priceHistory(symbol: string): Promise<PricePoint[]> {
    return this.delay(() => this.exchange.priceHistory(symbol));
  }

  subscribeMarket(symbol: string, handlers: MarketFeedHandlers): MarketSubscription {
    const m = this.exchange.market(symbol);
    let closed = false;
    handlers.onStatus("connecting");
    const send = (reason: "connect" | "resync") =>
      setTimeout(() => {
        if (closed) return;
        handlers.onStatus("live");
        handlers.onMessage(m.snapshot(reason));
      }, Math.min(this.latencyMs, 30));
    const listener = (msg: Parameters<MarketFeedHandlers["onMessage"]>[0]) => !closed && handlers.onMessage(msg);
    m.listeners.add(listener);
    send("connect");
    this.feeds++;
    this.startTimer();
    return {
      close: () => {
        if (closed) return;
        closed = true;
        m.listeners.delete(listener);
        if (--this.feeds === 0) this.stopTimer();
      },
      resync: () => {
        handlers.onStatus("resyncing");
        send("resync");
      },
    };
  }

  private startTimer(): void {
    if (this.timer || this.tickMs <= 0) return;
    const symbols = [...this.exchange.markets.keys()];
    this.timer = setInterval(() => {
      for (const s of symbols) if (Math.random() < 0.7) this.exchange.step(s);
      const now = Date.now();
      if (now - this.lastAccountPush > 750) {
        this.lastAccountPush = now;
        this.exchange.notifyAll();
      }
    }, this.tickMs);
  }

  private stopTimer(): void {
    clearInterval(this.timer);
    this.timer = undefined;
  }

  // ---- account + orders ----------------------------------------------------------------------------------------

  subscribeAccount(onUpdate: (view: AccountView) => void, onError?: (err: Error) => void): Unsubscribe {
    try {
      return this.exchange.onAccount(this.account(), onUpdate);
    } catch (e) {
      onError?.(e as Error);
      return () => {};
    }
  }

  placeOrder(order: OrderRequest): Promise<OrderResult> {
    return this.delay(() => this.exchange.place(this.account(), order));
  }

  amendOrder(orderId: number, change: { price?: number; qty?: number }): Promise<OrderResult> {
    return this.delay(() => this.exchange.amend(this.account(), orderId, change));
  }

  cancelOrder(orderId: number): Promise<OrderResult> {
    return this.delay(() => this.exchange.cancel(this.account(), orderId));
  }

  orderHistory(query: HistoryQuery = {}): Promise<OrderHistoryRow[]> {
    return this.delay(() =>
      [...this.account().history.values()]
        .filter((r) => !query.symbol || r.symbol === query.symbol)
        .sort((x, y) => y.createdAt - x.createdAt)
        .slice(0, query.limit ?? 200)
        .map((r) => ({ ...r })),
    );
  }

  tradeHistory(query: HistoryQuery = {}): Promise<TradeHistoryRow[]> {
    return this.delay(() =>
      this.account()
        .trades.filter((r) => !query.symbol || r.symbol === query.symbol)
        .slice(0, query.limit ?? 200)
        .map((r) => ({ ...r })),
    );
  }
}
