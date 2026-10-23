import pg from "pg";

import { buildApi } from "./api.js";
import { loadConfig } from "./config.js";
import { HistoryConsumer } from "./consumer.js";
import { HistoryWriter } from "./writer.js";

const { databaseUrl, brokers, host, port, poolMax, groupId, discoveryIntervalMs } = loadConfig();
const pool = new pg.Pool({ connectionString: databaseUrl, max: poolMax });
const consumer = new HistoryConsumer(new HistoryWriter(pool), { brokers, groupId, discoveryIntervalMs });
const api = await buildApi(pool);
await api.listen({ host, port });
console.log(`history-writer read API listening on ${host}:${port}`);
await consumer.start();
console.log(`history-writer discovering events.<symbol> topics on ${brokers.join(",")}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, async () => {
    await api.close();
    await consumer.stop();
    await pool.end();
    process.exit(0);
  });
}
