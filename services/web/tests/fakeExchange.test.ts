import { describe, expect, it } from "vitest";
import { FakeExchange } from "../src/api/mock/fakeExchange";
import { BookReplica } from "../src/marketdata/bookReplica";
import type { MarketDataMessage } from "../src/marketdata/wire";

describe("mock exchange (Pages build)", () => {
  it("publishes a feed whose replica always matches the exchange's own book", () => {
    const ex = new FakeExchange({ seed: 11 });
    const m = ex.market("NVTA");
    const replica = new BookReplica();
    replica.apply(m.snapshot("connect"));
    const msgs: MarketDataMessage[] = [];
    m.listeners.add((x) => msgs.push(x));
    const acct = ex.newAccount(true);
    for (let i = 0; i < 300; i++) {
      ex.step("NVTA");
      if (i % 25 === 0) {
        try {
          ex.place(acct, { symbol: "NVTA", side: i % 50 ? "BUY" : "SELL", type: "LIMIT", timeInForce: "GTC", price: m.last / 100, qty: 3 });
        } catch {
          /* risk rejections are fine here */
        }
      }
    }
    for (const msg of msgs) expect(replica.apply(msg).kind).toBe("delta");
    expect(replica.levels("B")).toEqual(m.snapshot("resync").bids.map(([price, qty, orders]) => ({ price, qty, orders })));
    expect(replica.levels("S")).toEqual(m.snapshot("resync").asks.map(([price, qty, orders]) => ({ price, qty, orders })));
    expect(replica.best("B")!).toBeLessThan(replica.best("S")!);
  });

  it("keeps cash, reservations and shares non-negative and consistent", () => {
    const ex = new FakeExchange({ seed: 5 });
    const a = ex.newAccount(true);
    for (let i = 0; i < 400; i++) {
      for (const s of ["ACME", "GLOBX"]) ex.step(s);
      const s = i % 2 ? "ACME" : "GLOBX";
      const m = ex.market(s);
      try {
        ex.place(a, { symbol: s, side: i % 3 ? "BUY" : "SELL", type: i % 7 ? "LIMIT" : "MARKET", timeInForce: "GTC", price: (m.last + (i % 5) - 2) / 100, qty: 1 + (i % 9) });
      } catch {
        /* rejected by risk */
      }
      for (const sym of ["ACME", "GLOBX"]) {
        const mk = ex.market(sym);
        expect(mk.bestBid()!, `${sym} book locked/crossed at step ${i}`).toBeLessThan(mk.bestAsk()!);
      }
      expect(a.cash).toBeGreaterThanOrEqual(0);
      expect(a.reservedCash).toBeGreaterThanOrEqual(0);
      expect(a.reservedCash).toBeLessThanOrEqual(a.cash);
      const reserved = [...a.orders.values()].filter((o) => o.side === "BUY").reduce((x, o) => x + o.reservedCents, 0);
      expect(a.reservedCash).toBe(reserved);
      a.positions.forEach((p) => {
        expect(p.qty).toBeGreaterThanOrEqual(0);
        expect(p.reserved).toBeGreaterThanOrEqual(0);
        expect(p.reserved).toBeLessThanOrEqual(p.qty);
      });
    }
  });
});
