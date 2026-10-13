-- V2: password reset by emailed code. Additive and safe on a populated database.
--
--  * core.password_reset_tokens: one row per code that was emailed. The code itself is never stored,
--    only a keyed hash (so a database leak does not reveal live codes), plus an attempt counter and
--    a single-use marker.
--  * core.users.email_changed_at: when the email address last changed. A reset code is not sent to
--    an address that was swapped in recently, so a stolen login session cannot change the email and
--    then reset the password to lock the owner out.
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS email_changed_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS core.password_reset_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP,
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES core.users (id)
);
CREATE INDEX IF NOT EXISTS idx_password_reset_user ON core.password_reset_tokens (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_password_reset_expiry ON core.password_reset_tokens (expires_at);
