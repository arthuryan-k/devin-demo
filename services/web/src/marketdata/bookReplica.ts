import type { Level } from "./types";
import type { MarketDataMessage, WireLevel, WireTrade } from "./wire";
import { isTrade } from "./wire";

export type ApplyResult =
  | { kind: "snapshot"; trades: WireTrade[] }
  | { kind: "delta"; trades: WireTrade[] }
  | { kind: "stale" }
  | { kind: "gap"; expected: number; got: number };

/**
 * Local L2 replica of one symbol's book, rebuilt from a snapshot and kept current by strictly consecutive deltas.
 * Prices stay in ticks here; callers convert with the instrument's tick size.
 */
export class BookReplica {
  private bids = new Map<number, WireLevel>();
  private asks = new Map<number, WireLevel>();
  private _seq = -1;

  get seq(): number {
    return this._seq;
  }

  get synced(): boolean {
    return this._seq >= 0;
  }

  apply(msg: MarketDataMessage): ApplyResult {
    if (msg.type === "snapshot") {
      this.bids = new Map(msg.bids.map((l) => [l[0], l]));
      this.asks = new Map(msg.asks.map((l) => [l[0], l]));
      this._seq = msg.seq;
      return { kind: "snapshot", trades: msg.trades ?? [] };
    }
    if (!this.synced || msg.seq <= this._seq) {
      return { kind: "stale" };
    }
    if (msg.seq !== this._seq + 1) {
      const expected = this._seq + 1;
      this._seq = -1;
      return { kind: "gap", expected, got: msg.seq };
    }
    for (const [op, side, price, qty, orders] of msg.levels) {
      const book = side === "B" ? this.bids : this.asks;
      if (op === "r") book.delete(price);
      else book.set(price, [price, qty, orders]);
    }
    this._seq = msg.seq;
    return { kind: "delta", trades: msg.events.filter(isTrade) };
  }

  /** Best-first levels, prices in ticks. */
  levels(side: "B" | "S", depth = Infinity): Level[] {
    const book = side === "B" ? this.bids : this.asks;
    const sorted = [...book.values()].sort((a, b) => (side === "B" ? b[0] - a[0] : a[0] - b[0]));
    return sorted.slice(0, depth).map(([price, qty, orders]) => ({ price, qty, orders }));
  }

  best(side: "B" | "S"): number | null {
    const book = side === "B" ? this.bids : this.asks;
    if (book.size === 0) return null;
    let best: number | null = null;
    for (const p of book.keys()) {
      if (best === null || (side === "B" ? p > best : p < best)) best = p;
    }
    return best;
  }
}
