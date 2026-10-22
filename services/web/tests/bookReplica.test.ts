import { describe, expect, it } from "vitest";
import { BookReplica } from "../src/marketdata/bookReplica";
import { ACME_DELTAS, ACME_SNAPSHOT } from "./fixtures";

describe("BookReplica", () => {
  it("rebuilds the book from a snapshot plus consecutive deltas", () => {
    const r = new BookReplica();
    expect(r.apply(ACME_SNAPSHOT).kind).toBe("snapshot");
    for (const d of ACME_DELTAS) expect(r.apply(d).kind).toBe("delta");
    expect(r.seq).toBe(43);
    expect(r.levels("B")).toEqual([
      { price: 10001, qty: 75, orders: 1 },
      { price: 10000, qty: 300, orders: 3 },
      { price: 9999, qty: 90, orders: 1 },
      { price: 9998, qty: 500, orders: 4 },
    ]);
    expect(r.levels("S").map((l) => l.price)).toEqual([10003, 10005]);
    expect(r.best("B")).toBe(10001);
    expect(r.best("S")).toBe(10003);
  });

  it("returns trade prints from deltas", () => {
    const r = new BookReplica();
    r.apply(ACME_SNAPSHOT);
    const res = r.apply(ACME_DELTAS[0]);
    expect(res.kind === "delta" && res.trades.map((t) => [t.price, t.qty])).toEqual([[10002, 200]]);
  });

  it("ignores stale deltas and flags gaps until the next snapshot", () => {
    const r = new BookReplica();
    expect(r.apply(ACME_DELTAS[0]).kind).toBe("stale"); // nothing before a snapshot
    r.apply(ACME_SNAPSHOT);
    expect(r.apply({ ...ACME_DELTAS[0], seq: 41 }).kind).toBe("stale");
    expect(r.apply(ACME_DELTAS[1])).toEqual({ kind: "gap", expected: 42, got: 43 });
    expect(r.synced).toBe(false);
    expect(r.apply(ACME_DELTAS[1]).kind).toBe("stale");
    r.apply({ ...ACME_SNAPSHOT, seq: 50, reason: "resync" });
    expect(r.seq).toBe(50);
  });

  it("syncs from a pre-trading snapshot at seq -1 and takes seq 0 as the next delta", () => {
    const r = new BookReplica();
    r.apply({ type: "snapshot", symbol: "ACME", seq: -1, reason: "subscribe", bids: [], asks: [], trades: [], events: [] });
    expect(r.synced).toBe(true);
    expect(r.apply({ type: "delta", symbol: "ACME", seq: 0, levels: [["a", "B", 10000, 5, 1]], events: [] }).kind).toBe("delta");
    expect(r.levels("B")).toEqual([{ price: 10000, qty: 5, orders: 1 }]);
  });
});
