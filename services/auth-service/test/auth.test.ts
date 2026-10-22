import { generateKeyPairSync } from "node:crypto";

import { createLocalJWKSet, jwtVerify } from "jose";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { TokenIssuer } from "../src/tokens.js";
import { startApp, uniqueName } from "./harness.js";

const pem = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
let h: Awaited<ReturnType<typeof startApp>>;

beforeAll(async () => {
  h = await startApp({}, await TokenIssuer.create({ issuer: "devin-demo-auth", audience: "devin-demo", ttlSeconds: 3600, privateKeyPem: pem }));
});
afterAll(() => h.close());

const signup = (username: string, password = "correct horse") =>
  h.app.inject({ method: "POST", url: "/auth/signup", payload: { username, password } });
const login = (username: string, password: string) =>
  h.app.inject({ method: "POST", url: "/auth/login", payload: { username, password } });
const me = (headers: Record<string, string> = {}) => h.app.inject({ method: "GET", url: "/auth/me", headers });

describe("signup", () => {
  it("creates a user with one account, a starting cash grant and a JWT verifiable from the JWKS", async () => {
    const username = uniqueName();
    const res = await signup(username);
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.user).toMatchObject({ username, kind: "human" });
    expect(body.account.participantId).toBeGreaterThanOrEqual(1001);
    expect(body.account.label).toBe(username);
    expect(body.account.startingCash).toBe("10000.00");
    expect(body.tokenType).toBe("Bearer");
    expect(res.headers["set-cookie"]).toMatch(/^ob_session=.+; Path=\/; HttpOnly; SameSite=Lax/);

    const jwks = (await h.app.inject({ method: "GET", url: "/.well-known/jwks.json" })).json();
    const { payload, protectedHeader } = await jwtVerify(body.token, createLocalJWKSet(jwks), {
      issuer: "devin-demo-auth",
      audience: "devin-demo",
    });
    expect(protectedHeader.alg).toBe("RS256");
    expect(payload).toMatchObject({
      sub: String(body.user.id),
      participantId: body.account.participantId,
      accountId: body.account.id,
      username,
      kind: "human",
    });

    const rows = await h.pool.query(
      "SELECT u.password_hash, a.participant_id FROM users u JOIN accounts a ON a.user_id = u.id WHERE u.username = $1",
      [username],
    );
    expect(rows.rows).toHaveLength(1);
    expect(rows.rows[0].password_hash).toMatch(/^\$2[aby]\$/);
    expect(rows.rows[0].password_hash).not.toContain("correct horse");
  });

  it("rejects a duplicate username case-insensitively with 409", async () => {
    const username = uniqueName();
    expect((await signup(username)).statusCode).toBe(201);
    const res = await signup(username.toUpperCase());
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toBe("username_taken");
  });

  it("validates username and password", async () => {
    expect((await signup("a")).statusCode).toBe(400);
    expect((await signup("you")).statusCode).toBe(400);
    expect((await signup(uniqueName(), "short")).statusCode).toBe(400);
    expect((await h.app.inject({ method: "POST", url: "/auth/signup", payload: { username: "x" } })).statusCode).toBe(400);
  });
});

describe("login", () => {
  it("issues a session for the same account as signup", async () => {
    const username = uniqueName();
    const created = (await signup(username, "s3cret-pass")).json();
    const res = await login(username, "s3cret-pass");
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.account).toEqual(created.account);
    const who = await me({ authorization: `Bearer ${body.token}` });
    expect(who.statusCode).toBe(200);
    expect(who.json().account.participantId).toBe(created.account.participantId);
  });

  it("accepts the session cookie instead of a bearer token", async () => {
    const res = await signup(uniqueName());
    const cookie = String(res.headers["set-cookie"]).split(";")[0];
    expect((await me({ cookie })).statusCode).toBe(200);
  });

  it("returns 401 for a wrong password and for an unknown user, with the same message", async () => {
    const username = uniqueName();
    await signup(username, "right-password");
    const wrong = await login(username, "wrong-password");
    const unknown = await login(uniqueName("ghost"), "whatever-pass");
    expect(wrong.statusCode).toBe(401);
    expect(unknown.statusCode).toBe(401);
    expect(wrong.json()).toEqual(unknown.json());
    expect(wrong.headers["set-cookie"]).toBeUndefined();
  });

  it("does not let simulated (password-less) participants log in", async () => {
    const reg = (await h.app.inject({ method: "POST", url: "/participants/register", payload: { name: "mm" } })).json();
    expect((await login(reg.label, "anything-at-all")).statusCode).toBe(401);
  });
});

describe("session tokens", () => {
  it("returns 401 without a token, with a malformed token, or with a foreign signature", async () => {
    expect((await me()).statusCode).toBe(401);
    expect((await me({ authorization: "Bearer not.a.jwt" })).statusCode).toBe(401);
    const forger = await TokenIssuer.create({ issuer: "devin-demo-auth", audience: "devin-demo", ttlSeconds: 3600 });
    const forged = await forger.issue({ userId: 1, accountId: 1, participantId: 1001, username: "x", kind: "human" });
    expect((await me({ authorization: `Bearer ${forged}` })).statusCode).toBe(401);
  });

  it("returns 401 for an expired token signed with the right key", async () => {
    const created = (await signup(uniqueName())).json();
    const stale = await TokenIssuer.create({ issuer: "devin-demo-auth", audience: "devin-demo", ttlSeconds: -60, privateKeyPem: pem });
    const token = await stale.issue({
      userId: created.user.id, accountId: created.account.id, participantId: created.account.participantId,
      username: created.user.username, kind: "human",
    });
    const res = await me({ authorization: `Bearer ${token}` });
    expect(res.statusCode).toBe(401);
    expect(res.json().message).toBe("invalid or expired token");
  });

  it("returns 401 for a token minted for another audience", async () => {
    const other = await TokenIssuer.create({ issuer: "devin-demo-auth", audience: "someone-else", ttlSeconds: 60, privateKeyPem: pem });
    const token = await other.issue({ userId: 1, accountId: 1, participantId: 1001, username: "x", kind: "human" });
    expect((await me({ authorization: `Bearer ${token}` })).statusCode).toBe(401);
  });
});
