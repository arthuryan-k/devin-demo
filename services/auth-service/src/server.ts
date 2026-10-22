import pg from "pg";

import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { Passwords } from "./passwords.js";
import { Store } from "./store.js";
import { TokenIssuer } from "./tokens.js";

const config = loadConfig();
if (!config.jwt.privateKeyPem) {
  console.warn("JWT_PRIVATE_KEY not set: signing with an ephemeral key; tokens will not survive a restart");
}
const pool = new pg.Pool({ connectionString: config.databaseUrl, max: Number(process.env.PG_POOL_MAX ?? 10) });
const app = await buildApp({
  config,
  store: new Store(pool),
  tokens: await TokenIssuer.create(config.jwt),
  passwords: new Passwords(config.bcryptCost),
});
await app.listen({ host: config.host, port: config.port });
console.log(`auth-service listening on ${config.host}:${config.port}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, async () => {
    await app.close();
    await pool.end();
    process.exit(0);
  });
}
