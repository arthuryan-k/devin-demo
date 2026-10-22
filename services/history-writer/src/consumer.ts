import { Kafka, logLevel, type Admin, type Consumer, type KafkaMessage } from "kafkajs";

import { MalformedRecordError, toRows } from "./events.js";
import type { HistoryWriter } from "./writer.js";

export const EVENTS_TOPIC = /^events\.[A-Z][A-Z0-9]{0,9}$/;

export interface ConsumerOptions {
  brokers: string[];
  groupId: string;
  clientId?: string;
  /** Read each topic from the beginning when the group has no committed offset (default true). */
  fromBeginning?: boolean;
  /** How often to look for newly listed events.<symbol> topics (default 10 s). */
  discoveryIntervalMs?: number;
  log?: (msg: string) => void;
}

/**
 * Consumes every events.<symbol> topic and writes each record before its offset is committed: at-least-once
 * delivery, made exactly-once in the tables by the (symbol, seq, event_index) key.
 *
 * KafkaJS resolves a regex subscription once, at subscribe time, and the matching engine creates its topics on its
 * own startup. So the topic set is discovered here: listed on start, re-listed every discoveryIntervalMs, and the
 * consumer is re-subscribed (same group, committed offsets kept) whenever a new listing appears.
 */
export class HistoryConsumer {
  private readonly kafka: Kafka;
  private readonly admin: Admin;
  private readonly log: (msg: string) => void;
  private consumer: Consumer | null = null;
  private topics: string[] = [];
  private timer: NodeJS.Timeout | null = null;
  private refreshing: Promise<void> = Promise.resolve();
  private stopped = false;

  constructor(private readonly writer: HistoryWriter, private readonly opts: ConsumerOptions) {
    this.kafka = new Kafka({
      clientId: opts.clientId ?? "history-writer",
      brokers: opts.brokers,
      logLevel: logLevel.WARN,
    });
    this.admin = this.kafka.admin();
    this.log = opts.log ?? ((m) => console.log(m));
  }

  /** The events topics currently consumed. */
  get subscribed(): readonly string[] {
    return this.topics;
  }

  async start(): Promise<void> {
    await this.admin.connect();
    await this.refresh();
    this.timer = setInterval(() => {
      this.refreshing = this.refreshing
        .then(() => this.refresh())
        .catch((err) => this.log(`topic discovery failed: ${(err as Error).message}`));
    }, this.opts.discoveryIntervalMs ?? 10_000);
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    await this.refreshing;
    await this.consumer?.disconnect();
    await this.admin.disconnect();
  }

  private async refresh(): Promise<void> {
    if (this.stopped) return;
    const topics = (await this.admin.listTopics()).filter((t) => EVENTS_TOPIC.test(t)).sort();
    if (topics.length === 0 || topics.join() === this.topics.join()) return;
    await this.consumer?.disconnect();
    const consumer = this.kafka.consumer({ groupId: this.opts.groupId });
    await consumer.connect();
    await consumer.subscribe({ topics, fromBeginning: this.opts.fromBeginning ?? true });
    await consumer.run({
      eachMessage: async ({ topic, partition, message }) => this.handle(topic, partition, message),
    });
    this.consumer = consumer;
    this.topics = topics;
    this.log(`history-writer consuming ${topics.join(", ")}`);
  }

  /** Malformed records are logged and skipped (a poison message must not stall the partition). */
  async handle(topic: string, partition: number, message: KafkaMessage): Promise<void> {
    const symbol = topic.slice("events.".length);
    try {
      const record = JSON.parse(message.value?.toString("utf8") ?? "null");
      await this.writer.write(toRows(record, symbol, new Date(Number(message.timestamp))));
    } catch (err) {
      if (err instanceof SyntaxError || err instanceof MalformedRecordError) {
        this.log(`skipping malformed record ${topic}[${partition}]@${message.offset}: ${err.message}`);
        return;
      }
      throw err; // Database trouble: KafkaJS retries the message, then restarts the consumer.
    }
  }
}
