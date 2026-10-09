# devin-demo

A price-time priority limit order book in Java 17, with a market simulator that trades against it.

**[Live demo](https://arthuryan-k.github.io/devin-demo/)**

## Order book

A single-instrument, in-memory matching engine (`orderbook.MatchingEngine`, `orderbook.OrderBook`). It is
deterministic and single-threaded, uses integer prices (ticks) and quantities, and never uses floating point.

- **Commands:** `Place`, `Cancel` and `Amend` all go through `MatchingEngine.process(Command)` and produce events:
  `OrderPlaced`, `TradeExecuted`, `OrderCancelled`, `OrderAmended`, `OrderRejected`.
- **Matching:** price-time priority, with FIFO order taken from an engine-assigned `seqNum`. Trades execute at the
  resting (maker) order's price, and orders sweep across multiple price levels.
- **Order types:** `LIMIT` and `MARKET`.
- **Time in force:**
  - `GTC`: the unfilled remainder rests in the book.
  - `IOC`: the unfilled remainder is cancelled.
  - `FOK`: all-or-nothing, checked first with a dry run, so a rejected order leaves the book untouched.
- **Amend:** a quantity decrease at the same price keeps the order's queue position. A price change or quantity
  increase sends the order to the back of the queue, and it can cross the book like a new order.
- **Self-trade prevention:** cancel-taker. An incoming order that would match the same participant's resting order is
  cancelled, and the resting order is left alone.
- **Validation:** invalid input is returned as `OrderRejected` events and never thrown. This covers non-positive
  price or quantity, duplicate order IDs, and unknown order IDs.
- **Journal and replay:** `orderbook.journal.Journal` writes every command as JSON Lines before processing it.
  `Journal.recover()` replays the journal to rebuild an identical book and counters, and tolerates a torn last line.

The book stores each side as a `TreeMap` of price levels with a FIFO queue per level, plus a hash index for O(1) cancels.

## Market simulator

`orderbook.sim.Simulator` wraps the engine and sends it ordinary `Command`s, so the engine core is unaware of it.

- **Order flow:** arrivals follow a Poisson process (exponential inter-arrival times) at an adjustable rate. A
  single-threaded executor serializes simulated and user commands. Runs are reproducible from a seed.
- **Reference price:** a hidden fair value follows a random walk with rare jumps. Each jump starts a volatility
  shock that fades out over 12 seconds. During a shock, prices move more, market makers quote wider, and
  participants are more likely to leave.
- **Participants:** 2–8 are active at once. They join and leave over time, with more leaving after shocks. A
  participant cancels its resting orders before leaving. Each one is assigned a persona and a hidden
  `riskTolerance` between 0 and 1 when it joins.

| Persona | Share | Strategy |
|---|---|---|
| Market maker | ~58% | Posts small GTC quotes on both sides near the reference price. It re-quotes as the price drifts and refills an empty side of the book. Its spread and readiness to leave depend on its risk tolerance. It tracks the net of its own fills and shrinks its quotes on the side that would add to its inventory. |
| Aggressive taker | ~19% | Sends market and IOC orders, sometimes sweeping several levels. It follows recent drift, buying 55% of the time after the price rises and 45% after it falls. |
| Maintenance trader | ~19% | Keeps a few resting orders and cancels, resizes or reprices them. |
| Whale | ~4% | Occasionally sends a large market sweep, which triggers a volatility shock. |

**Your account (`YOU`).** You trade against the simulated participants with a cash and share balance. Every order is
checked against that balance before it reaches the engine.
- Buys reserve cash and sells reserve shares until the order fills or is cancelled.
- Cash and shares can never go negative, so short selling is not allowed.
- Fills settle at the trade price, and any unused reservation is refunded.
- Session gain/loss is tracked at average cost: realized on sells, unrealized on held shares marked at the last trade.

The simulation starts stopped; press **Simulate** to begin.

## Build and run

```
mvn test                              # engine, simulator, account, server and property tests
mvn compile exec:java                 # local server at http://localhost:8080/ (SIM_SEED=42 for a fixed seed)
```
