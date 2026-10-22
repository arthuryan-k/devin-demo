export interface Config {
  host: string;
  port: number;
  databaseUrl: string;
  bcryptCost: number;
  jwt: {
    issuer: string;
    audience: string;
    ttlSeconds: number;
    /** PKCS#8 PEM RSA private key. When absent an ephemeral key is generated (tokens die with the process). */
    privateKeyPem?: string;
  };
  /** When set, /internal/* routes require `X-Internal-Token: <value>`. */
  internalToken?: string;
  cookieName: string;
  cookieSecure: boolean;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const databaseUrl = env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error("DATABASE_URL is required");
  }
  return {
    host: env.HOST ?? "0.0.0.0",
    port: Number(env.PORT ?? 8081),
    databaseUrl,
    bcryptCost: Number(env.BCRYPT_COST ?? 12),
    jwt: {
      issuer: env.JWT_ISSUER ?? "devin-demo-auth",
      audience: env.JWT_AUDIENCE ?? "devin-demo",
      ttlSeconds: Number(env.JWT_TTL_SECONDS ?? 3600),
      privateKeyPem: env.JWT_PRIVATE_KEY?.replace(/\\n/g, "\n") || undefined,
    },
    internalToken: env.INTERNAL_TOKEN || undefined,
    cookieName: env.SESSION_COOKIE ?? "ob_session",
    cookieSecure: env.COOKIE_SECURE === "true",
  };
}
