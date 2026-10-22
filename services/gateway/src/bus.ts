/** The slice of the event bus the gateway needs: keyed publish and topic subscription. */
export interface BusRecord {
  topic: string;
  key: string | null;
  value: string;
}

export type BusHandler = (record: BusRecord) => void | Promise<void>;

export interface Bus {
  publish(topic: string, key: string, value: string): Promise<void>;
  /** Delivers every record on {@code topics} (from the beginning) in per-topic order. */
  subscribe(topics: string[], handler: BusHandler): Promise<void>;
  close(): Promise<void>;
}

/** In-process bus for tests and local runs: per-topic append-only logs, synchronous fan-out. */
export class InMemoryBus implements Bus {
  private readonly logs = new Map<string, BusRecord[]>();
  private readonly subscribers: { topics: Set<string>; handler: BusHandler }[] = [];
  failNextPublish: Error | null = null;

  async publish(topic: string, key: string, value: string): Promise<void> {
    if (this.failNextPublish) {
      const e = this.failNextPublish;
      this.failNextPublish = null;
      throw e;
    }
    const record = { topic, key, value };
    let log = this.logs.get(topic);
    if (!log) {
      log = [];
      this.logs.set(topic, log);
    }
    log.push(record);
    for (const s of this.subscribers) {
      if (s.topics.has(topic)) {
        await s.handler(record);
      }
    }
  }

  async subscribe(topics: string[], handler: BusHandler): Promise<void> {
    for (const topic of topics) {
      for (const record of this.logs.get(topic) ?? []) {
        await handler(record);
      }
    }
    this.subscribers.push({ topics: new Set(topics), handler });
  }

  records(topic: string): BusRecord[] {
    return [...(this.logs.get(topic) ?? [])];
  }

  async close(): Promise<void> {
    this.subscribers.length = 0;
  }
}
