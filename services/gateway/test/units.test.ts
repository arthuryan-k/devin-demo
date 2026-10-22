import { SignJWT } from "jose";
import { describe, expect, it } from "vitest";

import { AdmissionControl, TokenBucket } from "../src/admission.js";
import { HttpAuthenticator } from "../src/auth.js";
import { HttpRiskClient, RiskUnavailable } from "../src/risk.js";
import { TickConverter } from "../src/ticks.js";

describe("TickConverter", () => {
  const c = new TickConverter("0.01");
  it("converts exactly, without float rounding", () => {
    expect(c.toTicks("price", "100.50")).toBe(10050);
    expect(c.toTicks("price", "0.29")).toBe(29);
    expect(c.toTicks("price", 1.1)).toBe(110);
    expect(c.toTicks("price", "12")).toBe(1200);
    expect(c.toDecimal(10050)).toBe("100.50");
    expect(c.toDecimal(7)).toBe("0.07");
  });
  it("rejects off-tick, non-numeric and non-positive prices", () => {
    expect(() => c.toTicks("price", "1.005")).toThrow(/multiple of 0.01/);
    expect(() => c.toTicks("price", "1e3")).toThrow();
    expect(() => c.toTicks("price", "0")).toThrow();
    expect(() => c.toTicks("price", "-1")).toThrow();
  });
  it("supports coarser ticks", () => {
    const n = new TickConverter("0.05");
    expect(n.toTicks("price", "20.05")).toBe(401);
    expect(n.toDecimal(401)).toBe("20.05");
    expect(() => n.toTicks("price", "20.03")).toThrow();
  });
});

describe("TokenBucket / AdmissionControl", () => {
  it("refills at the configured rate up to the burst", () => {
    let now = 0;
    const b = new TokenBucket(200, 400, () => now);
    for (let i = 0; i < 400; i++) expect(b.tryAcquire()).toBe(true);
    expect(b.tryAcquire()).toBe(false);
    now += 5; // 1 token at 200/s
    expect(b.tryAcquire()).toBe(true);
    expect(b.tryAcquire()).toBe(false);
    now += 10_000;
    for (let i = 0; i < 400; i++) expect(b.tryAcquire()).toBe(true);
    expect(b.tryAcquire()).toBe(false);
  });

  it("counts violations and blocks after maxViolations", () => {
    const a = new AdmissionControl({ messagesPerSec: 200, burst: 400, maxOpenOrders: 100, maxViolations: 100 }, () => 0);
    for (let i = 0; i < 100; i++) expect(a.admit(1, true, 100)).toBe("MAX_OPEN_ORDERS");
    expect(a.violations(1)).toBe(100);
    expect(a.isBlocked(1)).toBe(true);
    expect(a.isBlocked(2)).toBe(false);
  });
});

describe("HttpAuthenticator", () => {
  const secret = "test-secret-test-secret-test-secret";
  const sign = (claims: Record<string, unknown>, exp = "5m") =>
    new SignJWT(claims).setProtectedHeader({ alg: "HS256" }).setIssuedAt().setIssuer("auth").setAudience("devin-demo").setExpirationTime(exp).sign(new TextEncoder().encode(secret));

  it("verifies JWT claims locally", async () => {
    const auth = new HttpAuthenticator({ baseUrl: "http://unused", jwtSecret: secret, issuer: "auth", audience: "devin-demo" });
    expect(await auth.token(await sign({ participantId: 42, accountId: "acc-1", username: "carol", kind: "human" }))).toEqual({ participantId: 42, accountId: "acc-1", label: "carol", kind: "human" });
    expect(await auth.token(await sign({ participantId: 42 }, "-1m"))).toBeNull();
    expect(await auth.token(await sign({ username: "no-pid" }))).toBeNull();
    const wrongAud = await new SignJWT({ participantId: 42 }).setProtectedHeader({ alg: "HS256" }).setIssuer("auth").setAudience("other").sign(new TextEncoder().encode(secret));
    expect(await auth.token(wrongAud)).toBeNull();
    const other = await new SignJWT({ participantId: 42 }).setProtectedHeader({ alg: "HS256" }).setIssuer("auth").sign(new TextEncoder().encode("x".repeat(40)));
    expect(await auth.token(other)).toBeNull();
  });

  it("looks API keys up in the auth service and caches the answer", async () => {
    const calls: string[] = [];
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      const headers = init?.headers as Record<string, string>;
      expect(headers["x-internal-token"]).toBe("shh");
      calls.push(`${url} ${headers["x-api-key"]}`);
      return headers["x-api-key"] === "good"
        ? new Response(JSON.stringify({ participantId: 7, label: "mm-1" }), { status: 200 })
        : new Response("{}", { status: 404 });
    }) as unknown as typeof fetch;
    const auth = new HttpAuthenticator({ baseUrl: "http://auth", jwtSecret: "unused", internalToken: "shh", fetchImpl });
    expect(await auth.apiKey("good")).toEqual({ participantId: 7, accountId: undefined, label: "mm-1" });
    expect(await auth.apiKey("good")).not.toBeNull();
    expect(await auth.apiKey("bad")).toBeNull();
    expect(calls).toEqual(["http://auth/internal/api-keys/verify good", "http://auth/internal/api-keys/verify bad"]);
  });
});

describe("HttpRiskClient", () => {
  const recorder = (reply: (path: string) => Response) => {
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      const path = new URL(url).pathname;
      calls.push({ path, body: JSON.parse(String(init?.body)) });
      return reply(path);
    }) as unknown as typeof fetch;
    return { calls, client: new HttpRiskClient("http://risk:8000", 1_000, fetchImpl) };
  };
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  const base = { participantId: 1001, accountId: 7, symbol: "ACME", orderId: 5 };

  it("maps place / amend / cancel / release onto the /v1/orders routes", async () => {
    const { calls, client } = recorder(() => json({ approved: true, orderId: 5, limitPrice: 1050 }));
    expect(await client.check({ ...base, action: "PLACE", side: "BUY", orderType: "MARKET", timeInForce: "IOC", price: null, qty: 2 })).toEqual({ approved: true, limitPrice: 1050 });
    await client.check({ ...base, action: "AMEND", price: 1200, qty: null });
    await client.check({ ...base, action: "CANCEL", price: null, qty: null });
    await client.release({ ...base });
    await client.release({ ...base, kind: "amend" });
    expect(calls.map((c) => c.path)).toEqual(["/v1/orders/check", "/v1/orders/check-amend", "/v1/orders/check-cancel", "/v1/orders/release", "/v1/orders/release"]);
    expect(calls[0]!.body).toEqual({ ...base, side: "BUY", orderType: "MARKET", timeInForce: "IOC", price: null, qty: 2 });
    expect(calls[1]!.body).toEqual({ ...base, newPrice: 1200, newQty: null });
    expect(calls[2]!.body).toEqual(base);
    expect([calls[3]!.body.kind, calls[4]!.body.kind]).toEqual(["place", "amend"]);
  });

  it("returns rejections and fails closed on 5xx", async () => {
    const reject = recorder(() => json({ approved: false, orderId: 5, reason: "NO_SHORTING", message: "no shares" }));
    expect(await reject.client.check({ ...base, action: "PLACE", side: "SELL", price: 100, qty: 1 })).toEqual({ approved: false, reason: "NO_SHORTING", message: "no shares" });
    const down = recorder(() => json({}, 503));
    await expect(down.client.check({ ...base, action: "PLACE", price: 100, qty: 1 })).rejects.toBeInstanceOf(RiskUnavailable);
  });
});
