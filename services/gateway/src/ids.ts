/**
 * Order ids unique across gateway instances without coordination: milliseconds since 2026-01-01, a 4-bit instance
 * id and an 8-bit counter, packed into a JavaScript safe integer (and a Java long). Monotonic per instance.
 */
const EPOCH_MS = Date.UTC(2026, 0, 1);
const INSTANCE_BITS = 4;
const COUNTER_BITS = 8;

export class OrderIdAllocator {
  private lastMs = 0;
  private counter = 0;

  constructor(
    private readonly instance = 0,
    private readonly clock: () => number = Date.now,
  ) {
    if (!Number.isInteger(instance) || instance < 0 || instance >= 1 << INSTANCE_BITS) {
      throw new Error(`instance must be in [0, ${(1 << INSTANCE_BITS) - 1}]`);
    }
  }

  next(): number {
    const now = Math.max(this.clock() - EPOCH_MS, 0);
    if (now > this.lastMs) {
      this.lastMs = now;
      this.counter = 0;
    } else if (++this.counter >= 1 << COUNTER_BITS) {
      this.lastMs += 1;
      this.counter = 0;
    }
    return (this.lastMs * (1 << INSTANCE_BITS) + this.instance) * (1 << COUNTER_BITS) + this.counter;
  }
}
