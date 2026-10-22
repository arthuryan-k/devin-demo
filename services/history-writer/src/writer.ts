import type pg from "pg";

import type { HistoryRows } from "./events.js";

/** Writes one record's rows in a single transaction; rows already present (redelivery) are skipped. */
export class HistoryWriter {
  constructor(private readonly pool: pg.Pool) {}

  async write(rows: HistoryRows): Promise<{ orders: number; trades: number }> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      let orders = 0;
      for (const o of rows.orders) {
        const r = await client.query(
          `INSERT INTO orders_history (symbol, seq, event_index, order_id, participant_id, event_type, side,
                                       order_type, time_in_force, price_ticks, qty, reason, details, event_time)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
           ON CONFLICT (symbol, seq, event_index) DO NOTHING`,
          [o.symbol, o.seq, o.eventIndex, o.orderId, o.participantId, o.eventType, o.side, o.orderType,
            o.timeInForce, o.priceTicks, o.qty, o.reason, JSON.stringify(o.details), o.eventTime],
        );
        orders += r.rowCount ?? 0;
      }
      let trades = 0;
      for (const t of rows.trades) {
        // Maker participant and aggressor side fall back to what the PLACED rows recorded (same transaction, so
        // a taker placed by this very record is visible).
        const r = await client.query(
          `INSERT INTO trades_history (symbol, seq, event_index, maker_order_id, taker_order_id,
                                       maker_participant_id, taker_participant_id, aggressor_side, price_ticks, qty,
                                       event_time)
           VALUES ($1, $2, $3, $4, $5,
                   COALESCE($6::bigint, (SELECT participant_id FROM orders_history
                                         WHERE symbol = $1 AND order_id = $4 AND event_type = 'PLACED' LIMIT 1)),
                   COALESCE($7::bigint, (SELECT participant_id FROM orders_history
                                         WHERE symbol = $1 AND order_id = $5 AND event_type = 'PLACED' LIMIT 1)),
                   COALESCE($8::text, (SELECT side FROM orders_history
                                       WHERE symbol = $1 AND order_id = $5 AND event_type = 'PLACED' LIMIT 1)),
                   $9, $10, $11)
           ON CONFLICT (symbol, seq, event_index) DO NOTHING`,
          [t.symbol, t.seq, t.eventIndex, t.makerOrderId, t.takerOrderId, t.makerParticipantId,
            t.takerParticipantId, t.aggressorSide, t.priceTicks, t.qty, t.eventTime],
        );
        trades += r.rowCount ?? 0;
      }
      await client.query("COMMIT");
      return { orders, trades };
    } catch (err) {
      await client.query("ROLLBACK").catch(() => {});
      throw err;
    } finally {
      client.release();
    }
  }
}
