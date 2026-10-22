import type { OrderType, Side, TimeInForce } from "./contracts.js";

/** One pre-trade check; prices are integer ticks. Amends carry the order's new price/qty. */
export interface RiskRequest {
  action: "PLACE" | "AMEND" | "CANCEL";
  participantId: number;
  accountId?: string | number;
  symbol: string;
  orderId: number;
  side?: Side;
  orderType?: OrderType;
  timeInForce?: TimeInForce;
  price: number | null;
  qty: number | null;
}

/**
 * {@code limitPrice} (ticks) is risk's protection price for a MARKET order: the most the order may pay (buy) or least
 * it may receive (sell) given what was reserved. The gateway then sends it to the engine as a LIMIT IOC/FOK.
 */
export type RiskDecision = { approved: true; limitPrice?: number } | { approved: false; reason: string; message: string };

/** The synchronous risk service: reserves cash/shares before the order reaches the engine. */
export interface RiskClient {
  check(request: RiskRequest): Promise<RiskDecision>;
  /** Releases a reservation the gateway took but could not publish. */
  release(request: ReleaseRequest): Promise<void>;
}

/** {@code kind} says which reservation to drop: the order's ({@code place}) or a pending amend's. */
export interface ReleaseRequest {
  participantId: number;
  accountId?: string | number;
  symbol: string;
  orderId: number;
  kind?: "place" | "amend";
}

export class RiskUnavailable extends Error {}

export class HttpRiskClient implements RiskClient {
  constructor(
    private readonly baseUrl: string,
    private readonly timeoutMs = 2_000,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async check(request: RiskRequest): Promise<RiskDecision> {
    const { action, price, qty, ...rest } = request;
    const key = { participantId: rest.participantId, accountId: rest.accountId, symbol: rest.symbol, orderId: rest.orderId };
    const res =
      action === "AMEND"
        ? await this.post("/v1/orders/check-amend", { ...key, newPrice: price, newQty: qty })
        : action === "CANCEL"
          ? await this.post("/v1/orders/check-cancel", key)
          : await this.post("/v1/orders/check", { ...rest, price, qty });
    const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    if (res.ok && body.approved !== false) {
      const limitPrice = Number(body.limitPrice ?? body.protectionPrice);
      return Number.isSafeInteger(limitPrice) && limitPrice > 0 ? { approved: true, limitPrice } : { approved: true };
    }
    if (res.status >= 500) {
      throw new RiskUnavailable(`risk service returned ${res.status}`);
    }
    return {
      approved: false,
      reason: String(body.reason ?? "RISK_REJECTED"),
      message: String(body.message ?? body.detail ?? "rejected by pre-trade risk"),
    };
  }

  async release(request: ReleaseRequest): Promise<void> {
    const res = await this.post("/v1/orders/release", { kind: "place", ...request });
    if (!res.ok && res.status !== 404) {
      throw new RiskUnavailable(`risk service returned ${res.status}`);
    }
  }

  private async post(path: string, body: unknown): Promise<Response> {
    try {
      return await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (e) {
      throw new RiskUnavailable(`risk service unreachable: ${(e as Error).message}`);
    }
  }
}
