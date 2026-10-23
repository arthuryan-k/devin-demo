import type { Backend, FeedStatus, MarketSubscription } from "../api/backend";
import type { Instrument, PricePoint, Side } from "../api/types";
import { fromTicks } from "../lib/format";
import { BookReplica } from "./bookReplica";
import type { Level } from "./types";
import type { WireTrade } from "./wire";

export interface TradePrint {
  id: string;
  time: number;
  price: number;
  qty: number;
  side: Side | null;
  mine: boolean;
}

export interface MarketState {
  symbol: string;
  status: FeedStatus;
  seq: number;
  bids: Level[];
  asks: Level[];
  bestBid: number | null;
  bestAsk: number | null;
  last: number | null;
  open: number | null;
  high: number | null;
  low: number | null;
  volume: number;
  trades: TradePrint[];
  series: PricePoint[];
  resyncs: number;
}

const DEPTH = 20;
const MAX_TRADES = 200;
const MAX_POINTS = 5000;

interface Entry {
  inst: Instrument;
  replica: BookReplica;
  sub?: MarketSubscription;
  status: FeedStatus;
  trades: TradePrint[];
  series: PricePoint[];
  seenTrades: Set<string>;
  open: number | null;
  high: number | null;
  low: number | null;
  volume: number;
  resyncs: number;
  state: MarketState;
}

const schedule: (fn: () => void) => void =
  typeof requestAnimationFrame === "function" ? (fn) => requestAnimationFrame(() => fn()) : (fn) => setTimeout(fn, 16);

/**
 * Keeps one book replica + trade tape + price series per symbol, fed by the backend's market-data subscriptions.
 * React reads immutable per-symbol `MarketState` objects through `subscribe`/`get` (useSyncExternalStore);
 * notifications are batched to one per animation frame.
 */
export class MarketStore {
  private readonly entries = new Map<string, Entry>();
  private readonly listeners = new Set<() => void>();
  private readonly dirty = new Set<string>();
  private flushQueued = false;

  constructor(
    private readonly backend: Backend,
    instruments: Instrument[],
    private readonly isMine: (label: string | undefined) => boolean = (l) => l === "YOU",
  ) {
    for (const inst of instruments) {
      const e: Entry = {
        inst,
        replica: new BookReplica(),
        status: "connecting",
        trades: [],
        series: [],
        seenTrades: new Set(),
        open: null,
        high: null,
        low: null,
        volume: 0,
        resyncs: 0,
        state: undefined as unknown as MarketState,
      };
      e.state = this.build(e);
      this.entries.set(inst.symbol, e);
    }
  }

  start(): void {
    for (const e of this.entries.values()) {
      if (e.sub) continue;
      void this.backend.priceHistory(e.inst.symbol).then((pts) => {
        if (!pts.length) return;
        e.series = [...pts, ...e.series].slice(-MAX_POINTS);
        e.open = pts[0].price;
        for (const p of pts) this.extremes(e, p.price);
        this.markDirty(e.inst.symbol);
      });
      e.sub = this.backend.subscribeMarket(e.inst.symbol, {
        onStatus: (s) => {
          e.status = s;
          this.markDirty(e.inst.symbol);
        },
        onMessage: (msg) => {
          const r = e.replica.apply(msg);
          if (r.kind === "gap") {
            e.resyncs++;
            e.sub?.resync();
          } else if (r.kind !== "stale") {
            this.addTrades(e, r.trades, r.kind === "snapshot");
          }
          this.markDirty(e.inst.symbol);
        },
      });
    }
  }

  stop(): void {
    for (const e of this.entries.values()) {
      e.sub?.close();
      e.sub = undefined;
    }
  }

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  get(symbol: string): MarketState {
    const e = this.entries.get(symbol);
    if (!e) throw new Error(`Unknown symbol ${symbol}`);
    return e.state;
  }

  /** Synchronously rebuilds pending states and notifies (tests). */
  flush(): void {
    this.flushQueued = false;
    if (!this.dirty.size) return;
    for (const s of this.dirty) {
      const e = this.entries.get(s)!;
      e.state = this.build(e);
    }
    this.dirty.clear();
    this.listeners.forEach((l) => l());
  }

  private markDirty(symbol: string): void {
    this.dirty.add(symbol);
    if (!this.flushQueued) {
      this.flushQueued = true;
      schedule(() => this.flush());
    }
  }

  private extremes(e: Entry, price: number): void {
    e.high = e.high == null ? price : Math.max(e.high, price);
    e.low = e.low == null ? price : Math.min(e.low, price);
  }

  private addTrades(e: Entry, trades: WireTrade[], fromSnapshot: boolean): void {
    const now = Date.now();
    const tick = e.inst.tickSize;
    for (const t of trades) {
      const id = t.n != null ? `n${t.n}` : `${t.makerOrderId}-${t.takerOrderId}-${t.qty}`;
      if (e.seenTrades.has(id)) continue;
      e.seenTrades.add(id);
      const price = fromTicks(t.price, tick);
      const time = t.ts ?? now;
      e.trades.unshift({
        id,
        time,
        price,
        qty: t.qty,
        side: t.takerSide,
        mine: this.isMine(t.maker) || this.isMine(t.taker),
      });
      if (!fromSnapshot) e.volume += t.qty;
      if (e.open == null) e.open = price;
      this.extremes(e, price);
      e.series.push({ time, price, qty: t.qty });
    }
    if (e.trades.length > MAX_TRADES) e.trades.length = MAX_TRADES;
    if (e.series.length > MAX_POINTS) e.series.splice(0, e.series.length - MAX_POINTS);
    if (e.seenTrades.size > 4 * MAX_TRADES) e.seenTrades = new Set(e.trades.map((t) => t.id));
  }

  private build(e: Entry): MarketState {
    const tick = e.inst.tickSize;
    const conv = (l: Level): Level => ({ price: fromTicks(l.price, tick), qty: l.qty, orders: l.orders });
    const bb = e.replica.best("B");
    const ba = e.replica.best("S");
    const lastPoint = e.series[e.series.length - 1];
    return {
      symbol: e.inst.symbol,
      status: e.status,
      seq: e.replica.seq,
      bids: e.replica.levels("B", DEPTH).map(conv),
      asks: e.replica.levels("S", DEPTH).map(conv),
      bestBid: bb == null ? null : fromTicks(bb, tick),
      bestAsk: ba == null ? null : fromTicks(ba, tick),
      last: e.trades[0]?.price ?? lastPoint?.price ?? null,
      open: e.open,
      high: e.high,
      low: e.low,
      volume: e.volume,
      trades: e.trades.slice(0, 80),
      series: e.series,
      resyncs: e.resyncs,
    };
  }
}
