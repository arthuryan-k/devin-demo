# fix-gateway

A FIX 4.4 order-entry gateway: the deliberately old-school way into the exchange. It authenticates a FIX session
with an API key, runs the same pre-trade risk check as the REST gateway, and publishes to the same
`commands.<symbol>` topics, so a FIX client and a REST client trade in the same book. Written like the in-house
gateways of the 2000s: thread per socket, raw collections, hand-rolled tag=value codec, no resend.

**Stack:** Java 17 (written to Java 5 idioms), kafka-clients, Jackson. Tests: JUnit 5.

## Session

| Message | | |
| --- | --- | --- |
| Logon `A` | in | `553` Username (logged only), `554` Password = the participant's API key. Bad key: Logout `5` |
| Heartbeat `0`, TestRequest `1`, Logout `5` | in/out | |
| NewOrderSingle `D` | in | `11` ClOrdID, `55` Symbol, `54` Side (1/2), `40` OrdType (1 market, 2 limit), `44` Price, `38` OrderQty, `59` TimeInForce (0/1 GTC, 3 IOC, 4 FOK) |
| OrderCancelRequest `F` | in | `11`, `41` OrigClOrdID |
| OrderCancelReplaceRequest `G` | in | `11`, `41`, new `44` and/or `38` |
| ExecutionReport `8` | out | `150` ExecType 0 New, F Trade, 4 Canceled, 5 Replaced, 8 Rejected; `39`, `31`/`32` on fills, `14`, `151`, `6` |
| OrderCancelReject `9`, Reject `3` | out | unknown order / malformed message |

Sequence numbers reset on every logon; a gap ends the session. Prices are decimals on the wire, ticks on the bus.

```text
8=FIX.4.4|9=..|35=A|49=ME|56=FIXGW|34=1|52=...|98=0|108=30|553=alice|554=ob_...|10=...
8=FIX.4.4|9=..|35=D|49=ME|56=FIXGW|34=2|52=...|11=c1|55=ACME|54=1|40=2|44=100.25|38=10|59=1|10=...
```

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `FIX_PORT` | `9878` | |
| `AUTH_URL`, `INTERNAL_TOKEN` | `http://auth-service:8081`, unset | key check via `GET /internal/api-keys/verify` |
| `RISK_URL` | `http://risk:8092` | `none` skips risk |
| `KAFKA_BOOTSTRAP`, `SYMBOLS` | `redpanda:9092`, all four | |
| `GATEWAY_INSTANCE` | `15` | order-id instance bits, distinct from the REST gateway's |
| `FIX_COMP_ID` | `FIXGW` | |

## Build, test

```bash
mvn -B test
docker compose up --build fix-gateway   # from the repo root
```
