import pg from "pg";

import { buildApp } from "./app.js";

const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) {
  throw new Error("DATABASE_URL is required");
}
const host = process.env.HOST ?? "0.0.0.0";
const port = Number(process.env.PORT ?? 8082);
const pool = new pg.Pool({ connectionString: databaseUrl, max: Number(process.env.PG_POOL_MAX ?? 5) });
const app = await buildApp(pool, { maxAgeSeconds: Number(process.env.CACHE_MAX_AGE_SECONDS ?? 60) });
await app.listen({ host, port });
console.log(`reference-data-service listening on ${host}:${port}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, async () => {
    await app.close();
    await pool.end();
    process.exit(0);
  });
}
