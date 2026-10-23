import pg from "pg";
import type { FastifyInstance } from "fastify";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { inject } from "vitest";

import { buildApp } from "../src/app.js";

let pool: pg.Pool;
let app: FastifyInstance;

beforeAll(async () => {
  pool = new pg.Pool({ connectionString: inject("databaseUrl"), max: 2 });
  app = await buildApp(pool);
});
afterAll(async () => {
  await app.close();
  await pool.end();
});

describe("reference data", () => {
  it("lists the seeded tickers with their trading parameters, in display order", async () => {
    const res = await app.inject({ method: "GET", url: "/symbols" });
    expect(res.statusCode).toBe(200);
    expect(res.headers["cache-control"]).toBe("public, max-age=60");
    const { symbols } = res.json();
    expect(symbols.map((s: { symbol: string }) => s.symbol)).toEqual(["ACME", "GLOBX", "INITD", "NVTA"]);
    expect(symbols[0]).toEqual({
      symbol: "ACME",
      name: "Acme Corporation",
      currency: "USD",
      tickSize: "0.01",
      lotSize: 1,
      initialCashGrant: "2500.00",
      active: true,
    });
  });

  it("serves a single symbol, case-insensitively, and 404s unknown ones", async () => {
    const res = await app.inject({ method: "GET", url: "/symbols/globx" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ symbol: "GLOBX", tickSize: "0.01", lotSize: 1 });
    const missing = await app.inject({ method: "GET", url: "/symbols/NOPE" });
    expect(missing.statusCode).toBe(404);
    expect(missing.json().error).toBe("not_found");
  });

  it("reflects reference_data changes and hides inactive symbols unless asked", async () => {
    await pool.query(
      `INSERT INTO reference_data (symbol, name, tick_size, lot_size, initial_cash_grant, active, display_order)
       VALUES ('ZZTOP', 'Delisted Co', 0.05, 100, 0, false, 99)`,
    );
    try {
      const active = (await app.inject({ method: "GET", url: "/symbols" })).json().symbols;
      expect(active.some((s: { symbol: string }) => s.symbol === "ZZTOP")).toBe(false);
      const all = (await app.inject({ method: "GET", url: "/symbols?includeInactive=true" })).json().symbols;
      expect(all.at(-1)).toMatchObject({ symbol: "ZZTOP", tickSize: "0.05", lotSize: 100, active: false });
      expect((await app.inject({ method: "GET", url: "/symbols/ZZTOP" })).json().active).toBe(false);
    } finally {
      await pool.query("DELETE FROM reference_data WHERE symbol = 'ZZTOP'");
    }
  });
});
