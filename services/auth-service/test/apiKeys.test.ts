import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { hashApiKey } from "../src/apiKeys.js";
import { startApp, uniqueName } from "./harness.js";

let h: Awaited<ReturnType<typeof startApp>>;
beforeAll(async () => {
  h = await startApp();
});
afterAll(() => h.close());

async function session() {
  const res = await h.app.inject({ method: "POST", url: "/auth/signup", payload: { username: uniqueName(), password: "pass-word-1" } });
  const body = res.json();
  return { ...body, auth: { authorization: `Bearer ${body.token}` } };
}

const verify = (key?: string) =>
  h.app.inject({ method: "GET", url: "/internal/api-keys/verify", headers: key === undefined ? {} : { "x-api-key": key } });

describe("API keys", () => {
  it("issues a key once, stores only its hash, and resolves it to the participant", async () => {
    const s = await session();
    const res = await h.app.inject({ method: "POST", url: "/api-keys", headers: s.auth, payload: { label: "bot" } });
    expect(res.statusCode).toBe(201);
    const issued = res.json();
    expect(issued.apiKey).toMatch(/^ob_[0-9a-f]{48}$/);
    expect(issued).toMatchObject({ participantId: s.account.participantId, label: "bot", prefix: issued.apiKey.slice(0, 11) });

    const stored = await h.pool.query("SELECT key_hash, key_prefix FROM api_keys WHERE id = $1", [issued.id]);
    expect(Buffer.compare(stored.rows[0].key_hash, hashApiKey(issued.apiKey))).toBe(0);
    const leaked = await h.pool.query("SELECT count(*)::int AS n FROM api_keys WHERE key_prefix = $1 OR label = $1", [issued.apiKey]);
    expect(leaked.rows[0].n).toBe(0);

    const lookup = await verify(issued.apiKey);
    expect(lookup.statusCode).toBe(200);
    expect(lookup.json()).toEqual({
      keyId: issued.id, participantId: s.account.participantId, accountId: s.account.id, userId: s.user.id,
      username: s.user.username, label: s.account.label, kind: "human",
    });

    const list = (await h.app.inject({ method: "GET", url: "/api-keys", headers: s.auth })).json();
    expect(list.keys).toHaveLength(1);
    expect(list.keys[0].lastUsedAt).not.toBeNull();
    expect(JSON.stringify(list)).not.toContain(issued.apiKey);
  });

  it("returns 401 for missing, unknown and revoked keys", async () => {
    expect((await verify()).statusCode).toBe(401);
    expect((await verify("ob_" + "0".repeat(48))).statusCode).toBe(401);

    const s = await session();
    const issued = (await h.app.inject({ method: "POST", url: "/api-keys", headers: s.auth })).json();
    expect((await verify(issued.apiKey)).statusCode).toBe(200);
    expect((await h.app.inject({ method: "DELETE", url: `/api-keys/${issued.id}`, headers: s.auth })).statusCode).toBe(204);
    expect((await verify(issued.apiKey)).statusCode).toBe(401);
    expect((await h.app.inject({ method: "DELETE", url: `/api-keys/${issued.id}`, headers: s.auth })).statusCode).toBe(404);
  });

  it("requires a session to issue, list or revoke keys, and keys are scoped to their account", async () => {
    expect((await h.app.inject({ method: "POST", url: "/api-keys" })).statusCode).toBe(401);
    expect((await h.app.inject({ method: "GET", url: "/api-keys" })).statusCode).toBe(401);
    const alice = await session();
    const bob = await session();
    const key = (await h.app.inject({ method: "POST", url: "/api-keys", headers: alice.auth })).json();
    expect((await h.app.inject({ method: "DELETE", url: `/api-keys/${key.id}`, headers: bob.auth })).statusCode).toBe(404);
    expect((await verify(key.apiKey)).json().participantId).toBe(alice.account.participantId);
  });
});

describe("participant registration", () => {
  it("creates a simulated user, account and key per participant (form-encoded, like the old endpoint)", async () => {
    const res = await h.app.inject({
      method: "POST", url: "/participants/register", payload: "name=maker", headers: { "content-type": "application/x-www-form-urlencoded" },
    });
    expect(res.statusCode).toBe(201);
    const reg = res.json();
    expect(reg.label).toBe(`maker-${reg.participantId}`);
    expect(reg.startingCash).toBe("10000.00");
    const lookup = (await verify(reg.apiKey)).json();
    expect(lookup).toMatchObject({ participantId: reg.participantId, accountId: reg.accountId, kind: "simulated" });

    const other = (await h.app.inject({ method: "POST", url: "/participants/register", payload: { name: "maker" } })).json();
    expect(other.participantId).not.toBe(reg.participantId);
    expect(other.accountId).not.toBe(reg.accountId);
  });

  it("sanitizes names and reserves YOU/anon", async () => {
    const reg = (await h.app.inject({ method: "POST", url: "/participants/register", payload: { name: "YOU" } })).json();
    expect(reg.label).toBe(`P-${reg.participantId}`);
    const odd = (await h.app.inject({ method: "POST", url: "/participants/register", payload: { name: "a b/c!" } })).json();
    expect(odd.label).toBe(`abc-${odd.participantId}`);
  });

  it("resolves participant ids to labels for internal callers", async () => {
    const reg = (await h.app.inject({ method: "POST", url: "/participants/register", payload: {} })).json();
    const res = await h.app.inject({ method: "GET", url: `/internal/participants/${reg.participantId}` });
    expect(res.json()).toMatchObject({ participantId: reg.participantId, label: reg.label });
    expect((await h.app.inject({ method: "GET", url: "/internal/participants/999999999" })).statusCode).toBe(404);
  });
});

describe("internal token", () => {
  it("guards /internal routes when INTERNAL_TOKEN is configured", async () => {
    const guarded = await startApp({ internalToken: "s3cr3t" });
    try {
      const reg = (await guarded.app.inject({ method: "POST", url: "/participants/register", payload: {} })).json();
      const call = (headers: Record<string, string>) =>
        guarded.app.inject({ method: "GET", url: "/internal/api-keys/verify", headers: { "x-api-key": reg.apiKey, ...headers } });
      expect((await call({})).statusCode).toBe(403);
      expect((await call({ "x-internal-token": "nope" })).statusCode).toBe(403);
      expect((await call({ "x-internal-token": "s3cr3t" })).statusCode).toBe(200);
    } finally {
      await guarded.close();
    }
  });
});
