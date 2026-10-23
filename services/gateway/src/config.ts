import { DEFAULT_LIMITS, type Limits } from "./admission.js";

export interface Config {
  port: number;
  host: string;
  brokers: string[];
  authUrl: string;
  jwtSecret?: string;
  jwksUrl?: string;
  jwtIssuer?: string;
  jwtAudience?: string;
  internalToken?: string;
  sessionCookie: string;
  riskUrl: string;
  referenceDataUrl?: string;
  marketDataUrl?: string;
  portfolioUrl?: string;
  historyUrl?: string;
  upstreams: Record<string, string>;
  symbols?: string[];
  instance: number;
  responseTimeoutMs: number;
  limits: Limits;
}

function num(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const v = env[name];
  if (v === undefined || v === "") {
    return fallback;
  }
  const n = Number(v);
  if (!Number.isFinite(n)) {
    throw new Error(`${name} must be a number`);
  }
  return n;
}

/** Comma-separated {@code /api/portfolio=http://portfolio:8000,...} into a prefix -> upstream map. */
function upstreams(text: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const entry of (text ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)) {
    const [prefix, url] = entry.split("=");
    if (!prefix || !url) {
      throw new Error(`bad UPSTREAMS entry ${entry}`);
    }
    out[prefix] = url;
  }
  return out;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    port: num(env, "PORT", 8080),
    host: env.HOST ?? "0.0.0.0",
    brokers: (env.KAFKA_BROKERS ?? "localhost:9092").split(",").map((s) => s.trim()),
    authUrl: env.AUTH_URL ?? "http://localhost:8081",
    jwtSecret: env.JWT_SECRET || undefined,
    jwksUrl: env.JWKS_URL || undefined,
    jwtIssuer: env.JWT_ISSUER ?? "devin-demo-auth",
    jwtAudience: env.JWT_AUDIENCE ?? "devin-demo",
    internalToken: env.INTERNAL_TOKEN || undefined,
    sessionCookie: env.SESSION_COOKIE ?? "ob_session",
    riskUrl: env.RISK_URL ?? "http://localhost:8092",
    referenceDataUrl: env.REFERENCE_DATA_URL || undefined,
    marketDataUrl: env.MARKET_DATA_URL || undefined,
    portfolioUrl: env.PORTFOLIO_URL || undefined,
    historyUrl: env.HISTORY_URL || undefined,
    upstreams: upstreams(env.UPSTREAMS),
    symbols: env.SYMBOLS ? env.SYMBOLS.split(",").map((s) => s.trim().toUpperCase()) : undefined,
    instance: num(env, "GATEWAY_INSTANCE", 0),
    responseTimeoutMs: num(env, "RESPONSE_TIMEOUT_MS", 5_000),
    limits: {
      messagesPerSec: num(env, "LIMIT_MSGS_PER_SEC", DEFAULT_LIMITS.messagesPerSec),
      burst: num(env, "LIMIT_BURST", DEFAULT_LIMITS.burst),
      maxOpenOrders: num(env, "LIMIT_MAX_OPEN_ORDERS", DEFAULT_LIMITS.maxOpenOrders),
      maxViolations: num(env, "LIMIT_MAX_VIOLATIONS", DEFAULT_LIMITS.maxViolations),
    },
  };
}
