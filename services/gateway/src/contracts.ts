/**
 * Wire format of the bus topics the gateway uses (JSON, integer ticks):
 *  - commands.<symbol>: one {@link CommandMessage} per Place / Cancel / Amend, keyed by symbol.
 *  - events.<symbol>: one {@link EventRecord} per engine command, stamped with the engine's global seq and the
 *    command's correlationId.
 */

export type Side = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type TimeInForce = "GTC" | "IOC" | "FOK";

export interface PlaceCommand {
  type: "Place";
  correlationId: string;
  participantId: number;
  symbol: string;
  orderId: number;
  side: Side;
  orderType: OrderType;
  timeInForce: TimeInForce;
  price: number | null;
  qty: number;
}

export interface CancelCommand {
  type: "Cancel";
  correlationId: string;
  participantId: number;
  symbol: string;
  orderId: number;
}

export interface AmendCommand {
  type: "Amend";
  correlationId: string;
  participantId: number;
  symbol: string;
  orderId: number;
  newPrice: number | null;
  newQty: number | null;
}

export type CommandMessage = PlaceCommand | CancelCommand | AmendCommand;

export type EngineEvent =
  | { type: "OrderPlaced"; orderId: number; side: Side; price: number; qty: number; seqNum?: number }
  | { type: "OrderRejected"; orderId: number; reason: string }
  | { type: "OrderCancelled"; orderId: number; cancelledQty: number; reason: string }
  | {
      type: "OrderAmended";
      orderId: number;
      oldPrice: number;
      newPrice: number;
      oldQty: number;
      newQty: number;
      priorityRetained: boolean;
    }
  | {
      type: "TradeExecuted";
      price: number;
      qty: number;
      makerOrderId: number;
      takerOrderId: number;
      takerSide?: Side;
    };

export interface EventRecord {
  seq: number;
  symbol: string;
  correlationId?: string | null;
  participantId: number;
  command?: { type: string; orderId?: number; orderType?: OrderType };
  events: EngineEvent[];
}

export const commandsTopic = (symbol: string) => `commands.${symbol}`;
export const eventsTopic = (symbol: string) => `events.${symbol}`;

export function parseEventRecord(value: string): EventRecord | null {
  try {
    const r = JSON.parse(value) as EventRecord;
    if (typeof r.seq !== "number" || !Array.isArray(r.events)) {
      return null;
    }
    return r;
  } catch {
    return null;
  }
}
