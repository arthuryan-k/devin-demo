import type { Pool } from "pg";
import { describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";

const ROW = {
  symbol: "ACME",
  name: "Acme Corporation",
  currency: "USD",
  tick_size: "0.01",
  lot_size: 1,
  initial_cash_grant: "2500.00",
  active: true,
};

function fakePool(query: (sql: string, params?: unknown[]) => Promise<{ rows: unknown[] }>): Pool {
  return { query } as unknown as Pool;
}

describe("reference data API without a database", () => {
  it("answers health checks without touching the pool", async () => {
    const app = await buildApp(
      fakePool(() => {
        throw new Error("should not query");
      }),
    );
    const res = await app.inject({ method: "GET", url: "/healthz" });
    expect(res.json()).toEqual({ status: "ok" });
    await app.close();
  });

  it("honours a configured cache max-age on list and single-symbol responses", async () => {
    const app = await buildApp(
      fakePool(async () => ({ rows: [ROW] })),
      { maxAgeSeconds: 5 },
    );
    const list = await app.inject({ method: "GET", url: "/symbols" });
    expect(list.headers["cache-control"]).toBe("public, max-age=5");
    const one = await app.inject({ method: "GET", url: "/symbols/acme" });
    expect(one.headers["cache-control"]).toBe("public, max-age=5");
    expect(one.json().initialCashGrant).toBe("2500.00");
    await app.close();
  });

  it("filters inactive listings in SQL unless includeInactive=true", async () => {
    const seen: string[] = [];
    const app = await buildApp(
      fakePool(async (sql) => {
        seen.push(sql);
        return { rows: [] };
      }),
    );
    await app.inject({ method: "GET", url: "/symbols" });
    await app.inject({ method: "GET", url: "/symbols?includeInactive=yes" });
    await app.inject({ method: "GET", url: "/symbols?includeInactive=true" });
    expect(seen.map((s) => s.includes("WHERE active"))).toEqual([true, true, false]);
    await app.close();
  });

  it("uppercases the looked-up symbol and does not cache 404s", async () => {
    const params: unknown[] = [];
    const app = await buildApp(
      fakePool(async (_sql, p) => {
        params.push(...(p ?? []));
        return { rows: [] };
      }),
    );
    const res = await app.inject({ method: "GET", url: "/symbols/nvta" });
    expect(params).toEqual(["NVTA"]);
    expect(res.statusCode).toBe(404);
    expect(res.headers["cache-control"]).toBeUndefined();
    await app.close();
  });

  it("hides database errors behind a generic 500", async () => {
    const app = await buildApp(
      fakePool(async () => {
        throw new Error("connection terminated: password authentication failed for user postgres");
      }),
    );
    const res = await app.inject({ method: "GET", url: "/symbols" });
    expect(res.statusCode).toBe(500);
    expect(res.json()).toEqual({ error: "internal", message: "internal error" });
    await app.close();
  });
});
