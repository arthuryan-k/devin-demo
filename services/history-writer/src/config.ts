export interface HistoryConfig {
  databaseUrl: string;
  brokers: string[];
  host: string;
  port: number;
  poolMax: number;
  groupId: string;
  discoveryIntervalMs: number;
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

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HistoryConfig {
  const databaseUrl = env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error("DATABASE_URL is required");
  }
  return {
    databaseUrl,
    brokers: (env.KAFKA_BROKERS ?? "localhost:9092")
      .split(",")
      .map((b) => b.trim())
      .filter(Boolean),
    host: env.HOST ?? "0.0.0.0",
    port: num(env, "PORT", 8083),
    poolMax: num(env, "PG_POOL_MAX", 4),
    groupId: env.KAFKA_GROUP_ID ?? "history-writer",
    discoveryIntervalMs: num(env, "TOPIC_DISCOVERY_MS", 10_000),
  };
}
