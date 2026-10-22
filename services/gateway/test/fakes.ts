import { AdmissionControl, DEFAULT_LIMITS, type Limits } from "../src/admission.js";
import { buildApp } from "../src/app.js";
import type { Authenticator, Principal } from "../src/auth.js";
import { InMemoryBus } from "../src/bus.js";
import { eventsTopic, type CommandMessage, type EngineEvent, type EventRecord } from "../src/contracts.js";
import { Correlator } from "../src/correlator.js";
import { OrderIdAllocator } from "../src/ids.js";
import { OrderIndex } from "../src/orderIndex.js";
import { OrderService } from "../src/orders.js";
import type { RiskClient, RiskDecision, RiskRequest } from "../src/risk.js";
import { DEFAULT_INSTRUMENTS, SymbolDirectory } from "../src/symbols.js";

export const ALICE: Principal = { participantId: 1001, label: "alice" };
export const BOB: Principal = { participantId: 1002, label: "bob" };

export class FakeAuth implements Authenticator {
  keys = new Map<string, Principal>([
    ["key-alice", ALICE],
    ["key-bob", BOB],
  ]);
  tokens = new Map<string, Principal>([["jwt-alice", ALICE]]);
  async apiKey(key: string) {
    return this.keys.get(key) ?? null;
  }
  async token(jwt: string) {
    return this.tokens.get(jwt) ?? null;
  }
}

export class FakeRisk implements RiskClient {
  checks: RiskRequest[] = [];
  releases: { orderId: number }[] = [];
  decide: (r: RiskRequest) => RiskDecision = () => ({ approved: true });
  async check(r: RiskRequest) {
    this.checks.push(r);
    return this.decide(r);
  }
  async release(r: { orderId: number }) {
    this.releases.push(r);
  }
}

/**
 * Minimal per-symbol engine on the bus: rests limit orders, crosses at the maker price, echoes correlationId and
 * stamps a global seq. Enough to exercise the gateway's correlation and read model, not a matching-engine test.
 */
export class FakeEngine {
  seq = 0;
  paused = false;
  held: CommandMessage[] = [];
  private book = new Map<number, { side: "BUY" | "SELL"; price: number; qty: number; owner: number; symbol: string }>();

  constructor(private readonly bus: InMemoryBus, symbols: string[]) {
    void bus.subscribe(
      symbols.map((s) => `commands.${s}`),
      async (record) => {
        const command = JSON.parse(record.value) as CommandMessage;
        if (this.paused) {
          this.held.push(command);
          return;
        }
        await this.process(command);
      },
    );
  }

  async release(order: "fifo" | "lifo" = "fifo") {
    this.paused = false;
    const batch = order === "fifo" ? this.held : [...this.held].reverse();
    this.held = [];
    for (const c of batch) {
      await this.process(c);
    }
  }

  async process(c: CommandMessage) {
    const events: EngineEvent[] = [];
    if (c.type === "Place") {
      events.push({ type: "OrderPlaced", orderId: c.orderId, side: c.side, price: c.price ?? 0, qty: c.qty });
      let remaining = c.qty;
      for (const [id, o] of this.book) {
        if (remaining === 0) break;
        const crosses =
          o.symbol === c.symbol &&
          o.side !== c.side &&
          (c.price === null || (c.side === "BUY" ? o.price <= c.price : o.price >= c.price));
        if (!crosses) continue;
        const q = Math.min(o.qty, remaining);
        events.push({ type: "TradeExecuted", price: o.price, qty: q, makerOrderId: id, takerOrderId: c.orderId, takerSide: c.side });
        o.qty -= q;
        remaining -= q;
        if (o.qty === 0) this.book.delete(id);
      }
      if (remaining > 0 && c.orderType === "LIMIT" && c.timeInForce === "GTC") {
        this.book.set(c.orderId, { side: c.side, price: c.price!, qty: remaining, owner: c.participantId, symbol: c.symbol });
      } else if (remaining > 0) {
        events.push({ type: "OrderCancelled", orderId: c.orderId, cancelledQty: remaining, reason: c.orderType === "MARKET" ? "NO_LIQUIDITY" : "IOC" });
      }
    } else if (c.type === "Cancel") {
      const o = this.book.get(c.orderId);
      if (o) {
        this.book.delete(c.orderId);
        events.push({ type: "OrderCancelled", orderId: c.orderId, cancelledQty: o.qty, reason: "REQUESTED" });
      } else {
        events.push({ type: "OrderRejected", orderId: c.orderId, reason: "UNKNOWN_ORDER" });
      }
    } else {
      const o = this.book.get(c.orderId);
      if (!o) {
        events.push({ type: "OrderRejected", orderId: c.orderId, reason: "UNKNOWN_ORDER" });
      } else {
        const newPrice = c.newPrice ?? o.price;
        const newQty = c.newQty ?? o.qty;
        events.push({ type: "OrderAmended", orderId: c.orderId, oldPrice: o.price, newPrice, oldQty: o.qty, newQty, priorityRetained: newPrice === o.price && newQty <= o.qty });
        o.price = newPrice;
        o.qty = newQty;
      }
    }
    const record: EventRecord = {
      seq: ++this.seq,
      symbol: c.symbol,
      correlationId: c.correlationId,
      participantId: c.participantId,
      command: { type: c.type, orderId: c.orderId, orderType: c.type === "Place" ? c.orderType : undefined },
      events,
    };
    await this.bus.publish(eventsTopic(c.symbol), c.symbol, JSON.stringify(record));
  }
}

export async function harness(opts: { limits?: Partial<Limits>; timeoutMs?: number; clock?: () => number } = {}) {
  const bus = new InMemoryBus();
  const symbols = new SymbolDirectory(DEFAULT_INSTRUMENTS);
  const engine = new FakeEngine(bus, symbols.symbols());
  const auth = new FakeAuth();
  const risk = new FakeRisk();
  const admission = new AdmissionControl({ ...DEFAULT_LIMITS, ...opts.limits }, opts.clock ?? (() => 0));
  const index = new OrderIndex();
  const orders = new OrderService({
    bus,
    risk,
    symbols,
    admission,
    index,
    correlator: new Correlator(opts.timeoutMs ?? 2_000),
    ids: new OrderIdAllocator(0),
  });
  await orders.start();
  const app = await buildApp({ orders, auth, symbols });
  return { app, bus, engine, auth, risk, admission, index, orders, symbols };
}

export const asAlice = { "x-api-key": "key-alice" };
export const asBob = { "x-api-key": "key-bob" };
