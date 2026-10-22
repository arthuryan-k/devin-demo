/**
 * The events.<symbol> record: one per engine command, carrying everything the command produced, in order.
 * Field names follow the Phase 2 bus contract; parsing is lenient about representation (numbers may arrive as
 * JSON numbers or strings, prices as integer ticks or decimal strings) and strict about meaning.
 */
export interface EventRecord {
  symbol?: string;
  seq: number | string;
  participantId?: number | string | null;
  correlationId?: string | null;
  ts?: number | string;
  events: EngineEvent[];
}

export type EngineEvent = { type: string } & Record<string, unknown>;

export type OrderEventType = "PLACED" | "AMENDED" | "CANCELLED" | "REJECTED";

export interface OrderRow {
  symbol: string;
  seq: bigint;
  eventIndex: number;
  orderId: bigint;
  participantId: bigint | null;
  eventType: OrderEventType;
  side: "BUY" | "SELL" | null;
  orderType: string | null;
  timeInForce: string | null;
  priceTicks: bigint | null;
  qty: bigint | null;
  reason: string | null;
  details: Record<string, unknown>;
  eventTime: Date;
}

export interface TradeRow {
  symbol: string;
  seq: bigint;
  eventIndex: number;
  makerOrderId: bigint;
  takerOrderId: bigint;
  /** null when the record does not carry it; the writer then resolves it from the maker's PLACED row. */
  makerParticipantId: bigint | null;
  takerParticipantId: bigint | null;
  aggressorSide: "BUY" | "SELL" | null;
  priceTicks: bigint;
  qty: bigint;
  eventTime: Date;
}

export interface HistoryRows {
  orders: OrderRow[];
  trades: TradeRow[];
}

export class MalformedRecordError extends Error {}

/** Engine prices are integer ticks of 0.01 (100 ticks = 1.00). */
const TICK_DECIMALS = 2;

const ORDER_EVENTS: Record<string, OrderEventType> = {
  OrderPlaced: "PLACED",
  OrderAmended: "AMENDED",
  OrderCancelled: "CANCELLED",
  OrderRejected: "REJECTED",
};

/** Maps one bus record to history rows. {@code topicSymbol} is used when the record has no symbol of its own. */
export function toRows(record: unknown, topicSymbol: string, fallbackTime: Date): HistoryRows {
  if (!isObject(record) || !Array.isArray(record.events)) {
    throw new MalformedRecordError("record must be an object with an events array");
  }
  const symbol = String(record.symbol ?? topicSymbol).toUpperCase();
  if (symbol !== topicSymbol.toUpperCase()) {
    throw new MalformedRecordError(`record for ${symbol} on the events topic of ${topicSymbol}`);
  }
  const seq = requiredInt(record.seq, "seq");
  const issuer = optionalInt(record.participantId, "participantId");
  const eventTime = record.ts === undefined || record.ts === null ? fallbackTime : timestamp(record.ts);
  const details = record.correlationId ? { correlationId: String(record.correlationId) } : {};

  const rows: HistoryRows = { orders: [], trades: [] };
  record.events.forEach((event: unknown, eventIndex: number) => {
    if (!isObject(event) || typeof event.type !== "string") {
      throw new MalformedRecordError(`events[${eventIndex}] has no type`);
    }
    const key = { symbol, seq, eventIndex, eventTime };
    if (event.type === "TradeExecuted") {
      const t = isObject(event.trade) ? event.trade : event;
      rows.trades.push({
        ...key,
        makerOrderId: requiredInt(t.makerOrderId, "makerOrderId"),
        takerOrderId: requiredInt(t.takerOrderId, "takerOrderId"),
        makerParticipantId: optionalInt(t.makerParticipantId, "makerParticipantId"),
        takerParticipantId: optionalInt(t.takerParticipantId, "takerParticipantId") ?? issuer,
        aggressorSide: side(t.takerSide ?? t.aggressorSide),
        priceTicks: requiredPrice(t.price, "price"),
        qty: requiredInt(t.qty, "qty"),
      });
      return;
    }
    const eventType = ORDER_EVENTS[event.type];
    if (!eventType) {
      return; // Not a history event (e.g. a future market-data-only event): skip, keep its index reserved.
    }
    rows.orders.push({ ...key, ...orderFields(event, eventType, issuer), details: { ...details, ...extra(event) } });
  });
  return rows;
}

function orderFields(e: Record<string, unknown>, eventType: OrderEventType, issuer: bigint | null) {
  const amended = eventType === "AMENDED";
  return {
    eventType,
    orderId: requiredInt(e.orderId, "orderId"),
    participantId: optionalInt(e.participantId, "participantId") ?? issuer,
    side: side(e.side),
    orderType: optionalString(e.orderType),
    timeInForce: optionalString(e.timeInForce),
    priceTicks: optionalPrice(amended ? e.newPrice : e.price, "price"),
    qty: optionalInt(amended ? e.newQty : eventType === "CANCELLED" ? e.cancelledQty : e.qty, "qty"),
    reason: optionalString(e.reason),
  };
}

/** Fields without a column of their own, kept in details. */
function extra(e: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (e.type === "OrderAmended") {
    out.oldPriceTicks = optionalPrice(e.oldPrice, "oldPrice")?.toString() ?? null;
    out.oldQty = optionalInt(e.oldQty, "oldQty")?.toString() ?? null;
    if (e.priorityRetained !== undefined) out.priorityRetained = e.priorityRetained === true || e.priorityRetained === "true";
  }
  if (e.seqNum !== undefined) out.bookSeqNum = String(e.seqNum);
  return out;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function optionalInt(v: unknown, field: string): bigint | null {
  if (v === undefined || v === null || v === "") return null;
  if ((typeof v === "number" && Number.isSafeInteger(v)) || (typeof v === "string" && /^-?\d+$/.test(v))) {
    return BigInt(v);
  }
  throw new MalformedRecordError(`${field} must be an integer, got ${JSON.stringify(v)}`);
}

function requiredInt(v: unknown, field: string): bigint {
  const n = optionalInt(v, field);
  if (n === null) throw new MalformedRecordError(`${field} is required`);
  return n;
}

/** Integers are ticks; decimal strings ("101.25") are converted to ticks exactly. */
function optionalPrice(v: unknown, field: string): bigint | null {
  if (typeof v === "string" && v.includes(".")) {
    const m = /^(\d+)\.(\d+)$/.exec(v);
    if (!m || m[2].length > TICK_DECIMALS) throw new MalformedRecordError(`${field} is not a tick price: ${v}`);
    return BigInt(m[1] + m[2].padEnd(TICK_DECIMALS, "0"));
  }
  const n = optionalInt(v, field);
  return n === 0n ? null : n; // Market orders report price 0.
}

function requiredPrice(v: unknown, field: string): bigint {
  const n = optionalPrice(v, field);
  if (n === null) throw new MalformedRecordError(`${field} is required`);
  return n;
}

function optionalString(v: unknown): string | null {
  return typeof v === "string" && v !== "" ? v : null;
}

function side(v: unknown): "BUY" | "SELL" | null {
  return v === "BUY" || v === "SELL" ? v : null;
}

function timestamp(v: unknown): Date {
  const d = typeof v === "number" || /^\d+$/.test(String(v)) ? new Date(Number(v)) : new Date(String(v));
  if (Number.isNaN(d.getTime())) throw new MalformedRecordError(`ts is not a timestamp: ${String(v)}`);
  return d;
}
