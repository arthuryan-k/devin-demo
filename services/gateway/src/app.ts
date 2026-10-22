import formbody from "@fastify/formbody";
import httpProxy from "@fastify/http-proxy";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";

import type { Authenticator, Principal } from "./auth.js";
import { ApiError } from "./errors.js";
import { orderIdOf, type AmendInput, type OrderService, type PlaceInput } from "./orders.js";
import type { SymbolDirectory } from "./symbols.js";

export interface AppDeps {
  orders: OrderService;
  auth: Authenticator;
  symbols: SymbolDirectory;
  /** auth-service base URL; its public routes ({@link AUTH_ROUTES}) are proxied to it. */
  authServiceUrl?: string;
  /** Session cookie set by auth-service signup/login; accepted like a bearer token. */
  sessionCookie?: string;
  /** Market-data service base URL; {@code /marketdata/*} (incl. WebSocket upgrades) is proxied to it. */
  marketDataUrl?: string;
  /** Portfolio service; {@code GET /api/portfolio} returns the caller's account from {@code /v1/accounts/{participantId}}. */
  portfolioUrl?: string;
  /** History read API; {@code GET /api/history/{orders,trades}} forwards to {@code /v1/{orders,trades}?participantId=<caller>}. */
  historyUrl?: string;
  /**
   * Authenticated forwarded routes: public prefix -> upstream URL. A path on the URL replaces the prefix
   * (no path keeps it; {@code http://reference-data:8082/} maps {@code /api/reference/x} to {@code /x}).
   */
  upstreams?: Record<string, string>;
  logger?: boolean;
}

declare module "fastify" {
  interface FastifyRequest {
    principal?: Principal;
  }
}

/**
 * auth-service routes re-exposed at the edge: public prefix -> auth-service prefix. auth-service enforces its own
 * session checks; cookies and {@code Set-Cookie} pass through. {@code /internal/*} is never exposed.
 */
export const AUTH_ROUTES: Record<string, string> = {
  "/auth": "/auth",
  "/api/auth": "/auth",
  "/api-keys": "/api-keys",
  "/participants/register": "/participants/register",
};

export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const app = Fastify({ logger: deps.logger ?? false });
  const sessionCookie = deps.sessionCookie ?? "ob_session";

  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof ApiError) {
      return reply.status(error.status).send({ error: error.message, reason: error.reason, ...error.extra });
    }
    const status = (error as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      return reply.status(status).send({ error: (error as Error).message, reason: "BAD_REQUEST" });
    }
    app.log.error(error);
    return reply.status(500).send({ error: "internal error", reason: "INTERNAL" });
  });

  app.get("/healthz", async () => ({ status: "ok" }));

  app.get("/api/symbols", async () => ({ symbols: deps.symbols.instruments() }));

  const authenticate = async (request: FastifyRequest) => {
    request.principal = await resolvePrincipal(request, deps.auth, sessionCookie);
    deps.orders.checkNotBlocked(request.principal);
  };

  // Order routes accept JSON or form bodies like the Java API; proxied routes keep their raw bodies.
  await app.register(async (scope) => {
    await scope.register(formbody);
    scope.post<{ Body: PlaceInput }>("/api/orders", { preHandler: authenticate }, async (request) =>
      deps.orders.place(request.principal!, request.body ?? {}),
    );

    scope.patch<{ Params: { id: string }; Body: AmendInput }>(
      "/api/orders/:id",
      { preHandler: authenticate },
      async (request) => deps.orders.amend(request.principal!, orderIdOf(request.params.id), request.body ?? {}),
    );

    scope.delete<{ Params: { id: string }; Querystring: { symbol?: string } }>(
      "/api/orders/:id",
      { preHandler: authenticate },
      async (request) => deps.orders.cancel(request.principal!, orderIdOf(request.params.id), request.query.symbol),
    );
  });

  app.get("/api/me", { preHandler: authenticate }, async (request) => request.principal);

  if (deps.historyUrl) {
    const historyUrl = deps.historyUrl.replace(/\/$/, "");
    for (const kind of ["orders", "trades"]) {
      app.get(`/api/history/${kind}`, { preHandler: authenticate }, async (request, reply) => {
        const query = new URLSearchParams();
        for (const [k, v] of Object.entries(request.query as Record<string, unknown>)) {
          if (k !== "participantId" && v !== undefined) {
            query.set(k, String(v));
          }
        }
        query.set("participantId", String(request.principal!.participantId));
        return forward(reply, `${historyUrl}/v1/${kind}?${query}`, "HISTORY_UNAVAILABLE", "history");
      });
    }
  }

  if (deps.portfolioUrl) {
    const portfolioUrl = deps.portfolioUrl.replace(/\/$/, "");
    app.get("/api/portfolio", { preHandler: authenticate }, async (request, reply) => {
      return forward(reply, `${portfolioUrl}/v1/accounts/${request.principal!.participantId}`, "PORTFOLIO_UNAVAILABLE", "portfolio");
    });
  }

  if (deps.authServiceUrl) {
    for (const [prefix, rewritePrefix] of Object.entries(AUTH_ROUTES)) {
      await app.register(httpProxy, { upstream: deps.authServiceUrl, prefix, rewritePrefix });
    }
  }

  for (const [prefix, target] of Object.entries(deps.upstreams ?? {})) {
    const url = new URL(target);
    const path = /^[a-z][a-z0-9+.-]*:\/\/[^/]+(\/.*)?$/i.exec(target)?.[1];
    const rewritePrefix = path === undefined ? prefix : path.replace(/\/$/, "");
    await app.register(async (scope) => {
      scope.addHook("preHandler", authenticate);
      await scope.register(httpProxy, {
        upstream: url.origin,
        prefix,
        rewritePrefix,
        replyOptions: {
          rewriteRequestHeaders: (request, headers) => ({
            ...headers,
            "x-participant-id": String((request as FastifyRequest).principal?.participantId ?? ""),
            "x-account-id": String((request as FastifyRequest).principal?.accountId ?? ""),
          }),
        },
      });
    });
  }

  if (deps.marketDataUrl) {
    for (const prefix of ["/marketdata", "/ws/marketdata"]) {
      await app.register(httpProxy, { upstream: deps.marketDataUrl, prefix, rewritePrefix: prefix, websocket: true });
    }
  }

  return app;
}

function cookieValue(header: string | undefined, name: string): string | undefined {
  for (const part of (header ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i).trim() === name) {
      return decodeURIComponent(part.slice(i + 1).trim());
    }
  }
  return undefined;
}

/** {@code Authorization: Bearer <jwt>}, {@code X-Api-Key}, or the session cookie; 401 when missing or not recognised. */
async function resolvePrincipal(request: FastifyRequest, auth: Authenticator, sessionCookie: string): Promise<Principal> {
  const header = request.headers.authorization;
  const apiKey = request.headers["x-api-key"];
  const bearer =
    typeof header === "string" && header.toLowerCase().startsWith("bearer ")
      ? header.slice(7).trim()
      : typeof apiKey === "string" && apiKey.trim()
        ? undefined
        : cookieValue(request.headers.cookie, sessionCookie);
  let principal: Principal | null;
  try {
    if (bearer) {
      principal = await auth.token(bearer);
      if (!principal) {
        throw new ApiError(401, "INVALID_TOKEN", "invalid or expired session token");
      }
      return principal;
    }
    if (typeof apiKey === "string" && apiKey.trim()) {
      principal = await auth.apiKey(apiKey.trim());
      if (!principal) {
        throw new ApiError(401, "INVALID_API_KEY", "unknown API key");
      }
      return principal;
    }
  } catch (e) {
    if (e instanceof ApiError) {
      throw e;
    }
    throw new ApiError(503, "AUTH_UNAVAILABLE", `auth service unavailable: ${(e as Error).message}`);
  }
  throw new ApiError(401, "MISSING_CREDENTIALS", "Authorization: Bearer <token> or X-Api-Key header is required");
}

/** GET {@code url} and relay its status and JSON body; 503 {@code reason} if the service is unreachable. */
async function forward(reply: FastifyReply, url: string, reason: string, service: string) {
  let res: Response;
  try {
    res = await fetch(url, { signal: AbortSignal.timeout(3_000) });
  } catch (e) {
    throw new ApiError(503, reason, `${service} service unreachable: ${(e as Error).message}`);
  }
  return reply.status(res.status).type("application/json").send(await res.text());
}
