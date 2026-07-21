-- Full baseline schema. The DB is dropped and recreated from this single baseline (pre-launch,
-- no prod data to preserve), so everything is folded in here rather than migrated across versions.
-- Seed data is inserted by DataSeeder at app startup, not here.
--
-- Model: a BUSINESS (merchant) owns bank, QRIS, products, categories, transactions, stock
-- mutations, app settings, presence, ad deliveries, and the shared business picture. Many USERS
-- may belong to one business (business_id), each with a business_role (SUPER_ADMIN creator |
-- MEMBER). Users are soft-deletable; a released username is reusable (uniqueness among active
-- users only).

CREATE TABLE IF NOT EXISTS businesses (
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
CREATE UNIQUE INDEX IF NOT EXISTS uq_business_nmid ON businesses (nmid) WHERE nmid IS NOT NULL;

CREATE TABLE IF NOT EXISTS users (
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
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_active ON users (username) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_users_business ON users (business_id);

CREATE TABLE IF NOT EXISTS business_banks (
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

CREATE TABLE IF NOT EXISTS business_qris (
    id                   BIGSERIAL   PRIMARY KEY,
    business_id          BIGINT      NOT NULL,
    qris_code            VARCHAR(36) NOT NULL,
    qris_image           TEXT,
    initial_qris_image   TEXT,
    CONSTRAINT uq_qris_business UNIQUE (business_id),
    CONSTRAINT uq_qris_code     UNIQUE (qris_code),
    CONSTRAINT fk_qris_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS bejo_sessions (
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

CREATE TABLE IF NOT EXISTS bejo_history (
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
CREATE TABLE IF NOT EXISTS transaction_items (
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

CREATE TABLE IF NOT EXISTS token_blacklist (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS account_settings (
    id                        BIGSERIAL   PRIMARY KEY,
    business_id               BIGINT      NOT NULL,
    monitored_apps            TEXT,
    include_keywords          TEXT,
    exclude_keywords          TEXT,
    blacklist_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    whitelist_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    qris_with_profile_picture BOOLEAN     NOT NULL DEFAULT FALSE,
    inventory_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    receive_ads               BOOLEAN     NOT NULL DEFAULT FALSE,   -- opt-in to voice ads (super-admin-set)
    team_management_enabled   BOOLEAN     NOT NULL DEFAULT FALSE,   -- activate employees + presence (super-admin-set)
    updated_at                TIMESTAMP,
    CONSTRAINT uq_account_settings_business UNIQUE (business_id),
    CONSTRAINT fk_account_settings_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

-- ── Products, categories & inventory (per business) ───────────────────────────
CREATE TABLE IF NOT EXISTS product_categories (
    id          BIGSERIAL    PRIMARY KEY,
    business_id BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_category_name UNIQUE (business_id, name),
    CONSTRAINT fk_category_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS products (
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
CREATE TABLE IF NOT EXISTS stock_mutations (
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
CREATE TABLE IF NOT EXISTS stock_mutation_items (
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

-- ── Employee daily presence (attendance) ─────────────────────────────────────
-- One row per attendance entry: a member's CLOCK_IN / CLOCK_OUT (with location + the
-- client-supplied time), or a PING the super admin sent that member. username/name snapshot
-- the member so history survives a rename or soft-delete; user_id is a plain reference (no FK).
-- The calendar day is always CAST(clock_datetime AS DATE) — there is no stored date column;
-- response_datetime is set only when a member acknowledges their PING from the app.
CREATE TABLE IF NOT EXISTS presences (
    id                BIGSERIAL        PRIMARY KEY,
    business_id       BIGINT           NOT NULL,
    user_id           BIGINT           NOT NULL,
    username          VARCHAR(50)      NOT NULL,   -- same width as users.username
    name              VARCHAR(255)     NOT NULL,
    type              VARCHAR(20)      NOT NULL,   -- CLOCK_IN | CLOCK_OUT | PING
    clock_datetime    TIMESTAMP        NOT NULL,
    latitude          DOUBLE PRECISION,            -- null on a PING until the member responds
    longitude         DOUBLE PRECISION,
    note              VARCHAR(255),
    response_datetime TIMESTAMP,                   -- PING only: when the member tapped it
    created_at        TIMESTAMP        NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_presence_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

-- A member clocks in at most once and out at most once per calendar day (the day derived from
-- clock_datetime, no stored column). PINGs excluded: the super admin may ping repeatedly.
CREATE UNIQUE INDEX IF NOT EXISTS uq_presence_clock_daily
    ON presences (user_id, type, (CAST(clock_datetime AS DATE)))
    WHERE type IN ('CLOCK_IN', 'CLOCK_OUT');

-- ── Voice-ad campaigns ───────────────────────────────────────────────────────
-- A campaign is a brand's <=10s voice clip; its audio lives in Cloud Storage (only the object
-- name + integrity are kept here). A campaign fans out to devices as ad_deliveries — one per
-- target device — which carry the lifecycle state (see constant/AdCampaignState) + a timestamp
-- per step. target_username/target_device_id have no FK (same rationale as presences.user_id).
CREATE TABLE IF NOT EXISTS ad_campaigns (
    id                  BIGSERIAL     PRIMARY KEY,
    name                VARCHAR(255)  NOT NULL,
    brand               VARCHAR(255),
    audio_object        VARCHAR(500)  NOT NULL,   -- GCS object name (bucket is app config)
    audio_content_type  VARCHAR(100)  NOT NULL,
    audio_sha256        VARCHAR(64)   NOT NULL,   -- hex; the app must match this after download
    audio_size_bytes    BIGINT        NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ad_deliveries (
    id                  BIGSERIAL     PRIMARY KEY,
    campaign_id         BIGINT        NOT NULL,
    target_username     VARCHAR(50)   NOT NULL,   -- same width as users.username
    target_device_id    VARCHAR(255)  NOT NULL,
    state               VARCHAR(20)   NOT NULL DEFAULT 'CREATED',
    error_reason        VARCHAR(500),
    reported_sha256     VARCHAR(64),
    reported_size_bytes BIGINT,
    prepared_at         TIMESTAMP,
    ready_at            TIMESTAMP,
    started_at          TIMESTAMP,
    played_at           TIMESTAMP,
    finished_at         TIMESTAMP,
    errored_at          TIMESTAMP,
    play_latitude       DOUBLE PRECISION,         -- where the ad played (may arrive later)
    play_longitude      DOUBLE PRECISION,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ad_delivery_campaign FOREIGN KEY (campaign_id) REFERENCES ad_campaigns (id)
);

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_bejo_history_business          ON bejo_history (business_id);
CREATE INDEX IF NOT EXISTS idx_bejo_history_transaction_date  ON bejo_history (transaction_date DESC);
CREATE INDEX IF NOT EXISTS idx_transaction_items_history      ON transaction_items (history_id);
CREATE INDEX IF NOT EXISTS idx_bejo_sessions_device_id        ON bejo_sessions (device_id);
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires_at     ON token_blacklist (expires_at);
CREATE INDEX IF NOT EXISTS idx_product_categories_business     ON product_categories (business_id);
CREATE INDEX IF NOT EXISTS idx_products_business               ON products (business_id);
CREATE INDEX IF NOT EXISTS idx_products_category               ON products (category_id);
CREATE INDEX IF NOT EXISTS idx_stock_mutations_business             ON stock_mutations (business_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_mutation_items_mutation        ON stock_mutation_items (mutation_id);
CREATE INDEX IF NOT EXISTS idx_presences_business_clock  ON presences (business_id, clock_datetime DESC);
CREATE INDEX IF NOT EXISTS idx_presences_user_clock      ON presences (business_id, user_id, clock_datetime DESC);
CREATE INDEX IF NOT EXISTS idx_ad_deliveries_campaign    ON ad_deliveries (campaign_id);
CREATE INDEX IF NOT EXISTS idx_ad_deliveries_target      ON ad_deliveries (target_username, state);
