# auth-service

Users, sessions and API keys for the exchange, persisted in Postgres (the `users`, `accounts` and `api_keys`
tables from [`db/migrations`](../../db/migrations)). Replaces the matching engine's old in-memory
`ParticipantRegistry`.

**Stack:** TypeScript on Node 22, [Fastify](https://fastify.dev/) 5, `pg`, bcrypt (`bcryptjs`) for passwords,
[`jose`](https://github.com/panva/jose) for RS256 JWTs. Tests: Vitest + Testcontainers (Postgres migrated by the
same Flyway image compose uses).

## Model

- **One user, one account.** Signup creates a `human` user (bcrypt password hash) and its account in one
  transaction. The account gets a `participantId` (the id the engine stamps on orders, from 1001) and a starting
  cash grant equal to the sum of `reference_data.initial_cash_grant` over active symbols (10,000.00 with the seed
  data).
- **Simulated participants** (`POST /participants/register`) are real users of kind `simulated`: their own user,
  account and API key, but no password, so they cannot log in, only use their key.
- **API keys** are `ob_` + 48 hex chars. Only their SHA-256 is stored; the plaintext is returned once, at issuance.

## Contract for other services

### Session tokens (JWT)

| | |
| --- | --- |
| Algorithm | `RS256`, header carries `kid` |
| Verify with | `GET /.well-known/jwks.json` (cache it; `Cache-Control: max-age=300`). No call to auth-service per request. |
| `iss` / `aud` | `devin-demo-auth` / `devin-demo` (`JWT_ISSUER`, `JWT_AUDIENCE`) |
| `sub` | user id (string) |
| Custom claims | `participantId` (number), `accountId` (number), `username`, `kind` (`human` \| `simulated`) |
| Lifetime | `exp` = `iat` + `JWT_TTL_SECONDS` (default 3600) |
| Transport | `Authorization: Bearer <jwt>` or the `ob_session` cookie (`HttpOnly; SameSite=Lax; Path=/`) set by signup/login |

### Public routes

| Route | Auth | Result |
| --- | --- | --- |
| `POST /auth/signup` `{username, password}` | none | `201` session (below); `400` invalid input, `409 username_taken` |
| `POST /auth/login` `{username, password}` | none | `200` session; `401` for unknown user or wrong password (same body) |
| `POST /auth/logout` | none | `204`, clears the cookie (tokens are stateless and stay valid until `exp`) |
| `GET /auth/me` | session | `200 {user, account}`; `401` missing/invalid/expired token |
| `POST /api-keys` `{label?}` | session | `201 {apiKey, id, prefix, label, participantId, createdAt, ...}`, the only time the key is shown |
| `GET /api-keys` | session | `200 {keys: [{id, prefix, label, createdAt, lastUsedAt, revokedAt}]}` |
| `DELETE /api-keys/{id}` | session | `204`; `404` if not an active key of this account |
| `POST /participants/register` `{name?}` (JSON or form) | none | `201 {participantId, label, apiKey, accountId, userId, startingCash}`; label `<name>-<participantId>` |
| `GET /.well-known/jwks.json`, `GET /healthz` | none | |

Session body (signup and login):

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": { "id": 7, "username": "alice", "kind": "human" },
  "account": { "id": 7, "participantId": 1007, "label": "alice", "startingCash": "10000.00" }
}
```

Errors are `{"error": "<code>", "message": "..."}`.

### Internal routes (service-to-service)

If `INTERNAL_TOKEN` is set, these require `X-Internal-Token: <value>` (`403` otherwise). Keep them off the public edge.

| Route | Result |
| --- | --- |
| `GET /internal/api-keys/verify` with header `X-Api-Key: <key>` | `200 {keyId, participantId, accountId, userId, username, label, kind}`; `401` missing, unknown or revoked key |
| `GET /internal/participants/{participantId}` | `200 {participantId, accountId, userId, username, label, kind}`; `404` |

The key travels in a header, not the path, so it never lands in access logs.

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `DATABASE_URL` | (required) | e.g. `postgres://brokerage:brokerage@postgres:5432/brokerage` |
| `PORT` | `8081` | |
| `JWT_PRIVATE_KEY` | ephemeral | PKCS#8 PEM RSA key (`\n` escapes allowed). Without it a key is generated at start-up, so tokens die with the process |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_TTL_SECONDS` | `devin-demo-auth`, `devin-demo`, `3600` | |
| `INTERNAL_TOKEN` | unset | shared secret for `/internal/*` |
| `BCRYPT_COST` | `12` | |
| `SESSION_COOKIE`, `COOKIE_SECURE` | `ob_session`, `false` | |

## Run and test

```bash
npm ci
npm test                      # needs Docker (Testcontainers)
DATABASE_URL=postgres://... npm run dev
docker compose up auth-service  # from the repo root, with Postgres and migrations
```
