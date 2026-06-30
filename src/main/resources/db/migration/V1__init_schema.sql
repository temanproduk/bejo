CREATE TABLE IF NOT EXISTS users (
    id                        BIGSERIAL     PRIMARY KEY,
    username                  VARCHAR(50)   NOT NULL,
    password_hash             VARCHAR(255)  NOT NULL,
    name                      VARCHAR(100)  NOT NULL,
    email                     VARCHAR(255)  NOT NULL,
    phone                     VARCHAR(20)   NOT NULL,
    business_name             VARCHAR(255)  NOT NULL,
    business_address          TEXT,
    nmid                      VARCHAR(50),
    initial_business_name     VARCHAR(255),
    initial_business_address  TEXT,
    initial_nmid              VARCHAR(50),
    bank_name                 VARCHAR(100)  NOT NULL DEFAULT '',
    bank_number               VARCHAR(20)   NOT NULL DEFAULT '',
    bank_account_name         VARCHAR(100)  NOT NULL DEFAULT '',
    initial_bank_name         VARCHAR(100),
    initial_bank_number       VARCHAR(20),
    initial_bank_account_name VARCHAR(100),
    qris_code                 VARCHAR(36),
    qris_image                TEXT,
    uploaded_qris_image       TEXT,
    account_type              VARCHAR(50)   NOT NULL DEFAULT 'MANAGED',
    is_email_verified         BOOLEAN       NOT NULL DEFAULT FALSE,
    is_phone_verified         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_username  UNIQUE (username),
    CONSTRAINT uq_qris_code UNIQUE (qris_code)
);

CREATE TABLE IF NOT EXISTS cuan_sessions (
    id             BIGSERIAL    PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL,
    device_id      VARCHAR(255) NOT NULL,
    push_token     TEXT,
    login_status   BOOLEAN      NOT NULL DEFAULT FALSE,
    login_date     TIMESTAMP,
    last_updated   TIMESTAMP,
    schedule_start INTEGER      NOT NULL DEFAULT 8,
    schedule_end   INTEGER      NOT NULL DEFAULT 22,
    CONSTRAINT uq_username_device UNIQUE (username, device_id),
    CONSTRAINT fk_session_user FOREIGN KEY (username) REFERENCES users (username)
);

CREATE TABLE IF NOT EXISTS cuan_history (
    id               BIGSERIAL    PRIMARY KEY,
    transaction_id   VARCHAR(255) NOT NULL,
    username         VARCHAR(50)  NOT NULL,
    type             VARCHAR(50)  NOT NULL,
    acquirer         VARCHAR(255) NOT NULL,
    transaction_date TIMESTAMP    NOT NULL,
    amount           BIGINT       NOT NULL,
    status           VARCHAR(100) NOT NULL DEFAULT 'SUCCESS',
    source           VARCHAR(255),
    rrn              VARCHAR(255),
    merchant_pan     VARCHAR(50),
    customer_pan     VARCHAR(50),
    idempotency_key  VARCHAR(255),
    invoice_id       VARCHAR(255),
    settlement_date  TIMESTAMP,
    customer_name    VARCHAR(255),
    info             TEXT,
    scrape_date      TIMESTAMP,
    CONSTRAINT uq_transaction_id  UNIQUE (transaction_id),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cuan_history_username         ON cuan_history (username);
CREATE INDEX IF NOT EXISTS idx_cuan_history_transaction_date ON cuan_history (transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_cuan_sessions_device_id       ON cuan_sessions (device_id);
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires_at    ON token_blacklist (expires_at);
