import { gaussian, mulberry32 } from "../../lib/rng";
import type { MarketDataMessage, WireLevel, WireSnapshot, WireLevelChange, WireTrade } from "../../marketdata/wire";
import { ApiError } from "../backend";
import type {
  AccountView,
  OpenOrder,
  OrderHistoryRow,
  OrderRequest,
  OrderResult,
  OrderStatus,
  Portfolio,
  PricePoint,
  Side,
  TradeHistoryRow,
} from "../types";
import { DEMO_POSITIONS, INSTRUMENTS, MARKET_PARAMS, STARTING_CASH_CENTS } from "./fixtures";

/*
 * A tiny in-browser stand-in for the matching engine, market-data service and portfolio service, used only by the
 * static GitHub Pages build. Each symbol has synthetic liquidity around a random-walk reference price; the user's
 * orders rest in the same book and fill when crossed. Prices are integer ticks (0.01) and money is integer cents,
 * so the accounting is exact. It emits the real market-data wire format so the UI's replica code is exercised.
 */

interface Liquidity {
  qty: number;
  orders: number;
}

interface UserOrder {
  id: number;
  symbol: string;
  side: Side;
  type: OrderRequest["type"];
  tif: OrderRequest["timeInForce"];
  price: number | null;
  remaining: number;
  filled: number;
  original: number;
  reservedCents: number;
  fillCents: number;
  createdAt: number;
  account: Account;
}

interface PositionState {
  qty: number;
  reserved: number;
  costCents: number;
  realizedCents: number;
}

export class Account {
  cash = STARTING_CASH_CENTS;
  reservedCash = 0;
  startEquity = 0;
  readonly positions = new Map<string, PositionState>();
  readonly orders = new Map<number, UserOrder>();
  readonly history = new Map<number, OrderHistoryRow>();
  readonly trades: TradeHistoryRow[] = [];

  position(symbol: string): PositionState {
    let p = this.positions.get(symbol);
    if (!p) {
      p = { qty: 0, reserved: 0, costCents: 0, realizedCents: 0 };
      this.positions.set(symbol, p);
    }
    return p;
  }
}

type Listener = (msg: MarketDataMessage) => void;

class SymbolMarket {
  readonly bids = new Map<number, Liquidity>();
  readonly asks = new Map<number, Liquidity>();
  readonly userOrders: UserOrder[] = [];
  readonly listeners = new Set<Listener>();
  readonly recentTrades: WireTrade[] = [];
  seq = 0;
  ref: number;
  last: number;
  tradeN = 0;
  private pendingTrades: WireTrade[] = [];
  private before: Map<string, WireLevel> | null = null;

  constructor(
    readonly symbol: string,
    readonly params: (typeof MARKET_PARAMS)[string],
    private readonly rand: () => number,
  ) {
    this.ref = params.open;
    this.last = params.open;
    this.refill();
  }

  /** Wire view: synthetic liquidity plus resting user orders, keyed "B:price"/"S:price". */
  view(): Map<string, WireLevel> {
    const v = new Map<string, WireLevel>();
    const add = (side: "B" | "S", price: number, qty: number, orders: number) => {
      const k = `${side}:${price}`;
      const cur = v.get(k);
      if (cur) v.set(k, [price, cur[1] + qty, cur[2] + orders]);
      else v.set(k, [price, qty, orders]);
    };
    this.bids.forEach((l, p) => l.qty > 0 && add("B", p, l.qty, l.orders));
    this.asks.forEach((l, p) => l.qty > 0 && add("S", p, l.qty, l.orders));
    for (const o of this.userOrders) add(o.side === "BUY" ? "B" : "S", o.price!, o.remaining, 1);
    return v;
  }

  snapshot(reason: "connect" | "resync"): WireSnapshot {
    const v = [...this.view().entries()];
    const side = (s: "B" | "S", desc: boolean) =>
      v
        .filter(([k]) => k[0] === s)
        .map(([, l]) => l)
        .sort((a, b) => (desc ? b[0] - a[0] : a[0] - b[0]));
    return {
      type: "snapshot",
      symbol: this.symbol,
      seq: this.seq,
      reason,
      bids: side("B", true),
      asks: side("S", false),
      trades: [...this.recentTrades],
      events: [],
    };
  }

  begin(): void {
    if (!this.before) this.before = this.view();
  }

  /** Diffs the book against `begin()` and publishes one delta (one engine seq) if anything changed. */
  commit(): void {
    const before = this.before;
    this.before = null;
    if (!before) return;
    const after = this.view();
    const levels: WireLevelChange[] = [];
    after.forEach((l, k) => {
      const prev = before.get(k);
      const side = k[0] as "B" | "S";
      if (!prev) levels.push(["a", side, l[0], l[1], l[2]]);
      else if (prev[1] !== l[1] || prev[2] !== l[2]) levels.push(["u", side, l[0], l[1], l[2]]);
    });
    before.forEach((l, k) => {
      if (!after.has(k)) levels.push(["r", k[0] as "B" | "S", l[0], 0, 0]);
    });
    const events = this.pendingTrades;
    this.pendingTrades = [];
    if (levels.length === 0 && events.length === 0) return;
    const msg: MarketDataMessage = { type: "delta", symbol: this.symbol, seq: ++this.seq, levels, events };
    this.listeners.forEach((l) => l(msg));
  }

  print(price: number, qty: number, takerSide: Side, maker: string, taker: string, now: number): void {
    const t: WireTrade = { type: "TradeExecuted", n: ++this.tradeN, price, qty, takerSide, maker, taker, ts: now };
    this.last = price;
    this.pendingTrades.push(t);
    this.recentTrades.push(t);
    if (this.recentTrades.length > 60) this.recentTrades.shift();
  }

  bestBid(): number | null {
    let best: number | null = null;
    this.bids.forEach((l, p) => l.qty > 0 && (best === null || p > best) && (best = p));
    for (const o of this.userOrders) if (o.side === "BUY" && (best === null || o.price! > best)) best = o.price!;
    return best;
  }

  bestAsk(): number | null {
    let best: number | null = null;
    this.asks.forEach((l, p) => l.qty > 0 && (best === null || p < best) && (best = p));
    for (const o of this.userOrders) if (o.side === "SELL" && (best === null || o.price! < best)) best = o.price!;
    return best;
  }

  lotSize(): number {
    const base = this.params.open > 10_000 ? 20 : this.params.open > 3_000 ? 60 : 150;
    return Math.max(1, Math.round(base * (0.2 + this.rand() * 1.6)));
  }

  /** Keeps ~14 synthetic levels per side, best quotes straddling the reference price. */
  refill(): void {
    const half = this.params.spread;
    const topBid = Math.floor(this.ref) - Math.floor(this.rand() * half);
    const topAsk = Math.max(topBid + 1, Math.ceil(this.ref) + Math.floor(this.rand() * half));
    // Quotes left behind by a fast move would lock or cross the new touch: pull them.
    this.asks.forEach((_, p) => p <= topBid && this.asks.delete(p));
    this.bids.forEach((_, p) => p >= topAsk && this.bids.delete(p));
    const userBids = this.userOrders.filter((o) => o.side === "BUY").map((o) => o.price!);
    const userAsks = this.userOrders.filter((o) => o.side === "SELL").map((o) => o.price!);
    const bidCeiling = Math.min(topAsk, ...userAsks); // synthetic bids stay strictly below this
    const askFloor = Math.max(topBid, ...userBids); // synthetic asks stay strictly above this
    this.bids.forEach((_, p) => p >= bidCeiling && this.bids.delete(p));
    this.asks.forEach((_, p) => p <= askFloor && this.asks.delete(p));
    for (const [book, top, dir] of [
      [this.bids, topBid, -1],
      [this.asks, topAsk, 1],
    ] as const) {
      for (let i = 0; i < 16; i++) {
        const p = top + dir * i;
        if (p <= 0) break;
        if (dir < 0 ? p >= bidCeiling : p <= askFloor) continue;
        const l = book.get(p);
        if (!l || l.qty <= 0) {
          if (i > 1 && this.rand() < 0.18) continue; // leave gaps deeper in the book
          book.set(p, { qty: this.lotSize() * (1 + Math.floor(i / 4)), orders: 1 + Math.floor(this.rand() * 4) });
        }
      }
      book.forEach((_, p) => {
        if (Math.abs(p - top) > 22) book.delete(p);
      });
    }
  }
}

export interface FakeExchangeOptions {
  seed?: number;
  now?: () => number;
}

export class FakeExchange {
  readonly markets = new Map<string, SymbolMarket>();
  private readonly rand: () => number;
  private readonly now: () => number;
  private nextOrderId = 5001;
  private nextTradeId = 90001;
  private readonly accountListeners = new Map<Account, Set<(v: AccountView) => void>>();

  constructor(opts: FakeExchangeOptions = {}) {
    this.rand = mulberry32(opts.seed ?? 20261022);
    this.now = opts.now ?? Date.now;
    for (const inst of INSTRUMENTS) {
      this.markets.set(
        inst.symbol,
        new SymbolMarket(inst.symbol, MARKET_PARAMS[inst.symbol], mulberry32(this.rand() * 2 ** 32)),
      );
    }
  }

  market(symbol: string): SymbolMarket {
    const m = this.markets.get(symbol);
    if (!m) throw new ApiError(400, "UNKNOWN_SYMBOL", `Unknown symbol ${symbol}`);
    return m;
  }

  // ---- accounts -------------------------------------------------------------------------------------------------

  newAccount(withDemoPositions: boolean): Account {
    const a = new Account();
    if (withDemoPositions) seedDemoHistory(a, this.now(), () => this.nextTradeId++);
    a.startEquity = this.equityCents(a);
    return a;
  }

  onAccount(account: Account, fn: (v: AccountView) => void): () => void {
    let set = this.accountListeners.get(account);
    if (!set) this.accountListeners.set(account, (set = new Set()));
    set.add(fn);
    fn(this.accountView(account));
    return () => set!.delete(fn);
  }

  private notify(account: Account): void {
    const set = this.accountListeners.get(account);
    if (!set?.size) return;
    const v = this.accountView(account);
    set.forEach((f) => f(v));
  }

  notifyAll(): void {
    this.accountListeners.forEach((_, a) => this.notify(a));
  }

  private equityCents(a: Account): number {
    let eq = a.cash;
    a.positions.forEach((p, s) => (eq += p.qty * this.market(s).last));
    return eq;
  }

  accountView(a: Account): AccountView {
    const positions = [...a.positions.entries()]
      .filter(([, p]) => p.qty > 0 || p.reserved > 0 || p.realizedCents !== 0)
      .sort(([x], [y]) => x.localeCompare(y))
      .map(([symbol, p]) => {
        const mark = this.market(symbol).last;
        return {
          symbol,
          qty: p.qty,
          reservedQty: p.reserved,
          availableQty: p.qty - p.reserved,
          avgCost: p.qty > 0 ? p.costCents / p.qty / 100 : null,
          markPrice: mark / 100,
          marketValue: (p.qty * mark) / 100,
          realizedPnl: p.realizedCents / 100,
          unrealizedPnl: (p.qty * mark - p.costCents) / 100,
        };
      });
    const equity = this.equityCents(a);
    const portfolio: Portfolio = {
      cash: a.cash / 100,
      reservedCash: a.reservedCash / 100,
      availableCash: (a.cash - a.reservedCash) / 100,
      startingCash: STARTING_CASH_CENTS / 100,
      equity: equity / 100,
      realizedPnl: positions.reduce((s, p) => s + p.realizedPnl, 0),
      unrealizedPnl: positions.reduce((s, p) => s + p.unrealizedPnl, 0),
      sessionPnl: (equity - a.startEquity) / 100,
      positions,
    };
    const openOrders: OpenOrder[] = [...a.orders.values()]
      .sort((x, y) => y.id - x.id)
      .map((o) => ({
        id: o.id,
        symbol: o.symbol,
        side: o.side,
        type: o.type,
        timeInForce: o.tif,
        price: o.price == null ? null : o.price / 100,
        qty: o.remaining,
        filledQty: o.filled,
        reservedCash: o.reservedCents / 100,
        createdAt: o.createdAt,
      }));
    return { portfolio, openOrders };
  }

  // ---- order entry ----------------------------------------------------------------------------------------------

  place(a: Account, req: OrderRequest): OrderResult {
    const m = this.market(req.symbol);
    const qty = req.qty;
    if (!Number.isInteger(qty) || qty <= 0)
      throw new ApiError(400, "INVALID_QTY", "Quantity must be a positive integer");
    let price: number | null = null;
    if (req.type === "LIMIT") {
      if (req.price == null || !(req.price > 0))
        throw new ApiError(400, "INVALID_PRICE", "Limit price must be positive");
      price = Math.round(req.price * 100);
      if (Math.abs(price - req.price * 100) > 1e-6)
        throw new ApiError(400, "INVALID_PRICE", "Price is off the 0.01 tick grid");
    }
    const id = this.nextOrderId++;
    const now = this.now();
    const order: UserOrder = {
      id,
      symbol: req.symbol,
      side: req.side,
      type: req.type,
      tif: req.type === "MARKET" ? "IOC" : req.timeInForce,
      price,
      remaining: qty,
      filled: 0,
      original: qty,
      reservedCents: 0,
      fillCents: 0,
      createdAt: now,
      account: a,
    };
    a.history.set(id, {
      orderId: id,
      symbol: req.symbol,
      side: req.side,
      type: req.type,
      timeInForce: req.timeInForce,
      price: req.price ?? null,
      qty,
      filledQty: 0,
      avgFillPrice: null,
      status: "NEW",
      createdAt: now,
      updatedAt: now,
    });

    const reject = (reason: string, message: string): never => {
      this.finish(order, "REJECTED", reason);
      this.notify(a);
      throw new ApiError(422, reason, message);
    };
    const pos = a.position(req.symbol);
    if (req.side === "SELL" && qty > pos.qty - pos.reserved) {
      reject(
        "INSUFFICIENT_SHARES",
        `Only ${pos.qty - pos.reserved} ${req.symbol} available to sell (no short selling)`,
      );
    }
    const available = a.cash - a.reservedCash;
    if (req.side === "BUY") {
      const need = price != null ? price * qty : this.sweepCost(m, "BUY", qty);
      if (need == null) reject("NO_LIQUIDITY", "No liquidity for a market order");
      if (need! > available)
        reject(
          "INSUFFICIENT_FUNDS",
          `Order needs ${(need! / 100).toFixed(2)}, available ${(available / 100).toFixed(2)}`,
        );
      order.reservedCents = need!;
      a.reservedCash += need!;
    } else {
      pos.reserved += qty;
    }
    if (order.tif === "FOK" && (this.crossableQty(m, order) ?? 0) < qty) {
      this.release(order);
      reject("FOK_NOT_FILLABLE", "Fill-or-kill order cannot be filled in full");
    }

    m.begin();
    this.matchIncoming(m, order);
    let status: OrderStatus;
    if (order.remaining > 0 && order.type === "LIMIT" && order.tif === "GTC") {
      m.userOrders.push(order);
      a.orders.set(id, order);
      status = order.filled > 0 ? "PARTIALLY_FILLED" : "NEW";
      this.updateHistory(order, status);
    } else if (order.remaining > 0) {
      status = "CANCELLED";
      this.finish(order, status, order.type === "MARKET" ? "MARKET_REMAINDER" : "IOC_REMAINDER");
    } else {
      status = "FILLED";
      this.finish(order, status);
    }
    m.commit();
    this.notify(a);
    return { orderId: id, status, filledQty: order.filled, seq: m.seq };
  }

  amend(a: Account, orderId: number, change: { price?: number; qty?: number }): OrderResult {
    const o = a.orders.get(orderId);
    if (!o) throw new ApiError(404, "NOT_OWN_OPEN_ORDER", `Order ${orderId} is not one of your open orders`);
    const m = this.market(o.symbol);
    const newPrice = change.price != null ? Math.round(change.price * 100) : o.price!;
    const newQty = change.qty ?? o.remaining;
    if (!(newPrice > 0) || !Number.isInteger(newQty) || newQty <= 0)
      throw new ApiError(400, "INVALID_AMEND", "Price and quantity must be positive");
    const pos = a.position(o.symbol);
    if (o.side === "BUY") {
      const need = newPrice * newQty - o.reservedCents;
      if (need > a.cash - a.reservedCash)
        throw new ApiError(422, "INSUFFICIENT_FUNDS", "Not enough cash for the amended order");
      a.reservedCash += need;
      o.reservedCents += need;
    } else {
      if (newQty - o.remaining > pos.qty - pos.reserved)
        throw new ApiError(422, "INSUFFICIENT_SHARES", "Not enough shares for the amended order");
      pos.reserved += newQty - o.remaining;
    }
    m.begin();
    const keepsPriority = newPrice === o.price && newQty <= o.remaining;
    o.price = newPrice;
    o.remaining = newQty;
    o.original = o.filled + newQty;
    if (!keepsPriority) {
      removeFrom(m.userOrders, o);
      this.matchIncoming(m, o);
      if (o.remaining > 0) m.userOrders.push(o);
    }
    const status: OrderStatus = o.remaining === 0 ? "FILLED" : o.filled > 0 ? "PARTIALLY_FILLED" : "NEW";
    if (o.remaining === 0) this.finish(o, "FILLED");
    else this.updateHistory(o, status, { price: newPrice / 100, qty: o.original });
    m.commit();
    this.notify(a);
    return { orderId, status, filledQty: o.filled, seq: m.seq };
  }

  cancel(a: Account, orderId: number): OrderResult {
    const o = a.orders.get(orderId);
    if (!o) throw new ApiError(404, "NOT_OWN_OPEN_ORDER", `Order ${orderId} is not one of your open orders`);
    const m = this.market(o.symbol);
    m.begin();
    this.finish(o, "CANCELLED", "USER_REQUEST");
    m.commit();
    this.notify(a);
    return { orderId, status: "CANCELLED", filledQty: o.filled, seq: m.seq };
  }

  // ---- matching -------------------------------------------------------------------------------------------------

  private crosses(side: Side, limit: number | null, levelPrice: number): boolean {
    return limit == null || (side === "BUY" ? levelPrice <= limit : levelPrice >= limit);
  }

  private oppositeLevels(m: SymbolMarket, side: Side): [number, Liquidity][] {
    const book = side === "BUY" ? m.asks : m.bids;
    return [...book.entries()]
      .filter(([, l]) => l.qty > 0)
      .sort((x, y) => (side === "BUY" ? x[0] - y[0] : y[0] - x[0]));
  }

  private crossableQty(m: SymbolMarket, o: UserOrder): number {
    let q = 0;
    for (const [p, l] of this.oppositeLevels(m, o.side)) if (this.crosses(o.side, o.price, p)) q += l.qty;
    return q;
  }

  private sweepCost(m: SymbolMarket, side: Side, qty: number): number | null {
    let left = qty;
    let cost = 0;
    for (const [p, l] of this.oppositeLevels(m, side)) {
      const q = Math.min(left, l.qty);
      cost += q * p;
      left -= q;
      if (left === 0) return cost;
    }
    return left === qty ? null : cost;
  }

  /** Sweeps synthetic liquidity for an incoming user order (self-trade prevention: own orders are never hit). */
  private matchIncoming(m: SymbolMarket, o: UserOrder): void {
    for (const [p, l] of this.oppositeLevels(m, o.side)) {
      if (o.remaining === 0 || !this.crosses(o.side, o.price, p)) break;
      if (o.side === "BUY" && o.type === "MARKET" && o.reservedCents < p) break;
      let q = Math.min(o.remaining, l.qty);
      if (o.side === "BUY" && o.type === "MARKET") q = Math.min(q, Math.floor(o.reservedCents / p));
      l.qty -= q;
      if (l.qty === 0) (o.side === "BUY" ? m.asks : m.bids).delete(p);
      else l.orders = Math.max(1, Math.min(l.orders, Math.ceil(l.qty / 10)));
      m.print(p, q, o.side, "MM", "YOU", this.now());
      this.fill(o, p, q, "TAKER");
    }
  }

  private fill(o: UserOrder, price: number, qty: number, liquidity: "MAKER" | "TAKER"): void {
    const a = o.account;
    const pos = a.position(o.symbol);
    o.remaining -= qty;
    o.filled += qty;
    o.fillCents += price * qty;
    if (o.side === "BUY") {
      const release = o.type === "LIMIT" ? o.price! * qty : price * qty;
      o.reservedCents -= release;
      a.reservedCash -= release;
      a.cash -= price * qty;
      pos.qty += qty;
      pos.costCents += price * qty;
    } else {
      const avg = pos.qty > 0 ? pos.costCents / pos.qty : 0;
      const costOut = Math.round(avg * qty);
      a.cash += price * qty;
      pos.qty -= qty;
      pos.reserved -= qty;
      pos.realizedCents += price * qty - costOut;
      pos.costCents = pos.qty === 0 ? 0 : pos.costCents - costOut;
    }
    a.trades.unshift({
      tradeId: `T${this.nextTradeId++}`,
      orderId: o.id,
      symbol: o.symbol,
      side: o.side,
      price: price / 100,
      qty,
      liquidity,
      time: this.now(),
    });
    this.updateHistory(o, o.remaining === 0 ? "FILLED" : "PARTIALLY_FILLED");
  }

  private release(o: UserOrder): void {
    const a = o.account;
    if (o.side === "BUY") {
      a.reservedCash -= o.reservedCents;
      o.reservedCents = 0;
    } else {
      a.position(o.symbol).reserved -= o.remaining;
    }
  }

  private finish(o: UserOrder, status: OrderStatus, reason?: string): void {
    if (status !== "REJECTED") this.release(o);
    const m = this.market(o.symbol);
    removeFrom(m.userOrders, o);
    o.account.orders.delete(o.id);
    this.updateHistory(o, status, undefined, reason);
  }

  private updateHistory(o: UserOrder, status: OrderStatus, fields?: Partial<OrderHistoryRow>, reason?: string): void {
    const h = o.account.history.get(o.id);
    if (!h) return;
    Object.assign(h, fields, {
      status,
      filledQty: o.filled,
      avgFillPrice: o.filled > 0 ? Number((o.fillCents / o.filled / 100).toFixed(4)) : null,
      updatedAt: this.now(),
    });
    if (reason) h.reason = reason;
  }

  // ---- synthetic flow ---------------------------------------------------------------------------------------------

  /** Advances one symbol's synthetic market by one step: drift, sweeps through the moved price, noise, refill. */
  step(symbol: string): void {
    const m = this.market(symbol);
    const r = this.rand;
    const now = this.now();
    m.begin();
    const shock = r() < 0.004 ? gaussian(r) * 12 : 0;
    const pull = (m.params.open - m.ref) * 0.002;
    m.ref = Math.max(5, m.ref + m.ref * m.params.vol * gaussian(r) + pull + shock);

    // The fair value walked through resting liquidity: takers lift / hit it.
    for (const side of ["BUY", "SELL"] as const) {
      const through = side === "BUY" ? (p: number) => p <= m.ref - 0.5 : (p: number) => p >= m.ref + 0.5;
      for (const [p] of this.oppositeLevels(m, side)) {
        if (!through(p)) break;
        this.takeLevel(m, side, p, Infinity, now);
      }
      for (const o of [...m.userOrders]) {
        if (o.side !== side && through(o.price!)) this.takeLevel(m, side, o.price!, Infinity, now);
      }
    }

    // Random marketable flow at the touch.
    if (r() < 0.45) {
      const side: Side = r() < 0.5 + Math.tanh((m.ref - m.last) / 4) * 0.25 ? "BUY" : "SELL";
      const best = side === "BUY" ? m.bestAsk() : m.bestBid();
      if (best != null) this.takeLevel(m, side, best, Math.max(1, Math.round(m.lotSize() * (0.3 + r()))), now);
    }
    // Quote churn: resize / pull a few synthetic levels.
    for (let i = 0; i < 3; i++) {
      const book = r() < 0.5 ? m.bids : m.asks;
      const keys = [...book.keys()];
      if (!keys.length) continue;
      const p = keys[Math.floor(r() * keys.length)];
      const l = book.get(p)!;
      if (r() < 0.15) book.delete(p);
      else {
        l.qty = Math.max(1, Math.round(l.qty * (0.6 + r() * 0.9)));
        l.orders = Math.max(1, l.orders + (r() < 0.5 ? -1 : 1));
      }
    }
    m.refill();
    m.commit();
  }

  /** A synthetic taker on `side` consumes up to `max` at `price`: synthetic liquidity first, then user orders FIFO. */
  private takeLevel(m: SymbolMarket, side: Side, price: number, max: number, now: number): void {
    const book = side === "BUY" ? m.asks : m.bids;
    let left = max;
    const l = book.get(price);
    if (l && l.qty > 0) {
      const q = Math.min(left, l.qty);
      l.qty -= q;
      left -= q;
      if (l.qty === 0) book.delete(price);
      m.print(price, q, side, "MM", "FLOW", now);
    }
    const resting = m.userOrders.filter((o) => o.side !== side && o.price === price).sort((x, y) => x.id - y.id);
    for (const o of resting) {
      if (left <= 0) break;
      const q = Math.min(left, o.remaining);
      left -= q;
      m.print(price, q, side, "YOU", "FLOW", now);
      this.fill(o, price, q, "MAKER");
      if (o.remaining === 0) this.finish(o, "FILLED");
      this.notify(o.account);
    }
  }

  /** Synthetic intraday price history (one point every 5s) ending at the current price, for the chart backfill. */
  priceHistory(symbol: string, minutes = 45): PricePoint[] {
    const m = this.market(symbol);
    const rand = mulberry32(symbol.split("").reduce((h, c) => h * 31 + c.charCodeAt(0), 7));
    const n = (minutes * 60) / 5;
    const pts: PricePoint[] = [];
    let p = m.last;
    const now = this.now();
    for (let i = 0; i < n; i++) {
      pts.push({ time: now - (i + 1) * 5000, price: p / 100, qty: Math.round(20 + rand() * 300) });
      const target = m.params.prevClose + (m.params.open - m.params.prevClose) * (1 - i / n);
      p = Math.max(1, Math.round(p + p * m.params.vol * 2.2 * gaussian(rand) + (target - p) * 0.01));
    }
    return pts.reverse();
  }
}

function removeFrom<T>(arr: T[], item: T): void {
  const i = arr.indexOf(item);
  if (i >= 0) arr.splice(i, 1);
}

/** Prior-session orders and fills that built the demo account's positions (what the Postgres history tables hold). */
function seedDemoHistory(a: Account, now: number, tradeId: () => number): void {
  const day = 86_400_000;
  let id = 4001;
  for (const [i, p] of DEMO_POSITIONS.entries()) {
    const pos = a.position(p.symbol);
    pos.qty = p.qty;
    pos.costCents = p.qty * p.avgCost;
    const legs = [Math.ceil(p.qty * 0.6), p.qty - Math.ceil(p.qty * 0.6)];
    const prices = [p.avgCost - 12, 0];
    prices[1] = Math.round((p.qty * p.avgCost - legs[0] * prices[0]) / legs[1]);
    legs.forEach((q, j) => {
      const t = now - (3 - i) * day - (j + 1) * 3_600_000;
      const orderId = id++;
      a.history.set(orderId, {
        orderId,
        symbol: p.symbol,
        side: "BUY",
        type: "LIMIT",
        timeInForce: "GTC",
        price: prices[j] / 100,
        qty: q,
        filledQty: q,
        avgFillPrice: prices[j] / 100,
        status: "FILLED",
        createdAt: t - 40_000,
        updatedAt: t,
      });
      a.trades.push({
        tradeId: `T${tradeId()}`,
        orderId,
        symbol: p.symbol,
        side: "BUY",
        price: prices[j] / 100,
        qty: q,
        liquidity: j ? "TAKER" : "MAKER",
        time: t,
      });
    });
  }
  const extra: Omit<OrderHistoryRow, "orderId" | "createdAt" | "updatedAt">[] = [
    {
      symbol: "INITD",
      side: "BUY",
      type: "LIMIT",
      timeInForce: "GTC",
      price: 18.2,
      qty: 300,
      filledQty: 0,
      avgFillPrice: null,
      status: "CANCELLED",
      reason: "USER_REQUEST",
    },
    {
      symbol: "GLOBX",
      side: "SELL",
      type: "LIMIT",
      timeInForce: "IOC",
      price: 44.9,
      qty: 100,
      filledQty: 0,
      avgFillPrice: null,
      status: "CANCELLED",
      reason: "IOC_REMAINDER",
    },
    {
      symbol: "NVTA",
      side: "BUY",
      type: "LIMIT",
      timeInForce: "FOK",
      price: 224.0,
      qty: 50,
      filledQty: 0,
      avgFillPrice: null,
      status: "REJECTED",
      reason: "FOK_NOT_FILLABLE",
    },
    {
      symbol: "ACME",
      side: "BUY",
      type: "MARKET",
      timeInForce: "IOC",
      price: null,
      qty: 500000,
      filledQty: 0,
      avgFillPrice: null,
      status: "REJECTED",
      reason: "INSUFFICIENT_FUNDS",
    },
  ];
  extra.forEach((e, i) => {
    const t = now - day - i * 5_400_000;
    const orderId = id++;
    a.history.set(orderId, { ...e, orderId, createdAt: t - 20_000, updatedAt: t });
  });
  a.trades.sort((x, y) => y.time - x.time);
}
