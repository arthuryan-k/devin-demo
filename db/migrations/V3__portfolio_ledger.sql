-- Portfolio ledger, owned by the portfolio and risk services (services/portfolio, services/risk).
-- Accounts are keyed by the engine's participant id (accounts.participant_id): one shared cash balance per
-- account, one position row per (account, symbol). All amounts are integer engine ticks (cents), never NUMERIC,
-- so ledger arithmetic matches the engine exactly. CHECK constraints make negative cash, shares or reservations,
-- and reservations larger than holdings, impossible whatever the application does.

CREATE SCHEMA portfolio;

CREATE TABLE portfolio.accounts (
    account_id     BIGINT PRIMARY KEY,
    cash           BIGINT NOT NULL CHECK (cash >= 0),
    reserved_cash  BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cash >= 0),
    starting_cash  BIGINT NOT NULL CHECK (starting_cash >= 0),
    opened_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reserved_cash_covered CHECK (reserved_cash <= cash)
);

CREATE TABLE portfolio.positions (
    account_id       BIGINT NOT NULL REFERENCES portfolio.accounts (account_id),
    symbol           TEXT   NOT NULL,
    shares           BIGINT NOT NULL DEFAULT 0 CHECK (shares >= 0),
    reserved_shares  BIGINT NOT NULL DEFAULT 0 CHECK (reserved_shares >= 0),
    cost_basis       BIGINT NOT NULL DEFAULT 0 CHECK (cost_basis >= 0),
    realized_pnl     BIGINT NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, symbol),
    CONSTRAINT reserved_shares_covered CHECK (reserved_shares <= shares)
);

-- One row per order the risk service approved; order ids are unique per symbol.
CREATE TABLE portfolio.reservations (
    symbol           TEXT   NOT NULL,
    order_id         BIGINT NOT NULL,
    account_id       BIGINT NOT NULL REFERENCES portfolio.accounts (account_id),
    side             TEXT   NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type       TEXT   NOT NULL CHECK (order_type IN ('LIMIT', 'MARKET')),
    tif              TEXT   NOT NULL,
    limit_price      BIGINT CHECK (limit_price > 0),
    open_qty         BIGINT NOT NULL CHECK (open_qty >= 0),
    filled_qty       BIGINT NOT NULL DEFAULT 0 CHECK (filled_qty >= 0),
    reserved_cash    BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cash >= 0),
    reserved_shares  BIGINT NOT NULL DEFAULT 0 CHECK (reserved_shares >= 0),
    amend_pending    BOOLEAN NOT NULL DEFAULT false,
    pending_cash     BIGINT NOT NULL DEFAULT 0 CHECK (pending_cash >= 0),
    pending_shares   BIGINT NOT NULL DEFAULT 0 CHECK (pending_shares >= 0),
    status           TEXT   NOT NULL
        CHECK (status IN ('PENDING', 'OPEN', 'FILLED', 'CANCELLED', 'REJECTED', 'RELEASED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, order_id)
);
CREATE INDEX reservations_live ON portfolio.reservations (account_id) WHERE status IN ('PENDING', 'OPEN');

CREATE TABLE portfolio.fills (
    fill_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES portfolio.accounts (account_id),
    symbol      TEXT   NOT NULL,
    order_id    BIGINT NOT NULL,
    side        TEXT   NOT NULL CHECK (side IN ('BUY', 'SELL')),
    price       BIGINT NOT NULL CHECK (price > 0),
    qty         BIGINT NOT NULL CHECK (qty > 0),
    seq         BIGINT,
    settled_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX fills_account ON portfolio.fills (account_id, fill_id);

-- Last trade price per symbol, for unrealized P&L and market-order collars.
CREATE TABLE portfolio.marks (
    symbol      TEXT PRIMARY KEY,
    last_price  BIGINT NOT NULL CHECK (last_price > 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Kafka offsets of the settlement consumer, committed in the same transaction as the settlement they cover.
CREATE TABLE portfolio.consumer_offsets (
    topic        TEXT    NOT NULL,
    partition    INTEGER NOT NULL,
    next_offset  BIGINT  NOT NULL,
    PRIMARY KEY (topic, partition)
);
