import type { OrderType, Side, TimeInForce } from "./contracts.js";

/** One pre-trade check; prices are integer ticks. Amends carry the order's new price/qty. */
export interface RiskRequest {
  action: "PLACE" | "AMEND";
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

export type RiskDecision = { approved: true } | { approved: false; reason: string; message: string };

/** The synchronous risk service: reserves cash/shares before the order reaches the engine. */
export interface RiskClient {
  check(request: RiskRequest): Promise<RiskDecision>;
  /** Releases a reservation the gateway took but could not publish. */
  release(request: { participantId: number; accountId?: string | number; symbol: string; orderId: number }): Promise<void>;
}

export class RiskUnavailable extends Error {}

export class HttpRiskClient implements RiskClient {
  constructor(
    private readonly baseUrl: string,
    private readonly timeoutMs = 2_000,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async check(request: RiskRequest): Promise<RiskDecision> {
    const res = await this.post("/risk/check", request);
    const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    if (res.ok && body.approved !== false) {
      return { approved: true };
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

  async release(request: { participantId: number; symbol: string; orderId: number }): Promise<void> {
    const res = await this.post("/risk/release", request);
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
