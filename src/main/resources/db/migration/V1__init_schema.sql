-- Full baseline schema. Consolidated from the earlier V1–V6 (the production DB is
-- rebuilt from scratch + reseeded, so no incremental history needs preserving).
-- Seed data is inserted by DataSeeder at app startup, not here.

CREATE TABLE users (
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
    account_type              VARCHAR(50)   NOT NULL DEFAULT 'MANAGED',
    profile_picture           TEXT,
    is_email_verified         BOOLEAN       NOT NULL DEFAULT FALSE,
    is_phone_verified         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_username UNIQUE (username)
);

CREATE TABLE user_banks (
    id                        BIGSERIAL     PRIMARY KEY,
    username                  VARCHAR(50)   NOT NULL,
    bank_name                 VARCHAR(100)  NOT NULL DEFAULT '',
    bank_number               VARCHAR(20)   NOT NULL DEFAULT '',
    bank_account_name         VARCHAR(100)  NOT NULL DEFAULT '',
    initial_bank_name         VARCHAR(100),
    initial_bank_number       VARCHAR(20),
    initial_bank_account_name VARCHAR(100),
    CONSTRAINT uq_bank_username UNIQUE (username),
    CONSTRAINT fk_bank_user FOREIGN KEY (username) REFERENCES users (username)
);

CREATE TABLE user_qris (
    id                   BIGSERIAL   PRIMARY KEY,
    username             VARCHAR(50) NOT NULL,
    qris_code            VARCHAR(36) NOT NULL,
    qris_image           TEXT,
    initial_qris_image   TEXT,
    CONSTRAINT uq_qris_username UNIQUE (username),
    CONSTRAINT uq_qris_code     UNIQUE (qris_code),
    CONSTRAINT fk_qris_user FOREIGN KEY (username) REFERENCES users (username)
);

CREATE TABLE bejo_sessions (
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

CREATE TABLE bejo_history (
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
    additional_info  TEXT,
    deleted_at       TIMESTAMP,
    scrape_date      TIMESTAMP,
    CONSTRAINT uq_transaction_id  UNIQUE (transaction_id),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);

CREATE TABLE user_app_settings (
    id                        BIGSERIAL   PRIMARY KEY,
    username                  VARCHAR(50) NOT NULL,
    monitored_apps            TEXT,
    include_keywords          TEXT,
    exclude_keywords          TEXT,
    blacklist_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    whitelist_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    qris_with_profile_picture BOOLEAN     NOT NULL DEFAULT FALSE,
    inventory_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at                TIMESTAMP,
    CONSTRAINT uq_app_settings_username UNIQUE (username),
    CONSTRAINT fk_app_settings_user FOREIGN KEY (username) REFERENCES users (username)
);

-- ── Products, categories & inventory (per merchant) ───────────────────────────
CREATE TABLE product_categories (
    id         BIGSERIAL    PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_category_name UNIQUE (username, name),
    CONSTRAINT fk_category_user FOREIGN KEY (username) REFERENCES users (username)
);

CREATE TABLE products (
    id                BIGSERIAL     PRIMARY KEY,
    username          VARCHAR(50)   NOT NULL,
    sku               VARCHAR(64)   NOT NULL,
    name              VARCHAR(255)  NOT NULL,
    short_description VARCHAR(255),
    category_id       BIGINT,                            -- nullable -> uncategorized
    price             BIGINT        NOT NULL DEFAULT 0,  -- selling price, IDR
    cost_price        BIGINT,                            -- purchase cost, IDR (optional)
    stock             INTEGER       NOT NULL DEFAULT 0,  -- qty on hand
    photo             TEXT,                              -- base64 image, <= 500 KB decoded
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP,
    CONSTRAINT uq_product_sku      UNIQUE (username, sku),
    CONSTRAINT fk_product_user     FOREIGN KEY (username)    REFERENCES users (username),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_categories (id)
);

-- ── Stock mutations (restock / opname / product edit) ────────────────────────
-- A stock mutation is any recorded change to product stock. type is one of
-- PURCHASE (restock), ADJUSTMENT (stock opname), UPDATE (product edit changed stock).
-- total_amount is only meaningful for PURCHASE (sum of line subtotals).
CREATE TABLE stock_mutations (
    id           BIGSERIAL   PRIMARY KEY,
    username     VARCHAR(50) NOT NULL,
    type         VARCHAR(20) NOT NULL DEFAULT 'PURCHASE',
    total_amount BIGINT      NOT NULL DEFAULT 0,  -- sum of line subtotals, IDR (PURCHASE)
    note         VARCHAR(255),
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_mutation_user FOREIGN KEY (username) REFERENCES users (username)
);

-- Line items snapshot the product (sku/name/price) so history survives even if the
-- product is later edited or deleted; product_id is a plain reference (no FK).
CREATE TABLE stock_mutation_items (
    id              BIGSERIAL    PRIMARY KEY,
    mutation_id     BIGINT       NOT NULL,
    product_id      BIGINT,
    sku             VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    previous_stock  INTEGER      NOT NULL DEFAULT 0,
    new_stock       INTEGER      NOT NULL DEFAULT 0,
    quantity_change INTEGER      NOT NULL DEFAULT 0,  -- new_stock - previous_stock (signed)
    unit_price      BIGINT,                           -- cost per unit (PURCHASE only), IDR
    subtotal        BIGINT,                           -- unit_price * quantity (PURCHASE only)
    CONSTRAINT fk_mutation_item FOREIGN KEY (mutation_id) REFERENCES stock_mutations (id)
);

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX idx_bejo_history_username         ON bejo_history (username);
CREATE INDEX idx_bejo_history_transaction_date ON bejo_history (transaction_date DESC);
CREATE INDEX idx_bejo_sessions_device_id       ON bejo_sessions (device_id);
CREATE INDEX idx_token_blacklist_expires_at    ON token_blacklist (expires_at);
CREATE INDEX idx_product_categories_username    ON product_categories (username);
CREATE INDEX idx_products_username              ON products (username);
CREATE INDEX idx_products_category              ON products (category_id);
CREATE INDEX idx_stock_mutations_username             ON stock_mutations (username, created_at DESC);
CREATE INDEX idx_stock_mutation_items_mutation        ON stock_mutation_items (mutation_id);
