import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import { hashApiKey, keyPrefix, newApiKey } from "./apiKeys.js";
import type { Config } from "./config.js";
import { MAX_PASSWORD_BYTES, MIN_PASSWORD_LENGTH, Passwords } from "./passwords.js";
import { type AccountRecord, isUniqueViolation, Store } from "./store.js";
import type { SessionClaims, TokenIssuer } from "./tokens.js";

declare module "fastify" {
  interface FastifyRequest {
    session?: SessionClaims;
  }
}

export interface Deps {
  config: Config;
  store: Store;
  tokens: TokenIssuer;
  passwords: Passwords;
}

class HttpError extends Error {
  constructor(readonly statusCode: number, readonly code: string, message: string) {
    super(message);
  }
}

const unauthorized = (message = "authentication required") => new HttpError(401, "unauthorized", message);

const MAX_NAME_LENGTH = 24;

/** Same rules as the old in-process registry: letters, digits, `_` and `.`, at most 24; defaults to `P`. */
export function sanitizeParticipantName(name: unknown): string {
  const cleaned = (typeof name === "string" ? name.trim() : "").replace(/[^A-Za-z0-9_.]/g, "").slice(0, MAX_NAME_LENGTH);
  return cleaned === "" || /^(you|anon)$/i.test(cleaned) ? "P" : cleaned;
}

function accountJson(a: AccountRecord) {
  return {
    user: { id: a.userId, username: a.username, kind: a.kind },
    account: { id: a.accountId, participantId: a.participantId, label: a.label, startingCash: a.startingCash },
  };
}

function claimsOf(a: AccountRecord): SessionClaims {
  return { userId: a.userId, accountId: a.accountId, participantId: a.participantId, username: a.username, kind: a.kind };
}

function cookieValue(header: string | undefined, name: string): string | undefined {
  for (const part of (header ?? "").split(";")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq).trim() === name) {
      return decodeURIComponent(part.slice(eq + 1).trim());
    }
  }
  return undefined;
}

const credentialsSchema = {
  type: "object",
  required: ["username", "password"],
  properties: {
    username: { type: "string" },
    password: { type: "string" },
  },
} as const;

export async function buildApp(deps: Deps): Promise<FastifyInstance> {
  const { config, store, tokens, passwords } = deps;
  const app = Fastify({ logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false });

  app.addContentTypeParser("application/x-www-form-urlencoded", { parseAs: "string" }, (_req, body, done) => {
    done(null, Object.fromEntries(new URLSearchParams(body as string)));
  });

  app.setErrorHandler((err: Error & { statusCode?: number; validation?: unknown; code?: string }, _req, reply) => {
    if (err instanceof HttpError) {
      return reply.status(err.statusCode).send({ error: err.code, message: err.message });
    }
    if (err.validation) {
      return reply.status(400).send({ error: "bad_request", message: err.message });
    }
    if (err.statusCode && err.statusCode < 500) {
      return reply.status(err.statusCode).send({ error: "bad_request", message: err.message });
    }
    app.log.error(err);
    return reply.status(500).send({ error: "internal", message: "internal error" });
  });

  async function startSession(reply: FastifyReply, account: AccountRecord, status: number) {
    const token = await tokens.issue(claimsOf(account));
    const cookie = [`${config.cookieName}=${token}`, "Path=/", "HttpOnly", "SameSite=Lax",
      `Max-Age=${tokens.ttlSeconds}`, ...(config.cookieSecure ? ["Secure"] : [])].join("; ");
    reply.header("Set-Cookie", cookie);
    return reply.status(status).send({ token, tokenType: "Bearer", expiresIn: tokens.ttlSeconds, ...accountJson(account) });
  }

  /** Bearer token, else the session cookie. */
  async function requireSession(req: FastifyRequest) {
    const auth = req.headers.authorization;
    const token = auth?.startsWith("Bearer ") ? auth.slice(7).trim() : cookieValue(req.headers.cookie, config.cookieName);
    if (!token) {
      throw unauthorized();
    }
    try {
      req.session = await tokens.verify(token);
    } catch {
      throw unauthorized("invalid or expired token");
    }
  }

  async function requireInternal(req: FastifyRequest) {
    if (config.internalToken && req.headers["x-internal-token"] !== config.internalToken) {
      throw new HttpError(403, "forbidden", "internal route");
    }
  }

  app.get("/healthz", async () => ({ status: "ok" }));
  app.get("/.well-known/jwks.json", async (_req, reply) => {
    reply.header("Cache-Control", "public, max-age=300");
    return tokens.jwks;
  });

  app.post<{ Body: { username: string; password: string } }>("/auth/signup", { schema: { body: credentialsSchema } },
    async (req, reply) => {
      const username = req.body.username.trim();
      const { password } = req.body;
      if (!/^[A-Za-z0-9_.-]{3,32}$/.test(username) || /^(you|anon)$/i.test(username)) {
        throw new HttpError(400, "bad_request", "username must be 3-32 letters, digits, '_', '.' or '-'");
      }
      if (password.length < MIN_PASSWORD_LENGTH || Buffer.byteLength(password, "utf8") > MAX_PASSWORD_BYTES) {
        throw new HttpError(400, "bad_request",
          `password must be at least ${MIN_PASSWORD_LENGTH} characters and at most ${MAX_PASSWORD_BYTES} bytes`);
      }
      const passwordHash = await passwords.hash(password);
      let account: AccountRecord;
      try {
        account = await store.createAccount({ username: () => username, label: () => username, passwordHash, kind: "human" });
      } catch (err) {
        if (isUniqueViolation(err)) {
          throw new HttpError(409, "username_taken", "username is already registered");
        }
        throw err;
      }
      return startSession(reply, account, 201);
    });

  app.post<{ Body: { username: string; password: string } }>("/auth/login", { schema: { body: credentialsSchema } },
    async (req, reply) => {
      const login = await store.loginByUsername(req.body.username.trim());
      const ok = await passwords.verify(req.body.password, login?.passwordHash);
      if (!login || !ok) {
        throw unauthorized("invalid username or password");
      }
      return startSession(reply, login, 200);
    });

  app.post("/auth/logout", async (_req, reply) => {
    reply.header("Set-Cookie", `${config.cookieName}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`);
    return reply.status(204).send();
  });

  app.get("/auth/me", { preHandler: requireSession }, async (req) => {
    const account = await store.accountByUserId(req.session!.userId);
    if (!account) {
      throw unauthorized("account no longer exists");
    }
    return accountJson(account);
  });

  app.post<{ Body: { label?: string } | undefined }>("/api-keys", { preHandler: requireSession }, async (req, reply) => {
    const label = typeof req.body?.label === "string" ? req.body.label.slice(0, 64) : null;
    const key = newApiKey();
    const record = await store.insertApiKey(req.session!.accountId, hashApiKey(key), keyPrefix(key), label);
    return reply.status(201).send({ apiKey: key, participantId: req.session!.participantId, ...record });
  });

  app.get("/api-keys", { preHandler: requireSession }, async (req) => ({
    keys: await store.listApiKeys(req.session!.accountId),
  }));

  app.delete<{ Params: { id: string } }>("/api-keys/:id", { preHandler: requireSession }, async (req, reply) => {
    const id = Number(req.params.id);
    if (!Number.isSafeInteger(id) || !(await store.revokeApiKey(req.session!.accountId, id))) {
      throw new HttpError(404, "not_found", "no such active API key");
    }
    return reply.status(204).send();
  });

  /** Programmatic participants (the simulator): a simulated user + account + first API key, no password. */
  app.post<{ Body: { name?: string } | undefined }>("/participants/register", async (req, reply) => {
    const name = sanitizeParticipantName(req.body?.name);
    const key = newApiKey();
    for (let attempt = 0; ; attempt++) {
      try {
        const account = await store.transaction(async (client) => {
          const created = await store.createAccount({
            username: (pid) => `${name}-${pid}`, label: (pid) => `${name}-${pid}`, passwordHash: null, kind: "simulated",
          }, client);
          await store.insertApiKey(created.accountId, hashApiKey(key), keyPrefix(key), "registration", client);
          return created;
        });
        return reply.status(201).send({
          participantId: account.participantId, label: account.label, apiKey: key,
          accountId: account.accountId, userId: account.userId, startingCash: account.startingCash,
        });
      } catch (err) {
        if (!isUniqueViolation(err) || attempt >= 4) {
          throw err;
        }
      }
    }
  });

  app.get("/internal/api-keys/verify", { preHandler: requireInternal }, async (req) => {
    const key = req.headers["x-api-key"];
    if (typeof key !== "string" || key.trim() === "") {
      throw unauthorized("missing X-Api-Key");
    }
    const principal = await store.principalByKeyHash(hashApiKey(key));
    if (!principal) {
      throw unauthorized("unknown or revoked API key");
    }
    return { keyId: principal.keyId, ...principalJson(principal) };
  });

  app.get<{ Params: { participantId: string } }>("/internal/participants/:participantId",
    { preHandler: requireInternal }, async (req) => {
      const account = await store.accountByParticipantId(Number(req.params.participantId));
      if (!account) {
        throw new HttpError(404, "not_found", "unknown participant");
      }
      return principalJson(account);
    });

  return app;
}

function principalJson(a: AccountRecord) {
  return {
    participantId: a.participantId, accountId: a.accountId, userId: a.userId,
    username: a.username, label: a.label, kind: a.kind,
  };
}
