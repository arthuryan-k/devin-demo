import { SignJWT } from "jose";
import { describe, expect, it } from "vitest";

import { AdmissionControl, TokenBucket } from "../src/admission.js";
import { HttpAuthenticator } from "../src/auth.js";
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
