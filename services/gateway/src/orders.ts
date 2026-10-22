import { randomUUID } from "node:crypto";

import type { AdmissionControl } from "./admission.js";
import type { Principal } from "./auth.js";
import type { Bus } from "./bus.js";
import {
  commandsTopic,
  eventsTopic,
  parseEventRecord,
  type CommandMessage,
  type EngineEvent,
  type EventRecord,
  type OrderType,
  type Side,
  type TimeInForce,
} from "./contracts.js";
import { Correlator, CorrelationTimeout } from "./correlator.js";
import { ApiError } from "./errors.js";
import type { OrderIdAllocator } from "./ids.js";
import type { OrderIndex } from "./orderIndex.js";
import { RiskUnavailable, type RiskClient } from "./risk.js";
import type { SymbolDirectory } from "./symbols.js";
import { parseQty, TickError, type TickConverter } from "./ticks.js";

/** Decimal-priced order input as received from clients. */
export interface PlaceInput {
  symbol?: string;
  side?: string;
  type?: string;
  timeInForce?: string;
  price?: string | number;
  qty?: string | number;
  id?: string | number;
}

export interface AmendInput {
  symbol?: string;
  price?: string | number;
  qty?: string | number;
}

/** HTTP 200 body: the engine's answer (or a pre-engine rejection), prices as decimal strings. */
export interface OrderReply {
  orderId: number;
  symbol: string;
  seq?: number;
  correlationId?: string;
  events: Record<string, unknown>[];
}

export interface OrderServiceDeps {
  bus: Bus;
  risk: RiskClient;
  symbols: SymbolDirectory;
  admission: AdmissionControl;
  index: OrderIndex;
  correlator: Correlator;
  ids: OrderIdAllocator;
}

const SIDES: Side[] = ["BUY", "SELL"];
const TYPES: OrderType[] = ["LIMIT", "MARKET"];
const TIFS: TimeInForce[] = ["GTC", "IOC", "FOK"];

/**
 * The gateway's command path: admission control, ownership, synchronous risk, publish to commands.<symbol>, then wait
 * for the correlated record on events.<symbol>.
 */
export class OrderService {
  constructor(private readonly deps: OrderServiceDeps) {}

  /** Consumes events.<symbol> for every listed symbol into the order index and the correlator. */
  async start(): Promise<void> {
    const topics = this.deps.symbols.symbols().map(eventsTopic);
    await this.deps.bus.subscribe(topics, (record) => {
      const parsed = parseEventRecord(record.value);
      if (parsed) {
        this.onEvents(parsed);
      }
    });
  }

  onEvents(record: EventRecord): void {
    this.deps.index.apply(record);
    this.deps.correlator.complete(record);
  }

  async place(who: Principal, input: PlaceInput): Promise<OrderReply> {
    const { instrument, ticks } = this.instrument(input.symbol);
    const side = choice("side", input.side, SIDES, undefined);
    const orderType = choice("type", input.type, TYPES, "LIMIT");
    const timeInForce = choice("timeInForce", input.timeInForce, TIFS, "GTC");
    const qty = guard(() => parseQty("qty", input.qty, instrument.lotSize));
    const price = orderType === "LIMIT" ? guard(() => ticks.toTicks("price", input.price)) : null;
    const orderId = input.id === undefined || String(input.id).trim() === "" ? this.deps.ids.next() : orderIdOf(input.id);
    const symbol = instrument.symbol;

    this.admit(who, true);
    const decision = await this.risk({
      action: "PLACE",
      participantId: who.participantId,
      accountId: who.accountId,
      symbol,
      orderId,
      side,
      orderType,
      timeInForce,
      price,
      qty,
    });
    if (!decision.approved) {
      return riskRejected(orderId, symbol, who, decision);
    }
    // A protected MARKET order goes to the engine as an aggressive LIMIT that can never rest.
    const sent =
      orderType === "MARKET" && side === "BUY" && decision.limitPrice !== undefined
        ? { orderType: "LIMIT" as const, timeInForce: timeInForce === "FOK" ? ("FOK" as const) : ("IOC" as const), price: decision.limitPrice }
        : { orderType, timeInForce, price };
    this.deps.index.remember(orderId, symbol);
    this.deps.index.placeStarted(who.participantId);
    try {
      return await this.send(
        ticks,
        { type: "Place", correlationId: randomUUID(), participantId: who.participantId, symbol, orderId, side, ...sent, qty },
        () => this.deps.risk.release({ participantId: who.participantId, accountId: who.accountId, symbol, orderId }),
      );
    } finally {
      this.deps.index.placeFinished(who.participantId);
    }
  }

  async cancel(who: Principal, orderId: number, symbolHint?: string): Promise<OrderReply> {
    const { symbol, ticks } = this.routeExisting(orderId, symbolHint);
    this.admit(who, false);
    const notOwn = this.notOwn(who, orderId, symbol);
    if (notOwn) {
      return notOwn;
    }
    // Risk owns the ledger across gateway instances; it confirms ownership but reserves nothing for a cancel.
    const decision = await this.risk({ action: "CANCEL", participantId: who.participantId, accountId: who.accountId, symbol, orderId, price: null, qty: null });
    if (!decision.approved) {
      return riskRejected(orderId, symbol, who, decision);
    }
    return this.send(ticks, { type: "Cancel", correlationId: randomUUID(), participantId: who.participantId, symbol, orderId });
  }

  async amend(who: Principal, orderId: number, input: AmendInput): Promise<OrderReply> {
    const { symbol, ticks, lotSize } = this.routeExisting(orderId, input.symbol);
    const hasPrice = input.price !== undefined && String(input.price).trim() !== "";
    const hasQty = input.qty !== undefined && String(input.qty).trim() !== "";
    if (!hasPrice && !hasQty) {
      throw new ApiError(400, "BAD_REQUEST", "price or qty is required");
    }
    const newPrice = hasPrice ? guard(() => ticks.toTicks("price", input.price)) : null;
    const newQty = hasQty ? guard(() => parseQty("qty", input.qty, lotSize)) : null;
    this.admit(who, false);
    const notOwn = this.notOwn(who, orderId, symbol);
    if (notOwn) {
      return notOwn;
    }
    const decision = await this.risk({
      action: "AMEND",
      participantId: who.participantId,
      accountId: who.accountId,
      symbol,
      orderId,
      price: newPrice,
      qty: newQty,
    });
    if (!decision.approved) {
      return riskRejected(orderId, symbol, who, decision);
    }
    return this.send(ticks, {
      type: "Amend",
      correlationId: randomUUID(),
      participantId: who.participantId,
      symbol,
      orderId,
      newPrice,
      newQty,
    }, () => this.deps.risk.release({ participantId: who.participantId, accountId: who.accountId, symbol, orderId, kind: "amend" }));
  }

  /** 403 once blocked; 429 (counted as a violation) when over the rate or open-order limit. */
  checkNotBlocked(who: Principal): void {
    if (this.deps.admission.isBlocked(who.participantId)) {
      throw new ApiError(
        403,
        "BLOCKED",
        `${who.label} is blocked after ${this.deps.admission.limits.maxViolations} limit violations`,
      );
    }
  }

  private admit(who: Principal, adds: boolean): void {
    const limits = this.deps.admission.limits;
    const violation = this.deps.admission.admit(who.participantId, adds, this.deps.index.openOrders(who.participantId));
    if (violation === "RATE_LIMITED") {
      throw new ApiError(
        429,
        violation,
        `rate limit of ${limits.messagesPerSec} msgs/sec (burst ${limits.burst}) exceeded`,
      );
    }
    if (violation === "MAX_OPEN_ORDERS") {
      throw new ApiError(429, violation, `at most ${limits.maxOpenOrders} open orders per participant`);
    }
  }

  private notOwn(who: Principal, orderId: number, symbol: string): OrderReply | null {
    const owner = this.deps.index.owner(orderId);
    if (owner === undefined || owner === who.participantId) {
      return null;
    }
    return {
      orderId,
      symbol,
      events: [
        {
          type: "Rejected",
          orderId,
          participant: who.label,
          reason: "NOT_OWN_OPEN_ORDER",
          message: `order ${orderId} is not one of your open orders`,
        },
      ],
    };
  }

  private async risk(request: Parameters<RiskClient["check"]>[0]) {
    try {
      return await this.deps.risk.check(request);
    } catch (e) {
      if (e instanceof RiskUnavailable) {
        throw new ApiError(503, "RISK_UNAVAILABLE", e.message);
      }
      throw e;
    }
  }

  private async send(ticks: TickConverter, command: CommandMessage, onUnpublished?: () => Promise<void>): Promise<OrderReply> {
    const response = this.deps.correlator.expect(command.correlationId);
    response.catch(() => undefined);
    try {
      await this.deps.bus.publish(commandsTopic(command.symbol), command.symbol, JSON.stringify(command));
    } catch (e) {
      this.deps.correlator.cancel(command.correlationId, e as Error);
      await onUnpublished?.().catch(() => undefined);
      throw new ApiError(503, "BUS_UNAVAILABLE", `could not publish command: ${(e as Error).message}`);
    }
    let record: EventRecord;
    try {
      record = await response;
    } catch (e) {
      if (e instanceof CorrelationTimeout) {
        throw new ApiError(504, "ENGINE_TIMEOUT", "no response from the matching engine; the order state is unknown", {
          orderId: command.orderId,
          correlationId: command.correlationId,
        });
      }
      throw e;
    }
    return {
      orderId: command.orderId,
      symbol: command.symbol,
      seq: record.seq,
      correlationId: command.correlationId,
      events: record.events.map((e) => renderEvent(e, ticks)),
    };
  }

  private instrument(symbol: string | undefined) {
    const s = (symbol ?? "").trim().toUpperCase();
    if (!s) {
      throw new ApiError(400, "BAD_REQUEST", "symbol is required");
    }
    const hit = this.deps.symbols.get(s);
    if (!hit) {
      throw new ApiError(400, "UNKNOWN_SYMBOL", `unknown symbol ${s}; listed: ${this.deps.symbols.symbols().join(", ")}`);
    }
    return hit;
  }

  /** Cancel/amend route by order id; the symbol comes from the index, or the caller's hint for unknown orders. */
  private routeExisting(orderId: number, hint?: string) {
    const known = this.deps.index.symbol(orderId);
    const symbol = known ?? (hint && hint.trim() ? hint : undefined);
    if (!symbol) {
      throw new ApiError(404, "UNKNOWN_ORDER", `order ${orderId} is unknown; pass symbol to route it`);
    }
    const { instrument, ticks } = this.instrument(symbol);
    if (known && hint && hint.trim() && hint.trim().toUpperCase() !== known) {
      throw new ApiError(400, "BAD_REQUEST", `order ${orderId} trades ${known}, not ${hint.trim().toUpperCase()}`);
    }
    return { symbol: instrument.symbol, ticks, lotSize: instrument.lotSize };
  }
}

/** Engine event with integer ticks rendered as decimal strings for clients. */
export function renderEvent(e: EngineEvent, ticks: TickConverter): Record<string, unknown> {
  switch (e.type) {
    case "OrderPlaced":
      return { ...e, price: e.price > 0 ? ticks.toDecimal(e.price) : null };
    case "OrderAmended":
      return { ...e, oldPrice: ticks.toDecimal(e.oldPrice), newPrice: ticks.toDecimal(e.newPrice) };
    case "TradeExecuted":
      return { ...e, price: ticks.toDecimal(e.price) };
    default:
      return { ...e };
  }
}

function riskRejected(orderId: number, symbol: string, who: Principal, d: { reason: string; message: string }): OrderReply {
  return {
    orderId,
    symbol,
    events: [{ type: "RiskRejected", orderId, participant: who.label, reason: d.reason, message: d.message }],
  };
}

function choice<T extends string>(name: string, value: string | undefined, allowed: T[], fallback: T | undefined): T {
  const v = (value ?? "").trim().toUpperCase();
  if (!v) {
    if (fallback === undefined) {
      throw new ApiError(400, "BAD_REQUEST", `${name} is required`);
    }
    return fallback;
  }
  if (!(allowed as string[]).includes(v)) {
    throw new ApiError(400, "BAD_REQUEST", `${name} must be one of ${allowed.join(", ")}`);
  }
  return v as T;
}

function guard<T>(f: () => T): T {
  try {
    return f();
  } catch (e) {
    if (e instanceof TickError) {
      throw new ApiError(400, "BAD_REQUEST", e.message);
    }
    throw e;
  }
}

export function orderIdOf(value: string | number): number {
  const text = String(value).trim();
  const id = Number(text);
  if (!/^\d+$/.test(text) || !Number.isSafeInteger(id) || id <= 0) {
    throw new ApiError(400, "BAD_REQUEST", "id must be a positive integer");
  }
  return id;
}
