-- V3: the admin dashboard's sign-in (dashboard.bejo.temanproduk.com). Additive; touches no existing table.
--
-- Admins have no password and no row in core.users: an address on the ADMIN_EMAILS allow-list signs in
-- with a one-time code sent to it by email.
--
--  * admin.admin_otp_codes: one row per emailed code. Only a keyed hash of the code is stored, plus an
--    attempt counter and a single-use marker (the same design as core.password_reset_tokens).
--  * admin.admin_sessions: one row per signed-in browser. The bearer token is random and only its
--    SHA-256 is stored, so a database leak does not yield usable sessions; a row can be revoked.
CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE IF NOT EXISTS admin.admin_otp_codes (
    id          BIGSERIAL     PRIMARY KEY,
    email       VARCHAR(255)  NOT NULL,
    code_hash   VARCHAR(64)   NOT NULL,
    attempts    INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP     NOT NULL,
    used_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_admin_otp_email ON admin.admin_otp_codes (email, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_otp_expiry ON admin.admin_otp_codes (expires_at);

CREATE TABLE IF NOT EXISTS admin.admin_sessions (
    id          BIGSERIAL     PRIMARY KEY,
    token_hash  VARCHAR(64)   NOT NULL,
    email       VARCHAR(255)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP     NOT NULL,
    revoked_at  TIMESTAMP,
    CONSTRAINT uq_admin_session_token UNIQUE (token_hash)
);
CREATE INDEX IF NOT EXISTS idx_admin_session_expiry ON admin.admin_sessions (expires_at);
