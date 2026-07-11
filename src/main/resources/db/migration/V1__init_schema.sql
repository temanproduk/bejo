-- Full baseline schema. The DB is dropped and recreated from this baseline (no prod data
-- to preserve), so business/user decoupling is folded straight in rather than migrated.
-- Seed data is inserted by DataSeeder at app startup, not here.
--
-- Model: a BUSINESS (merchant) owns bank, QRIS, products, categories, transactions, stock
-- mutations, app settings, and the shared business picture. Many USERS may belong to one
-- business (business_id), each with a business_role (SUPER_ADMIN creator | MEMBER). Users
-- are soft-deletable; a released username is reusable (uniqueness among active users only).

CREATE TABLE businesses (
    id             BIGSERIAL     PRIMARY KEY,
    name           VARCHAR(255)  NOT NULL,
    address        TEXT,
    nmid           VARCHAR(50),
    initial_name   VARCHAR(255),
    initial_address TEXT,
    initial_nmid   VARCHAR(50),
    size           VARCHAR(30)   NOT NULL DEFAULT 'Mikro',                -- set once at registration, immutable
    industry       VARCHAR(100)  NOT NULL DEFAULT 'Makanan & Minuman',    -- set once at registration, immutable
    store_photo    TEXT,                              -- optional store photo, base64, <= 1 MB decoded
    picture        TEXT,                              -- shared business picture, base64, <= 1 MB decoded
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);
-- NMID uniquely routes an inbound QRIS callback to a business (nulls allowed & non-unique)
CREATE UNIQUE INDEX uq_business_nmid ON businesses (nmid) WHERE nmid IS NOT NULL;

CREATE TABLE users (
    id                 BIGSERIAL     PRIMARY KEY,
    username           VARCHAR(50)   NOT NULL,
    password_hash      VARCHAR(255)  NOT NULL,
    name               VARCHAR(100)  NOT NULL,
    business_id        BIGINT        NOT NULL,
    business_role      VARCHAR(20)   NOT NULL DEFAULT 'MEMBER',
    email              VARCHAR(255)  NOT NULL,
    phone              VARCHAR(20)   NOT NULL,
    is_email_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    is_phone_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    account_type       VARCHAR(50)   NOT NULL DEFAULT 'MANAGED',
    deleted_at         TIMESTAMP,                     -- soft delete: non-null = removed, username released
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);
-- Username is unique only among ACTIVE users, so a deleted user's username can be reused
CREATE UNIQUE INDEX uq_users_username_active ON users (username) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_business ON users (business_id);

CREATE TABLE business_banks (
    id                        BIGSERIAL     PRIMARY KEY,
    business_id               BIGINT        NOT NULL,
    bank_name                 VARCHAR(100)  NOT NULL DEFAULT '',
    bank_number               VARCHAR(20)   NOT NULL DEFAULT '',
    bank_account_name         VARCHAR(100)  NOT NULL DEFAULT '',
    initial_bank_name         VARCHAR(100),
    initial_bank_number       VARCHAR(20),
    initial_bank_account_name VARCHAR(100),
    CONSTRAINT uq_bank_business UNIQUE (business_id),
    CONSTRAINT fk_bank_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE business_qris (
    id                   BIGSERIAL   PRIMARY KEY,
    business_id          BIGINT      NOT NULL,
    qris_code            VARCHAR(36) NOT NULL,
    qris_image           TEXT,
    initial_qris_image   TEXT,
    CONSTRAINT uq_qris_business UNIQUE (business_id),
    CONSTRAINT uq_qris_code     UNIQUE (qris_code),
    CONSTRAINT fk_qris_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE bejo_sessions (
    id             BIGSERIAL    PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL,             -- no FK: username uniqueness is partial (active only)
    device_id      VARCHAR(255) NOT NULL,
    push_token     TEXT,
    login_status   BOOLEAN      NOT NULL DEFAULT FALSE,
    login_date     TIMESTAMP,
    last_updated   TIMESTAMP,
    schedule_start INTEGER      NOT NULL DEFAULT 8,
    schedule_end   INTEGER      NOT NULL DEFAULT 22,
    CONSTRAINT uq_username_device UNIQUE (username, device_id)
);

CREATE TABLE bejo_history (
    id               BIGSERIAL    PRIMARY KEY,
    transaction_id   VARCHAR(255) NOT NULL,
    business_id      BIGINT       NOT NULL,
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
    info             TEXT,                       -- general-purpose extra context, e.g. raw QRIS callback payload JSON
    deleted_at       TIMESTAMP,
    scrape_date      TIMESTAMP,
    CONSTRAINT uq_transaction_id  UNIQUE (transaction_id),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_history_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

-- Products sold within a transaction (optional line items). Snapshots the product so
-- history survives a later product edit/delete; product_id is a plain reference (no FK).
CREATE TABLE transaction_items (
    id         BIGSERIAL    PRIMARY KEY,
    history_id BIGINT       NOT NULL,                 -- parent bejo_history.id
    product_id BIGINT,
    sku        VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    quantity   INTEGER      NOT NULL,
    unit_price BIGINT       NOT NULL DEFAULT 0,       -- selling price per unit at sale time, IDR
    subtotal   BIGINT       NOT NULL DEFAULT 0,       -- unit_price * quantity, IDR
    CONSTRAINT fk_transaction_item_history FOREIGN KEY (history_id) REFERENCES bejo_history (id)
);

CREATE TABLE token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);

CREATE TABLE business_app_settings (
    id                        BIGSERIAL   PRIMARY KEY,
    business_id               BIGINT      NOT NULL,
    monitored_apps            TEXT,
    include_keywords          TEXT,
    exclude_keywords          TEXT,
    blacklist_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    whitelist_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    qris_with_profile_picture BOOLEAN     NOT NULL DEFAULT FALSE,
    inventory_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at                TIMESTAMP,
    CONSTRAINT uq_app_settings_business UNIQUE (business_id),
    CONSTRAINT fk_app_settings_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

-- ── Products, categories & inventory (per business) ───────────────────────────
CREATE TABLE product_categories (
    id          BIGSERIAL    PRIMARY KEY,
    business_id BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_category_name UNIQUE (business_id, name),
    CONSTRAINT fk_category_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE products (
    id                BIGSERIAL     PRIMARY KEY,
    business_id       BIGINT        NOT NULL,
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
    CONSTRAINT uq_product_sku      UNIQUE (business_id, sku),
    CONSTRAINT fk_product_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_categories (id)
);

-- ── Stock mutations (restock / opname / product edit / sale) ─────────────────
-- A stock mutation is any recorded change to product stock. type is one of PURCHASE
-- (restock), ADJUSTMENT (stock opname), UPDATE (product edit changed stock), SALE (a
-- transaction with items). total_amount is only meaningful for PURCHASE.
CREATE TABLE stock_mutations (
    id           BIGSERIAL   PRIMARY KEY,
    business_id  BIGINT      NOT NULL,
    type         VARCHAR(20) NOT NULL DEFAULT 'PURCHASE',
    total_amount BIGINT      NOT NULL DEFAULT 0,
    note         VARCHAR(255),
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_mutation_business FOREIGN KEY (business_id) REFERENCES businesses (id)
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
CREATE INDEX idx_bejo_history_business          ON bejo_history (business_id);
CREATE INDEX idx_bejo_history_transaction_date  ON bejo_history (transaction_date DESC);
CREATE INDEX idx_transaction_items_history      ON transaction_items (history_id);
CREATE INDEX idx_bejo_sessions_device_id        ON bejo_sessions (device_id);
CREATE INDEX idx_token_blacklist_expires_at     ON token_blacklist (expires_at);
CREATE INDEX idx_product_categories_business     ON product_categories (business_id);
CREATE INDEX idx_products_business               ON products (business_id);
CREATE INDEX idx_products_category               ON products (category_id);
CREATE INDEX idx_stock_mutations_business             ON stock_mutations (business_id, created_at DESC);
CREATE INDEX idx_stock_mutation_items_mutation        ON stock_mutation_items (mutation_id);
