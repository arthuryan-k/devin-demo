-- Brokerage system of record. Owners: auth-service (users, accounts, api_keys),
-- reference-data-service (reference_data), history-writer (orders_history, trades_history).
-- Money is NUMERIC; engine prices/quantities are integers (price_ticks = price * 100, i.e. cents).

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT        NOT NULL CHECK (username ~ '^[A-Za-z0-9_.-]{3,32}$'),
    -- bcrypt hash; NULL only for simulated participants, which authenticate with API keys.
    password_hash TEXT,
    kind          TEXT        NOT NULL DEFAULT 'human' CHECK (kind IN ('human', 'simulated')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (kind = 'simulated' OR password_hash IS NOT NULL)
);
CREATE UNIQUE INDEX users_username_ci ON users (lower(username));

-- Participant ids are what the engine stamps on orders; 0 = anonymous, 1 = the legacy in-process "YOU".
CREATE SEQUENCE participant_id_seq START WITH 1001;

CREATE TABLE accounts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    participant_id BIGINT        NOT NULL UNIQUE DEFAULT nextval('participant_id_seq'),
    label          TEXT          NOT NULL UNIQUE,
    -- Cash granted when the account was opened: the sum of reference_data.initial_cash_grant over active symbols.
    starting_cash  NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (starting_cash >= 0),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
ALTER SEQUENCE participant_id_seq OWNED BY accounts.participant_id;

CREATE TABLE api_keys (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    -- Only the SHA-256 of the key is stored; the plaintext is shown once, at issuance.
    key_hash     BYTEA       NOT NULL UNIQUE CHECK (octet_length(key_hash) = 32),
    -- Leading characters of the key, for display ("ob_1a2b3c4d…").
    key_prefix   TEXT        NOT NULL,
    label        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX api_keys_account ON api_keys (account_id);

CREATE TABLE reference_data (
    symbol             TEXT           PRIMARY KEY CHECK (symbol ~ '^[A-Z][A-Z0-9]{0,9}$'),
    name               TEXT           NOT NULL,
    currency           CHAR(3)        NOT NULL DEFAULT 'USD',
    tick_size          NUMERIC(18, 8) NOT NULL CHECK (tick_size > 0),
    lot_size           INTEGER        NOT NULL CHECK (lot_size > 0),
    -- Cash credited to every new account for this listing.
    initial_cash_grant NUMERIC(18, 2) NOT NULL CHECK (initial_cash_grant >= 0),
    active             BOOLEAN        NOT NULL DEFAULT true,
    display_order      INTEGER        NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Order lifecycle, append-only: one row per engine order event on events.<symbol>.
-- (symbol, seq, event_index) is the idempotency key, so redelivered bus messages are no-ops.
CREATE TABLE orders_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol         TEXT        NOT NULL,
    seq            BIGINT      NOT NULL,
    event_index    INTEGER     NOT NULL,
    order_id       BIGINT      NOT NULL,
    participant_id BIGINT,
    event_type     TEXT        NOT NULL CHECK (event_type IN ('PLACED', 'AMENDED', 'CANCELLED', 'REJECTED')),
    side           TEXT        CHECK (side IN ('BUY', 'SELL')),
    order_type     TEXT,
    time_in_force  TEXT,
    price_ticks    BIGINT,
    qty            BIGINT,
    reason         TEXT,
    -- Event-specific fields (old/new price and qty of an amend, cancelled qty, priority retained, ...).
    details        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    event_time     TIMESTAMPTZ NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (symbol, seq, event_index)
);
CREATE INDEX orders_history_order ON orders_history (symbol, order_id);
CREATE INDEX orders_history_participant ON orders_history (participant_id, event_time DESC);

-- Fills, append-only: one row per TradeExecuted, at the maker's price.
CREATE TABLE trades_history (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol               TEXT        NOT NULL,
    seq                  BIGINT      NOT NULL,
    event_index          INTEGER     NOT NULL,
    maker_order_id       BIGINT      NOT NULL,
    taker_order_id       BIGINT      NOT NULL,
    maker_participant_id BIGINT,
    taker_participant_id BIGINT,
    -- Side of the taker (aggressor); the maker is on the other side.
    aggressor_side       TEXT        CHECK (aggressor_side IN ('BUY', 'SELL')),
    price_ticks          BIGINT      NOT NULL CHECK (price_ticks > 0),
    qty                  BIGINT      NOT NULL CHECK (qty > 0),
    event_time           TIMESTAMPTZ NOT NULL,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (symbol, seq, event_index)
);
CREATE INDEX trades_history_symbol_time ON trades_history (symbol, event_time DESC);
CREATE INDEX trades_history_maker ON trades_history (maker_participant_id, event_time DESC);
CREATE INDEX trades_history_taker ON trades_history (taker_participant_id, event_time DESC);
