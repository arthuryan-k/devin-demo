import formbody from "@fastify/formbody";
import Fastify, { type FastifyInstance } from "fastify";
import { afterEach, describe, expect, it } from "vitest";

import { buildApp } from "../src/app.js";
import { ALICE, harness } from "./fakes.js";

/** A stand-in for auth-service's public routes (contract in services/auth-service/README.md). */
async function fakeAuthService(): Promise<{ server: FastifyInstance; url: string; seen: string[] }> {
  const seen: string[] = [];
  const server = Fastify();
  await server.register(formbody);
  server.addHook("onRequest", async (req) => {
    seen.push(`${req.method} ${req.url}`);
  });
  server.post("/auth/login", async (req, reply) => {
    const body = req.body as { username: string; password: string };
    if (body.password !== "pw") {
      return reply.status(401).send({ error: "invalid_credentials", message: "wrong" });
    }
    reply.header("Set-Cookie", "ob_session=jwt-alice; Path=/; HttpOnly; SameSite=Lax");
    return { token: "jwt-alice", tokenType: "Bearer", account: { participantId: ALICE.participantId } };
  });
  server.post("/participants/register", async (req, reply) =>
    reply.status(201).send({ participantId: 2001, label: `${(req.body as { name: string }).name}-2001`, apiKey: "ob_x" }),
  );
  server.get("/auth/me", async (req, reply) =>
    req.headers.cookie?.includes("ob_session=jwt-alice") ? { user: { username: "alice" } } : reply.status(401).send({ error: "unauthorized" }),
  );
  server.get("/internal/api-keys/verify", async () => ({ participantId: 1 }));
  const url = await server.listen({ port: 0, host: "127.0.0.1" });
  return { server, url, seen };
}

let cleanup: (() => Promise<unknown>)[] = [];
afterEach(async () => {
  for (const c of cleanup.reverse()) await c();
  cleanup = [];
});

describe("edge routing to auth-service", () => {
  it("proxies login (with Set-Cookie) and the session cookie then authenticates orders", async () => {
    const auth = await fakeAuthService();
    const h = await harness();
    const app = await buildApp({ orders: h.orders, auth: h.auth, symbols: h.symbols, authServiceUrl: auth.url });
    cleanup.push(() => auth.server.close(), () => app.close(), () => h.app.close());

    const bad = await app.inject({ method: "POST", url: "/api/auth/login", payload: { username: "alice", password: "nope" } });
    expect(bad.statusCode).toBe(401);
    const login = await app.inject({ method: "POST", url: "/api/auth/login", payload: { username: "alice", password: "pw" } });
    expect(login.statusCode).toBe(200);
    expect(login.headers["set-cookie"]).toContain("ob_session=jwt-alice");
    expect(login.json().token).toBe("jwt-alice");

    const me = await app.inject({ method: "GET", url: "/auth/me", headers: { cookie: "ob_session=jwt-alice" } });
    expect(me.json().user.username).toBe("alice");

    const order = await app.inject({
      method: "POST",
      url: "/api/orders",
      headers: { cookie: "theme=dark; ob_session=jwt-alice" },
      payload: { symbol: "ACME", side: "BUY", price: "1.00", qty: 1 },
    });
    expect(order.statusCode).toBe(200);
    expect(JSON.parse(h.bus.records("commands.ACME")[0]!.value).participantId).toBe(ALICE.participantId);

    const reg = await app.inject({ method: "POST", url: "/participants/register", payload: "name=mm", headers: { "content-type": "application/x-www-form-urlencoded" } });
    expect(reg.statusCode).toBe(201);
    expect(reg.json().label).toBe("mm-2001");

    const internal = await app.inject({ method: "GET", url: "/internal/api-keys/verify" });
    expect(internal.statusCode).toBe(404);
    expect(auth.seen).not.toContain("GET /internal/api-keys/verify");
    expect(auth.seen).toContain("POST /auth/login");
  });
});

describe("upstream proxies", () => {
  it("rewrites a prefix onto the upstream path and passes the principal", async () => {
    const seen: { url: string; pid?: string }[] = [];
    const upstream = Fastify();
    upstream.get("/*", async (req) => {
      seen.push({ url: req.url, pid: req.headers["x-participant-id"] as string });
      return { ok: true };
    });
    const url = await upstream.listen({ port: 0, host: "127.0.0.1" });
    const h = await harness();
    const app = await buildApp({ orders: h.orders, auth: h.auth, symbols: h.symbols, upstreams: { "/api/reference": `${url}/`, "/api/portfolio": `${url}/v1/accounts` } });
    cleanup.push(() => upstream.close(), () => app.close(), () => h.app.close());

    expect((await app.inject({ method: "GET", url: "/api/reference/symbols" })).statusCode).toBe(401);
    expect((await app.inject({ method: "GET", url: "/api/reference/symbols", headers: { "x-api-key": "key-alice" } })).json()).toEqual({ ok: true });
    await app.inject({ method: "GET", url: "/api/portfolio/1001", headers: { "x-api-key": "key-alice" } });
    expect(seen).toEqual([
      { url: "/symbols", pid: String(ALICE.participantId) },
      { url: "/v1/accounts/1001", pid: String(ALICE.participantId) },
    ]);
  });
});

describe("portfolio BFF route", () => {
  it("returns the caller's own account", async () => {
    const portfolio = Fastify();
    portfolio.get("/v1/accounts/:id", async (req) => ({ accountId: Number((req.params as { id: string }).id) }));
    const url = await portfolio.listen({ port: 0, host: "127.0.0.1" });
    const h = await harness();
    const app = await buildApp({ orders: h.orders, auth: h.auth, symbols: h.symbols, portfolioUrl: url });
    cleanup.push(() => portfolio.close(), () => app.close(), () => h.app.close());
    expect((await app.inject({ method: "GET", url: "/api/portfolio" })).statusCode).toBe(401);
    expect((await app.inject({ method: "GET", url: "/api/portfolio", headers: { "x-api-key": "key-alice" } })).json()).toEqual({ accountId: ALICE.participantId });
  });
});

describe("history BFF routes", () => {
  it("scopes history queries to the caller", async () => {
    const seen: string[] = [];
    const history = Fastify();
    history.get("/v1/:kind", async (req) => {
      seen.push(req.url);
      return { [(req.params as { kind: string }).kind]: [] };
    });
    const url = await history.listen({ port: 0, host: "127.0.0.1" });
    const h = await harness();
    const app = await buildApp({ orders: h.orders, auth: h.auth, symbols: h.symbols, historyUrl: url });
    cleanup.push(() => history.close(), () => app.close(), () => h.app.close());
    expect((await app.inject({ method: "GET", url: "/api/history/orders" })).statusCode).toBe(401);
    const res = await app.inject({ method: "GET", url: "/api/history/trades?symbol=ACME&limit=5&participantId=999", headers: { "x-api-key": "key-alice" } });
    expect(res.json()).toEqual({ trades: [] });
    await app.inject({ method: "GET", url: "/api/history/orders", headers: { "x-api-key": "key-alice" } });
    expect(seen).toEqual([
      `/v1/trades?symbol=ACME&limit=5&participantId=${ALICE.participantId}`,
      `/v1/orders?participantId=${ALICE.participantId}`,
    ]);
  });
});
