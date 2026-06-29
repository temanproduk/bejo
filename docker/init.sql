CREATE TABLE IF NOT EXISTS users (
    id                BIGSERIAL PRIMARY KEY,
    username          VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    name              VARCHAR(255),
    business_name             VARCHAR(255),
    business_address          TEXT,
    initial_business_name     VARCHAR(255),
    initial_business_address  TEXT,
    email             VARCHAR(255) NOT NULL DEFAULT '',
    phone             VARCHAR(50)  NOT NULL DEFAULT '',
    bank_name                 VARCHAR(255) NOT NULL DEFAULT '',
    bank_number               VARCHAR(100) NOT NULL DEFAULT '',
    bank_account_name         VARCHAR(255) NOT NULL DEFAULT '',
    initial_bank_name         VARCHAR(255),
    initial_bank_number       VARCHAR(100),
    initial_bank_account_name VARCHAR(255),
    qris_code              VARCHAR(36),
    qris_image             TEXT,
    uploaded_qris_image    TEXT,
    is_email_verified      BOOLEAN   NOT NULL DEFAULT FALSE,
    is_phone_verified      BOOLEAN   NOT NULL DEFAULT FALSE,
    account_type           VARCHAR(50) NOT NULL DEFAULT 'MANAGED',
    created_at             TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_username  UNIQUE (username),
    CONSTRAINT uq_qris_code UNIQUE (qris_code)
);

-- Idempotent migrations for existing databases
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_name             VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS business_address          TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_business_name     VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_business_address  TEXT;
UPDATE users SET initial_business_name = business_name, initial_business_address = business_address WHERE initial_business_name IS NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS qris_code         VARCHAR(36);
ALTER TABLE users ADD COLUMN IF NOT EXISTS bank_name         VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS bank_number       VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS bank_account_name     VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS qris_image             TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS uploaded_qris_image    TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_email_verified      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_phone_verified      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_type               VARCHAR(50) NOT NULL DEFAULT 'MANAGED';
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_bank_name         VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_bank_number       VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_bank_account_name VARCHAR(255);
UPDATE users SET initial_bank_name = bank_name, initial_bank_number = bank_number, initial_bank_account_name = bank_account_name WHERE initial_bank_name IS NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_qris_code') THEN
    ALTER TABLE users ADD CONSTRAINT uq_qris_code UNIQUE (qris_code);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS cuan_sessions (
    id             BIGSERIAL PRIMARY KEY,
    username       VARCHAR(255)  NOT NULL,
    login_date     TIMESTAMP,
    login_status   BOOLEAN       DEFAULT FALSE,
    push_token     TEXT,
    device_id      VARCHAR(255)  NOT NULL,
    last_updated   TIMESTAMP,
    schedule_start INTEGER       DEFAULT 8,
    schedule_end   INTEGER       DEFAULT 22,
    CONSTRAINT uq_username_device UNIQUE (username, device_id),
    CONSTRAINT fk_session_user FOREIGN KEY (username) REFERENCES users (username)
);

CREATE TABLE IF NOT EXISTS cuan_history (
    id               BIGSERIAL PRIMARY KEY,
    transaction_id   VARCHAR(255) NOT NULL,
    transaction_date TIMESTAMP,
    amount           BIGINT,
    status           VARCHAR(100),
    invoice_id       VARCHAR(255),
    settlement_date  TIMESTAMP,
    source           VARCHAR(255),
    customer_name    VARCHAR(255),
    rrn              VARCHAR(255),
    info             TEXT,
    username         VARCHAR(255),
    scrape_date      TIMESTAMP,
    merchant_pan     VARCHAR(50),
    customer_pan     VARCHAR(50),
    type             VARCHAR(50),
    acquirer         VARCHAR(255),
    idempotency_key  VARCHAR(255),
    CONSTRAINT uq_transaction_id  UNIQUE (transaction_id),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

-- Idempotent migrations for existing databases
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS merchant_pan VARCHAR(50);
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS customer_pan VARCHAR(50);
ALTER TABLE cuan_history ALTER COLUMN amount TYPE BIGINT USING amount::BIGINT;
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS type            VARCHAR(50);
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS acquirer        VARCHAR(255);
ALTER TABLE cuan_history ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);
UPDATE cuan_history SET type = 'MANAGED', acquirer = 'AstraPay' WHERE type IS NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_idempotency_key') THEN
    ALTER TABLE cuan_history ADD CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP  NOT NULL
);

-- Idempotent migration for existing databases
CREATE TABLE IF NOT EXISTS token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cuan_history_username ON cuan_history (username);
CREATE INDEX IF NOT EXISTS idx_cuan_history_transaction_date ON cuan_history (transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_cuan_sessions_device_id ON cuan_sessions (device_id);
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires_at ON token_blacklist (expires_at);

-- To create a user with business info, insert with a BCrypt-hashed password (strength 10).
-- Generate hash: new BCryptPasswordEncoder().encode("your-password")
-- INSERT INTO users (username, password_hash, name, business_name, qris_code)
-- VALUES ('alice', '$2a$10$...', 'Alice', 'Alice Shop', gen_random_uuid()::text);
