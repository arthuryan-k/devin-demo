import type { EventRecord, OrderType } from "./contracts.js";

interface OpenOrder {
  participantId: number;
  symbol: string;
  remaining: number;
}

/**
 * Read model built from events.<symbol>: which orders are open, who owns them and which symbol they trade, so the
 * gateway can enforce the open-order cap and ownership, and route cancel/amend by order id.
 */
export class OrderIndex {
  private readonly open = new Map<number, OpenOrder>();
  private readonly symbolOf = new Map<number, string>();
  private readonly counts = new Map<number, number>();
  private readonly inFlight = new Map<number, number>();

  owner(orderId: number): number | undefined {
    return this.open.get(orderId)?.participantId;
  }

  /** Symbol of any order this gateway has seen (open or not). */
  symbol(orderId: number): string | undefined {
    return this.symbolOf.get(orderId);
  }

  remember(orderId: number, symbol: string): void {
    this.symbolOf.set(orderId, symbol);
  }

  /** Open orders plus places submitted but not yet answered by the engine. */
  openOrders(participantId: number): number {
    return (this.counts.get(participantId) ?? 0) + (this.inFlight.get(participantId) ?? 0);
  }

  placeStarted(participantId: number): void {
    this.inFlight.set(participantId, (this.inFlight.get(participantId) ?? 0) + 1);
  }

  placeFinished(participantId: number): void {
    const n = (this.inFlight.get(participantId) ?? 0) - 1;
    if (n > 0) {
      this.inFlight.set(participantId, n);
    } else {
      this.inFlight.delete(participantId);
    }
  }

  apply(record: EventRecord): void {
    const takerType: OrderType | undefined = record.command?.orderType;
    let taker: { orderId: number; owner: number; remaining: number; done: boolean } | null = null;
    for (const e of record.events) {
      switch (e.type) {
        case "OrderPlaced":
          this.symbolOf.set(e.orderId, record.symbol);
          taker = { orderId: e.orderId, owner: record.participantId, remaining: e.qty, done: takerType === "MARKET" };
          break;
        case "TradeExecuted": {
          this.fill(e.makerOrderId, e.qty);
          if (taker && taker.orderId === e.takerOrderId) {
            taker.remaining -= e.qty;
          } else {
            this.fill(e.takerOrderId, e.qty);
          }
          break;
        }
        case "OrderCancelled":
          if (taker && taker.orderId === e.orderId) {
            taker.done = true;
          } else {
            this.close(e.orderId);
          }
          break;
        case "OrderAmended": {
          const o = this.open.get(e.orderId);
          if (o) {
            o.remaining = e.newQty;
            if (!e.priorityRetained) {
              // The amended order re-enters like a new taker and may trade.
              this.close(e.orderId);
              taker = { orderId: e.orderId, owner: o.participantId, remaining: e.newQty, done: false };
            }
          }
          break;
        }
        default:
          break;
      }
    }
    if (taker && !taker.done && taker.remaining > 0) {
      this.open.set(taker.orderId, { participantId: taker.owner, symbol: record.symbol, remaining: taker.remaining });
      this.counts.set(taker.owner, (this.counts.get(taker.owner) ?? 0) + 1);
    }
  }

  private fill(orderId: number, qty: number): void {
    const o = this.open.get(orderId);
    if (!o) {
      return;
    }
    o.remaining -= qty;
    if (o.remaining <= 0) {
      this.close(orderId);
    }
  }

  private close(orderId: number): void {
    const o = this.open.get(orderId);
    if (!o) {
      return;
    }
    this.open.delete(orderId);
    const n = (this.counts.get(o.participantId) ?? 0) - 1;
    if (n > 0) {
      this.counts.set(o.participantId, n);
    } else {
      this.counts.delete(o.participantId);
    }
  }
}
