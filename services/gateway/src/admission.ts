/**
 * Per-participant admission control, ported from the Java gateway (orderbook.api.AdmissionControl): a token bucket on
 * every order/cancel/amend message, a cap on open orders, and a violation counter that blocks a participant.
 */

export interface Limits {
  messagesPerSec: number;
  burst: number;
  maxOpenOrders: number;
  maxViolations: number;
}

export const DEFAULT_LIMITS: Limits = { messagesPerSec: 200, burst: 400, maxOpenOrders: 100, maxViolations: 100 };

export type Violation = "RATE_LIMITED" | "MAX_OPEN_ORDERS";

/** Milliseconds, monotonic. */
export type Clock = () => number;

export const monotonicClock: Clock = () => performance.now();

/** Refills at {@code ratePerSec} up to {@code burst} tokens; each admitted message costs one. */
export class TokenBucket {
  private tokens: number;
  private last: number;

  constructor(
    private readonly ratePerSec: number,
    private readonly burst: number,
    private readonly clock: Clock,
  ) {
    if (!(ratePerSec > 0) || !(burst >= 1)) {
      throw new Error("rate must be positive and burst at least 1");
    }
    this.tokens = burst;
    this.last = clock();
  }

  tryAcquire(): boolean {
    const now = this.clock();
    this.tokens = Math.min(this.burst, this.tokens + ((now - this.last) / 1000) * this.ratePerSec);
    this.last = now;
    if (this.tokens >= 1) {
      this.tokens -= 1;
      return true;
    }
    return false;
  }
}

export class AdmissionControl {
  private readonly buckets = new Map<number, TokenBucket>();
  private readonly violationCounts = new Map<number, number>();

  constructor(
    readonly limits: Limits = DEFAULT_LIMITS,
    private readonly clock: Clock = monotonicClock,
  ) {
    if (!(limits.messagesPerSec > 0) || limits.burst < 1 || limits.maxOpenOrders < 1 || limits.maxViolations < 1) {
      throw new Error("limits must be positive");
    }
  }

  isBlocked(participantId: number): boolean {
    return this.violations(participantId) >= this.limits.maxViolations;
  }

  violations(participantId: number): number {
    return this.violationCounts.get(participantId) ?? 0;
  }

  /**
   * Admits one message or returns the violation (and counts it). {@code openOrders} is the participant's current
   * number of open orders (resting plus in flight); {@code adds} is true for a new order.
   */
  admit(participantId: number, adds: boolean, openOrders: number): Violation | null {
    let bucket = this.buckets.get(participantId);
    if (!bucket) {
      bucket = new TokenBucket(this.limits.messagesPerSec, this.limits.burst, this.clock);
      this.buckets.set(participantId, bucket);
    }
    let violation: Violation | null = null;
    if (!bucket.tryAcquire()) {
      violation = "RATE_LIMITED";
    } else if (adds && openOrders >= this.limits.maxOpenOrders) {
      violation = "MAX_OPEN_ORDERS";
    }
    if (violation) {
      this.violationCounts.set(participantId, this.violations(participantId) + 1);
    }
    return violation;
  }

  reset(): void {
    this.buckets.clear();
    this.violationCounts.clear();
  }
}
