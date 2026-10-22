import Fastify, { type FastifyInstance } from "fastify";
import type { Pool } from "pg";

/** One listed instrument. Decimals are strings so no client parses money through a float. */
export interface Instrument {
  symbol: string;
  name: string;
  currency: string;
  tickSize: string;
  lotSize: number;
  initialCashGrant: string;
  active: boolean;
}

const SELECT = `SELECT symbol, name, currency, trim_scale(tick_size)::text AS tick_size, lot_size,
    initial_cash_grant::text AS initial_cash_grant, active FROM reference_data`;

function toInstrument(row: Record<string, unknown>): Instrument {
  return {
    symbol: String(row.symbol),
    name: String(row.name),
    currency: String(row.currency),
    tickSize: String(row.tick_size),
    lotSize: Number(row.lot_size),
    initialCashGrant: String(row.initial_cash_grant),
    active: Boolean(row.active),
  };
}

export interface Options {
  /** `Cache-Control: max-age` for symbol responses. */
  maxAgeSeconds?: number;
}

export async function buildApp(pool: Pool, options: Options = {}): Promise<FastifyInstance> {
  const app = Fastify({ logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false });
  const cacheControl = `public, max-age=${options.maxAgeSeconds ?? 60}`;

  app.setErrorHandler((err: Error & { statusCode?: number }, _req, reply) => {
    if (err.statusCode && err.statusCode < 500) {
      return reply.status(err.statusCode).send({ error: "bad_request", message: err.message });
    }
    app.log.error(err);
    return reply.status(500).send({ error: "internal", message: "internal error" });
  });

  app.get("/healthz", async () => ({ status: "ok" }));

  /** Active symbols in display order; `?includeInactive=true` adds delisted ones. */
  app.get<{ Querystring: { includeInactive?: string } }>("/symbols", async (req, reply) => {
    const all = req.query.includeInactive === "true";
    const res = await pool.query(`${SELECT} ${all ? "" : "WHERE active"} ORDER BY display_order, symbol`);
    reply.header("Cache-Control", cacheControl);
    return { symbols: res.rows.map(toInstrument) };
  });

  app.get<{ Params: { symbol: string } }>("/symbols/:symbol", async (req, reply) => {
    const res = await pool.query(`${SELECT} WHERE symbol = $1`, [req.params.symbol.toUpperCase()]);
    if (!res.rows[0]) {
      return reply.status(404).send({ error: "not_found", message: `unknown symbol ${req.params.symbol}` });
    }
    reply.header("Cache-Control", cacheControl);
    return toInstrument(res.rows[0]);
  });

  return app;
}
