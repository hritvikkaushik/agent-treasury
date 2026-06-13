-- Agent Treasury — initial schema (Postgres)

CREATE TABLE agents (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    api_key_hash        VARCHAR(128) NOT NULL,
    per_tx_cap_atomic   BIGINT      NOT NULL,
    daily_budget_atomic BIGINT      NOT NULL,
    velocity_per_minute INTEGER     NOT NULL,
    min_reputation      INTEGER     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_allowed_merchants (
    agent_id VARCHAR(64) NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    merchant VARCHAR(64) NOT NULL,
    PRIMARY KEY (agent_id, merchant)
);

CREATE TABLE agent_allowed_assets (
    agent_id VARCHAR(64) NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    asset    VARCHAR(64) NOT NULL,
    PRIMARY KEY (agent_id, asset)
);

CREATE TABLE payment_intent (
    id              UUID PRIMARY KEY,
    agent_id        VARCHAR(64)  NOT NULL REFERENCES agents (id),
    payee           VARCHAR(64)  NOT NULL,
    asset           VARCHAR(64)  NOT NULL,
    amount_atomic   BIGINT       NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    state           VARCHAR(32)  NOT NULL,
    denial_reason   VARCHAR(64),
    tx_hash         VARCHAR(80),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_intent_agent_created ON payment_intent (agent_id, created_at);

-- Double-entry journal. Each balanced pair of rows shares an intent_id.
CREATE TABLE journal_entry (
    id            BIGSERIAL PRIMARY KEY,
    intent_id     UUID        NOT NULL REFERENCES payment_intent (id),
    account       VARCHAR(96) NOT NULL,
    debit_atomic  BIGINT      NOT NULL DEFAULT 0,
    credit_atomic BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_entry_account ON journal_entry (account);
CREATE INDEX idx_journal_entry_intent ON journal_entry (intent_id);
