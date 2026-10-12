CREATE TABLE transactions
(
    id                      UUID PRIMARY KEY,
    account_id              VARCHAR(64)    NOT NULL,
    type                    VARCHAR(10)    NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)     NOT NULL,
    description             VARCHAR(255),
    -- Client-supplied Idempotency-Key of POST /transactions. NULL when the client sent none;
    -- PostgreSQL lets any number of NULLs coexist under a UNIQUE constraint, so only real keys
    -- are de-duplicated. The constraint's index also serves the lookup by key.
    idempotency_key         VARCHAR(64)    CONSTRAINT uk_transactions_idempotency_key UNIQUE,
    status                VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED')),
    provider_transaction_id VARCHAR(64),
    balance_after           NUMERIC(19, 2),
    failure_code            VARCHAR(64),
    failure_message         TEXT,
    created_at              TIMESTAMPTZ    NOT NULL,
    updated_at              TIMESTAMPTZ    NOT NULL,
    version                 BIGINT         NOT NULL
);

-- Indexes follow the query patterns, all sorted newest first (created_at DESC, id DESC):
-- GET /transactions?accountId=...[&status=][&type=]  (the main access path)
CREATE INDEX idx_transactions_account_created ON transactions (account_id, created_at DESC, id DESC);
-- GET /transactions?status=...  and reconciliation of transactions stuck in PENDING
CREATE INDEX idx_transactions_status_created ON transactions (status, created_at DESC, id DESC);
-- GET /transactions with no filter
CREATE INDEX idx_transactions_created ON transactions (created_at DESC, id DESC);
-- No standalone index on "type": with only 2 values it is not selective enough to be used.
