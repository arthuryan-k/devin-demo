# history-writer

Consumes every `events.<symbol>` topic on the event bus and writes order lifecycle and fills to Postgres
(`orders_history`, `trades_history`) so they can be displayed later.

TypeScript on Node 22 with KafkaJS and `pg`. It is a worker with no HTTP API.

## What it writes

Each bus record (see `schemas/bus/events.schema.json`) is one sequenced command plus the events it produced. The worker
maps it to rows as follows:

| Bus event | Table | `event_type` |
|---|---|---|
| `OrderPlaced` | `orders_history` | `PLACED` |
| `OrderAmended` | `orders_history` | `AMENDED` (new price/qty, old values in `details`) |
| `OrderCancelled` | `orders_history` | `CANCELLED` (`qty` = cancelled quantity, `reason`) |
| `OrderRejected` | `orders_history` | `REJECTED` (`reason`) |
| `TradeExecuted` | `trades_history` | maker/taker order and participant, aggressor side, price, qty |

Prices are integer ticks. Each row is keyed by `(symbol, seq, event_index)`, and a record's rows are written in one
transaction with `ON CONFLICT DO NOTHING`. Redelivery after a crash or rebalance therefore never creates duplicates.
Malformed records are logged and skipped. Database errors are not committed and are retried.

Query example:

```sql
SELECT seq, event_type, order_id, side, price_ticks, qty FROM orders_history
WHERE symbol = 'ACME' AND participant_id = 1001 ORDER BY seq, event_index;
```

## Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | required | |
| `KAFKA_BROKERS` | `localhost:9092` | comma-separated |
| `KAFKA_GROUP_ID` | `history-writer` | consumer group; offsets are committed after the rows |

## Build and run

```bash
npm ci
npm run typecheck && npm run build
DATABASE_URL=postgres://brokerage:brokerage@localhost:5432/brokerage KAFKA_BROKERS=localhost:19092 npm start
npm test        # Testcontainers: Postgres + Flyway + Redpanda; publishes events and asserts rows (needs Docker)
```

In the full stack it is the `history-writer` service in the root `docker-compose.yml`.
