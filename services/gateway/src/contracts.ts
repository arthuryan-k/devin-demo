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

/** One engine event (schemas/bus/events.schema.json); every event also carries the envelope's {@code seq}. */
export type EngineEvent = { seq?: number } & (
  | {
      type: "OrderPlaced";
      orderId: number;
      side: Side;
      orderType?: OrderType;
      timeInForce?: TimeInForce;
      /** 0 for MARKET. */
      price: number;
      qty: number;
      seqNum?: number;
    }
  | { type: "OrderRejected"; orderId: number; reason: string }
  | { type: "OrderCancelled"; orderId: number; cancelledQty: number; reason: string }
  | {
      type: "OrderAmended";
      orderId: number;
      oldPrice: number;
      newPrice: number;
      oldQty: number;
      newQty: number;
      seqNum?: number;
      priorityRetained: boolean;
    }
  | {
      type: "TradeExecuted";
      price: number;
      qty: number;
      makerOrderId: number;
      takerOrderId: number;
      makerParticipantId?: number;
      takerParticipantId?: number;
      takerSide?: Side;
    }
);

/** One events.<symbol> record: exactly one per sequenced command, {@code seq} contiguous from 0. */
export interface EventRecord {
  v?: number;
  seq: number;
  ts?: number;
  commandOffset?: number;
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
