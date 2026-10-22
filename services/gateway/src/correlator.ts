import type { EventRecord } from "./contracts.js";

/** Request/response over the async bus: each published command waits for the event record with its correlationId. */
export class Correlator {
  private readonly pending = new Map<
    string,
    { resolve: (r: EventRecord) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }
  >();

  constructor(private readonly timeoutMs: number) {}

  /** Registers interest before publishing, so a fast response can't be missed. */
  expect(correlationId: string): Promise<EventRecord> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(correlationId);
        reject(new CorrelationTimeout(correlationId));
      }, this.timeoutMs);
      this.pending.set(correlationId, { resolve, reject, timer });
    });
  }

  cancel(correlationId: string, error: Error): void {
    const p = this.pending.get(correlationId);
    if (p) {
      clearTimeout(p.timer);
      this.pending.delete(correlationId);
      p.reject(error);
    }
  }

  /** Completes the waiting caller, if any (records for other gateways' or replayed commands are ignored). */
  complete(record: EventRecord): boolean {
    if (!record.correlationId) {
      return false;
    }
    const p = this.pending.get(record.correlationId);
    if (!p) {
      return false;
    }
    clearTimeout(p.timer);
    this.pending.delete(record.correlationId);
    p.resolve(record);
    return true;
  }

  size(): number {
    return this.pending.size;
  }
}

export class CorrelationTimeout extends Error {
  constructor(readonly correlationId: string) {
    super(`no engine response for ${correlationId}`);
  }
}
