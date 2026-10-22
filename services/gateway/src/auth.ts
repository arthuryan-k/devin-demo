import { createHash } from "node:crypto";

import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";

/** The authenticated caller. {@code participantId} is the engine identity; {@code accountId} the brokerage account. */
export interface Principal {
  participantId: number;
  accountId?: string | number;
  label: string;
  /** {@code human} (password + session) or {@code simulated} (API key only). */
  kind?: string;
}

export interface Authenticator {
  /** {@code X-Api-Key} lookup; null for an unknown key. */
  apiKey(key: string): Promise<Principal | null>;
  /** {@code Authorization: Bearer} session token; null if invalid or expired. */
  token(jwt: string): Promise<Principal | null>;
}

export interface HttpAuthOptions {
  baseUrl: string;
  /** HS256 shared secret (tests/local); otherwise RS256 against {@code jwksUrl}, default auth-service's JWKS. */
  jwtSecret?: string;
  jwksUrl?: string;
  issuer?: string;
  audience?: string;
  /** Sent as {@code X-Internal-Token} on auth-service {@code /internal/*} calls. */
  internalToken?: string;
  cacheTtlMs?: number;
  fetchImpl?: typeof fetch;
}

/**
 * Talks to auth-service ({@code services/auth-service/README.md}): session JWTs are verified locally against its
 * JWKS, API keys via {@code GET /internal/api-keys/verify}. Key lookups are cached briefly by key hash.
 */
export class HttpAuthenticator implements Authenticator {
  private readonly cache = new Map<string, { principal: Principal | null; expires: number }>();
  private readonly secret?: Uint8Array;
  private readonly jwks?: JWTVerifyGetKey;
  private readonly fetchImpl: typeof fetch;

  constructor(private readonly options: HttpAuthOptions) {
    this.fetchImpl = options.fetchImpl ?? fetch;
    if (options.jwtSecret) {
      this.secret = new TextEncoder().encode(options.jwtSecret);
    } else {
      this.jwks = createRemoteJWKSet(new URL(options.jwksUrl ?? `${options.baseUrl}/.well-known/jwks.json`));
    }
  }

  async apiKey(key: string): Promise<Principal | null> {
    const now = Date.now();
    const cacheKey = createHash("sha256").update(key).digest("hex");
    const hit = this.cache.get(cacheKey);
    if (hit && hit.expires > now) {
      return hit.principal;
    }
    const headers: Record<string, string> = { "x-api-key": key };
    if (this.options.internalToken) {
      headers["x-internal-token"] = this.options.internalToken;
    }
    const res = await this.fetchImpl(`${this.options.baseUrl}/internal/api-keys/verify`, { headers });
    let principal: Principal | null = null;
    if (res.ok) {
      principal = toPrincipal((await res.json()) as Record<string, unknown>);
    } else if (res.status !== 404 && res.status !== 401) {
      throw new Error(`auth service returned ${res.status}`);
    }
    if (this.cache.size > 10_000) {
      this.cache.clear();
    }
    this.cache.set(cacheKey, { principal, expires: now + (this.options.cacheTtlMs ?? 30_000) });
    return principal;
  }

  async token(jwt: string): Promise<Principal | null> {
    const key = this.secret ?? this.jwks;
    if (!key) {
      return this.introspect(jwt);
    }
    try {
      const { payload } = await jwtVerify(jwt, key as Uint8Array, {
        issuer: this.options.issuer,
        audience: this.options.audience,
        algorithms: this.secret ? ["HS256"] : ["RS256"],
      });
      return toPrincipal(payload as Record<string, unknown>);
    } catch {
      return null;
    }
  }

  private async introspect(jwt: string): Promise<Principal | null> {
    const res = await this.fetchImpl(`${this.options.baseUrl}/auth/me`, {
      headers: { authorization: `Bearer ${jwt}` },
    });
    if (res.status === 401 || res.status === 403) {
      return null;
    }
    if (!res.ok) {
      throw new Error(`auth service returned ${res.status}`);
    }
    return toPrincipal((await res.json()) as Record<string, unknown>);
  }
}

function toPrincipal(body: Record<string, unknown>): Principal | null {
  const raw = body.participantId ?? body.participant_id ?? body.pid;
  const participantId = Number(raw);
  if (raw === undefined || !Number.isSafeInteger(participantId) || participantId <= 0) {
    return null;
  }
  const accountId = (body.accountId ?? body.account_id) as string | number | undefined;
  const label = String(body.label ?? body.username ?? body.name ?? `P${participantId}`);
  if (body.kind !== undefined) {
    return { participantId, accountId, label, kind: String(body.kind) };
  }
  return { participantId, accountId, label };
}
