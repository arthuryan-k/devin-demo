import { Kafka, logLevel, type Consumer, type KafkaMessage } from "kafkajs";

import { MalformedRecordError, toRows } from "./events.js";
import type { HistoryWriter } from "./writer.js";

export const EVENTS_TOPIC = /^events\.[A-Z][A-Z0-9]{0,9}$/;

export interface ConsumerOptions {
  brokers: string[];
  groupId: string;
  clientId?: string;
  /** Read each topic from the beginning when the group has no committed offset (default true). */
  fromBeginning?: boolean;
  log?: (msg: string) => void;
}

/**
 * Subscribes to every events.<symbol> topic (matched by pattern, so new listings are picked up on the next
 * metadata refresh) and writes each record before its offset is committed: at-least-once delivery, made
 * exactly-once in the tables by the (symbol, seq, event_index) key.
 */
export class HistoryConsumer {
  private readonly consumer: Consumer;
  private readonly log: (msg: string) => void;

  constructor(private readonly writer: HistoryWriter, private readonly opts: ConsumerOptions) {
    const kafka = new Kafka({
      clientId: opts.clientId ?? "history-writer",
      brokers: opts.brokers,
      logLevel: logLevel.WARN,
    });
    this.consumer = kafka.consumer({ groupId: opts.groupId, metadataMaxAge: 30_000 });
    this.log = opts.log ?? ((m) => console.log(m));
  }

  async start(): Promise<void> {
    await this.consumer.connect();
    await this.consumer.subscribe({ topics: [EVENTS_TOPIC], fromBeginning: this.opts.fromBeginning ?? true });
    await this.consumer.run({
      eachMessage: async ({ topic, partition, message }) => this.handle(topic, partition, message),
    });
  }

  async stop(): Promise<void> {
    await this.consumer.disconnect();
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
