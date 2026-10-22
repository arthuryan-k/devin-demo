import { randomBytes } from "node:crypto";

import pg from "pg";
import { inject } from "vitest";

import { buildApp } from "../src/app.js";
import { type Config, loadConfig } from "../src/config.js";
import { Passwords } from "../src/passwords.js";
import { Store } from "../src/store.js";
import { TokenIssuer } from "../src/tokens.js";

export async function startApp(overrides: Partial<Config> = {}, tokens?: TokenIssuer) {
  const config = { ...loadConfig({ DATABASE_URL: inject("databaseUrl"), BCRYPT_COST: "4" }), ...overrides };
  const pool = new pg.Pool({ connectionString: config.databaseUrl, max: 4 });
  const issuer = tokens ?? (await TokenIssuer.create(config.jwt));
  const app = await buildApp({ config, store: new Store(pool), tokens: issuer, passwords: new Passwords(4) });
  return {
    app,
    pool,
    tokens: issuer,
    config,
    async close() {
      await app.close();
      await pool.end();
    },
  };
}

export function uniqueName(prefix = "user"): string {
  return `${prefix}_${randomBytes(4).toString("hex")}`;
}
