import { randomUUID } from "node:crypto";

import { Kafka, logLevel, type Consumer, type Producer, Partitioners } from "kafkajs";

import type { Bus, BusHandler } from "./bus.js";

/**
 * kafkajs over Redpanda. The producer is idempotent with acks=all, so a command is published at most once per
 * call and in order per symbol key. Each gateway instance reads events.<symbol> with its own consumer group from
 * the beginning (no committed offsets), rebuilding its order index on start.
 */
export class KafkaBus implements Bus {
  private readonly kafka: Kafka;
  private producer?: Producer;
  private readonly consumers: Consumer[] = [];

  constructor(brokers: string[], private readonly clientId = "gateway") {
    this.kafka = new Kafka({ clientId, brokers, logLevel: logLevel.WARN });
  }

  async connect(topics: string[] = []): Promise<void> {
    if (topics.length > 0) {
      const admin = this.kafka.admin();
      await admin.connect();
      try {
        // The matching engine owns its topics (and compose disables auto-create); only fill in missing ones.
        const existing = new Set(await admin.listTopics());
        const missing = topics.filter((t) => !existing.has(t));
        if (missing.length > 0) {
          await admin.createTopics({ topics: missing.map((topic) => ({ topic, numPartitions: 1 })), waitForLeaders: true });
        }
      } finally {
        await admin.disconnect();
      }
    }
    this.producer = this.kafka.producer({
      idempotent: true,
      maxInFlightRequests: 1,
      createPartitioner: Partitioners.DefaultPartitioner,
    });
    await this.producer.connect();
  }

  async publish(topic: string, key: string, value: string): Promise<void> {
    if (!this.producer) {
      throw new Error("bus not connected");
    }
    await this.producer.send({ topic, acks: -1, messages: [{ key, value }] });
  }

  async subscribe(topics: string[], handler: BusHandler): Promise<void> {
    const consumer = this.kafka.consumer({ groupId: `${this.clientId}-${randomUUID()}` });
    await consumer.connect();
    await consumer.subscribe({ topics, fromBeginning: true });
    await consumer.run({
      autoCommit: false,
      eachMessage: async ({ topic, message }) => {
        await handler({ topic, key: message.key?.toString() ?? null, value: message.value?.toString() ?? "" });
      },
    });
    this.consumers.push(consumer);
  }

  async close(): Promise<void> {
    await Promise.allSettled([...this.consumers.map((c) => c.disconnect()), this.producer?.disconnect()]);
  }
}
