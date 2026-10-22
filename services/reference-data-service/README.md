# reference-data-service

Serves the listed instruments and their trading parameters over REST, from the `reference_data` table in Postgres.

TypeScript on Node 22 with Fastify 5 and `pg`. The matching engine reads its hosted symbols from here at startup, and the
page header shows the traded instrument's name from it.

## API

| Method | Path | Description |
|---|---|---|
| `GET` | `/symbols` | Active instruments in display order. Add `?includeInactive=true` to include delisted ones. |
| `GET` | `/symbols/{symbol}` | One instrument (case-insensitive). Returns `404 {"error":"not_found"}` if the symbol is unknown. |
| `GET` | `/healthz` | `{"status":"ok"}` |

Decimals are strings, so tick size and cash grant stay exact:

```bash
curl -s localhost:8082/symbols/ACME
```
```json
{"symbol":"ACME","name":"Acme Corporation","currency":"USD","tickSize":"0.01","lotSize":1,"initialCashGrant":"2500.00","active":true}
```

The seeded listing (`db/migrations/V2__seed_reference_data.sql`) is ACME, GLOBX, INITD and NVTA, each with a 0.01 tick
size, a lot size of 1 and a 2500.00 cash grant. A new account's starting cash is the sum of the active grants, which the
auth service computes at signup.

## Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | required | e.g. `postgres://brokerage:brokerage@localhost:5432/brokerage` |
| `PORT` | `8082` | |
| `HOST` | `0.0.0.0` | |

## Build and run

```bash
npm ci
npm run typecheck && npm run build
DATABASE_URL=postgres://brokerage:brokerage@localhost:5432/brokerage npm start
npm test        # Testcontainers: Postgres 16 + Flyway applying ../../db/migrations (needs Docker)
```

In the full stack, run `docker compose up --build reference-data-service` from the repo root. The image is a multi-stage
Node 22 Alpine build that runs as a non-root user and has a health check on `/healthz`.
