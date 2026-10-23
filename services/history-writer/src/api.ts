import Fastify, { type FastifyInstance } from "fastify";
import type { Pool } from "pg";

/** One orders_history row. Prices are integer ticks, as on the bus. */
export interface OrderRow {
  symbol: string;
  seq: number;
  eventIndex: number;
  orderId: number;
  participantId: number | null;
  eventType: "PLACED" | "AMENDED" | "CANCELLED" | "REJECTED";
  side: "BUY" | "SELL" | null;
  orderType: string | null;
  timeInForce: string | null;
  priceTicks: number | null;
  qty: number | null;
  reason: string | null;
  details: Record<string, unknown>;
  eventTime: string;
}

/** One trades_history row; aggressorSide is the taker's side. */
export interface TradeRow {
  symbol: string;
  seq: number;
  eventIndex: number;
  makerOrderId: number;
  takerOrderId: number;
  makerParticipantId: number | null;
  takerParticipantId: number | null;
  aggressorSide: "BUY" | "SELL" | null;
  priceTicks: number;
  qty: number;
  eventTime: string;
}

interface HistoryQuery {
  participantId: number;
  symbol?: string;
  limit: number;
}

export const MAX_LIMIT = 1000;

const querySchema = {
  type: "object",
  required: ["participantId"],
  properties: {
    participantId: { type: "integer", minimum: 1 },
    symbol: { type: "string", pattern: "^[A-Z][A-Z0-9]{0,9}$" },
    limit: { type: "integer", minimum: 1, maximum: MAX_LIMIT, default: 100 },
  },
  additionalProperties: false,
} as const;

const num = (v: unknown) => (v === null || v === undefined ? null : Number(v));

function toOrder(r: Record<string, unknown>): OrderRow {
  return {
    symbol: String(r.symbol),
    seq: Number(r.seq),
    eventIndex: Number(r.event_index),
    orderId: Number(r.order_id),
    participantId: num(r.participant_id),
    eventType: r.event_type as OrderRow["eventType"],
    side: (r.side ?? null) as OrderRow["side"],
    orderType: (r.order_type ?? null) as string | null,
    timeInForce: (r.time_in_force ?? null) as string | null,
    priceTicks: num(r.price_ticks),
    qty: num(r.qty),
    reason: (r.reason ?? null) as string | null,
    details: (r.details ?? {}) as Record<string, unknown>,
    eventTime: (r.event_time as Date).toISOString(),
  };
}

function toTrade(r: Record<string, unknown>): TradeRow {
  return {
    symbol: String(r.symbol),
    seq: Number(r.seq),
    eventIndex: Number(r.event_index),
    makerOrderId: Number(r.maker_order_id),
    takerOrderId: Number(r.taker_order_id),
    makerParticipantId: num(r.maker_participant_id),
    takerParticipantId: num(r.taker_participant_id),
    aggressorSide: (r.aggressor_side ?? null) as TradeRow["aggressorSide"],
    priceTicks: Number(r.price_ticks),
    qty: Number(r.qty),
    eventTime: (r.event_time as Date).toISOString(),
  };
}

/**
 * Read side of the history tables, newest first. participantId is required: callers (the gateway) scope every
 * query to the authenticated participant, so there is no unscoped listing.
 */
export async function buildApi(pool: Pool): Promise<FastifyInstance> {
  const app = Fastify({ logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false });

  app.setErrorHandler((err: Error & { statusCode?: number }, _req, reply) => {
    if (err.statusCode && err.statusCode < 500) {
      return reply.status(err.statusCode).send({ error: "bad_request", message: err.message });
    }
    app.log.error(err);
    return reply.status(500).send({ error: "internal", message: "internal error" });
  });

  app.get("/healthz", async () => ({ status: "ok" }));

  app.get<{ Querystring: HistoryQuery }>("/v1/orders", { schema: { querystring: querySchema } }, async (req) => {
    const { participantId, symbol, limit } = req.query;
    const res = await pool.query(
      `SELECT symbol, seq, event_index, order_id, participant_id, event_type, side, order_type, time_in_force,
              price_ticks, qty, reason, details, event_time
       FROM orders_history
       WHERE participant_id = $1 AND ($2::text IS NULL OR symbol = $2)
       ORDER BY event_time DESC, seq DESC, event_index DESC
       LIMIT $3`,
      [participantId, symbol ?? null, limit],
    );
    return { orders: res.rows.map(toOrder) };
  });

  app.get<{ Params: { orderId: string }; Querystring: { participantId?: string; symbol?: string } }>(
    "/v1/orders/:orderId",
    async (req, reply) => {
      const orderId = Number(req.params.orderId);
      const participantId = Number(req.query.participantId);
      if (!Number.isSafeInteger(orderId) || orderId <= 0) {
        return reply.status(400).send({ error: "invalid order id" });
      }
      if (!participantId) {
        throw new Error("participantId is required");
      }
      try {
        const res = await pool.query(
          `SELECT symbol, seq, event_index, order_id, participant_id, event_type, side, order_type, time_in_force,
                  price_ticks, qty, reason, details, event_time
           FROM orders_history
           WHERE order_id = $1 AND participant_id = $2 AND ($3::text IS NULL OR symbol = $3)
           ORDER BY seq, event_index`,
          [orderId, participantId, req.query.symbol ?? null],
        );
        if (res.rows.length === 0) {
          return reply.status(404).send({ message: `order ${orderId} not found` });
        }
        return { orderId, events: res.rows.map(toOrder) };
      } catch (e) {
        console.error("order lookup failed", e);
        return reply.status(503).send({ error: "unavailable", detail: String(e) });
      }
    },
  );

  app.get<{ Querystring: HistoryQuery }>("/v1/trades", { schema: { querystring: querySchema } }, async (req) => {
    const { participantId, symbol, limit } = req.query;
    const res = await pool.query(
      `SELECT symbol, seq, event_index, maker_order_id, taker_order_id, maker_participant_id, taker_participant_id,
              aggressor_side, price_ticks, qty, event_time
       FROM trades_history
       WHERE (maker_participant_id = $1 OR taker_participant_id = $1) AND ($2::text IS NULL OR symbol = $2)
       ORDER BY event_time DESC, seq DESC, event_index DESC
       LIMIT $3`,
      [participantId, symbol ?? null, limit],
    );
    return { trades: res.rows.map(toTrade) };
  });

  return app;
}
