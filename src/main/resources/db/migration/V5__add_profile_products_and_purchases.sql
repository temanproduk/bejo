-- Consolidated migration: user profile picture + product/inventory management +
-- purchase orders. (Merged from what were previously V5/V6/V7; safe because none had
-- been applied to production, which was at V4.)

-- ── User profile picture ──────────────────────────────────────────────────────
-- Optional user profile picture, stored as a base64-encoded (compressed) image.
ALTER TABLE users ADD COLUMN profile_picture TEXT;

-- ── Products, categories & inventory (per merchant) ───────────────────────────
CREATE TABLE product_categories (
    id         BIGSERIAL    PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_category_name UNIQUE (username, name),
    CONSTRAINT fk_category_user FOREIGN KEY (username) REFERENCES users (username)
);
CREATE INDEX idx_product_categories_username ON product_categories (username);

CREATE TABLE products (
    id          BIGSERIAL     PRIMARY KEY,
    username    VARCHAR(50)   NOT NULL,
    sku         VARCHAR(64)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    category_id BIGINT,                            -- nullable -> uncategorized
    price       BIGINT        NOT NULL DEFAULT 0,  -- selling price, IDR
    cost_price  BIGINT,                            -- purchase cost, IDR (optional)
    stock       INTEGER       NOT NULL DEFAULT 0,  -- qty on hand
    photo       TEXT,                              -- base64 image, <= 500 KB decoded
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP,
    CONSTRAINT uq_product_sku      UNIQUE (username, sku),
    CONSTRAINT fk_product_user     FOREIGN KEY (username)    REFERENCES users (username),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_categories (id)
);
CREATE INDEX idx_products_username ON products (username);
CREATE INDEX idx_products_category ON products (category_id);

-- ── Purchase orders (restock) ─────────────────────────────────────────────────
CREATE TABLE purchases (
    id           BIGSERIAL   PRIMARY KEY,
    username     VARCHAR(50) NOT NULL,
    total_amount BIGINT      NOT NULL DEFAULT 0,  -- sum of line subtotals, IDR
    note         VARCHAR(255),
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_purchase_user FOREIGN KEY (username) REFERENCES users (username)
);
CREATE INDEX idx_purchases_username ON purchases (username, created_at DESC);

-- Line items snapshot the product (sku/name/price) so history survives even if the
-- product is later edited or deleted; product_id is a plain reference (no FK).
CREATE TABLE purchase_items (
    id          BIGSERIAL    PRIMARY KEY,
    purchase_id BIGINT       NOT NULL,
    product_id  BIGINT,
    sku         VARCHAR(64)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    unit_price  BIGINT       NOT NULL DEFAULT 0,  -- cost per unit at purchase time, IDR
    quantity    INTEGER      NOT NULL,
    subtotal    BIGINT       NOT NULL DEFAULT 0,  -- unit_price * quantity
    CONSTRAINT fk_item_purchase FOREIGN KEY (purchase_id) REFERENCES purchases (id)
);
CREATE INDEX idx_purchase_items_purchase ON purchase_items (purchase_id);

-- ── App config toggles (on user_app_settings, created in V4) ───────────────────
-- Whether the QRIS image should be composed onto the profile picture, and whether
-- inventory management is enabled. Both default off.
ALTER TABLE user_app_settings ADD COLUMN qris_with_profile_picture BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_app_settings ADD COLUMN inventory_enabled         BOOLEAN NOT NULL DEFAULT FALSE;
