-- Safe migration: add any columns that may be missing from the live database.
-- All statements use IF NOT EXISTS / IF EXISTS so this script is idempotent.

-- users: bank info columns
ALTER TABLE users ADD COLUMN IF NOT EXISTS bank_name             VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS bank_number           VARCHAR(20)  NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS bank_account_name     VARCHAR(100) NOT NULL DEFAULT '';

-- users: initial (regulatory) backup columns
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_business_name     VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_business_address  TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_bank_name         VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_bank_number       VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_bank_account_name VARCHAR(100);

-- users: nmid
ALTER TABLE users ADD COLUMN IF NOT EXISTS nmid         VARCHAR(50);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_nmid VARCHAR(50);

-- cuan_history: columns added over time
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS idempotency_key  VARCHAR(255);
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS invoice_id       VARCHAR(255);
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS settlement_date  TIMESTAMP;
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS customer_name    VARCHAR(255);
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS info             TEXT;
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS scrape_date      TIMESTAMP;

-- cuan_history: unique constraint on idempotency_key
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_idempotency_key'
    ) THEN
        ALTER TABLE cuan_history ADD CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key);
    END IF;
END$$;

-- token_blacklist table (may not exist on older deployments)
CREATE TABLE IF NOT EXISTS token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);

-- indexes
CREATE INDEX IF NOT EXISTS idx_cuan_history_username         ON cuan_history (username);
CREATE INDEX IF NOT EXISTS idx_cuan_history_transaction_date ON cuan_history (transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_cuan_sessions_device_id       ON cuan_sessions (device_id);
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires_at    ON token_blacklist (expires_at);
