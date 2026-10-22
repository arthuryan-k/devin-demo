import pg from "pg";

import { HistoryConsumer } from "./consumer.js";
import { HistoryWriter } from "./writer.js";

const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) {
  throw new Error("DATABASE_URL is required");
}
const brokers = (process.env.KAFKA_BROKERS ?? "localhost:9092").split(",").map((b) => b.trim()).filter(Boolean);
const pool = new pg.Pool({ connectionString: databaseUrl, max: Number(process.env.PG_POOL_MAX ?? 4) });
const consumer = new HistoryConsumer(new HistoryWriter(pool), {
  brokers,
  groupId: process.env.KAFKA_GROUP_ID ?? "history-writer",
  discoveryIntervalMs: Number(process.env.TOPIC_DISCOVERY_MS ?? 10_000),
});
await consumer.start();
console.log(`history-writer discovering events.<symbol> topics on ${brokers.join(",")}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, async () => {
    await consumer.stop();
    await pool.end();
    process.exit(0);
  });
}
