-- V4: email verification, unique emails, optional phone. Additive and safe on a populated database.
--
--  * core.users.phone becomes optional (registration no longer requires a phone number).
--  * core.email_verification_tokens: one row per code emailed to confirm an address. Same shape and
--    rules as password_reset_tokens (V2): only a keyed hash of the code is stored, it expires, works once
--    and allows a bounded number of guesses.
--  * An email address may belong to only one ACTIVE user (case-insensitive). A soft-deleted user releases
--    its address, like its username. If this index fails to build, two active users share an address:
--    the error names it — change one of them, then restart.
--  * The seed admin written by the old DataSeeder carried a placeholder address nobody can read
--    (admin@bejo.id). Login now requires a verified email, so that account is marked verified, and its
--    address becomes the real one when nobody else holds it. Matching the placeholder makes this a
--    one-time change: once the address differs, nothing matches again.

ALTER TABLE core.users ALTER COLUMN phone DROP NOT NULL;

CREATE TABLE IF NOT EXISTS core.email_verification_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP,
    CONSTRAINT fk_email_verification_user FOREIGN KEY (user_id) REFERENCES core.users (id)
);
CREATE INDEX IF NOT EXISTS idx_email_verification_user ON core.email_verification_tokens (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_email_verification_expiry ON core.email_verification_tokens (expires_at);

-- The placeholder seed admin: verified regardless, re-addressed only if the real address is free.
UPDATE core.users
   SET is_email_verified = TRUE
 WHERE email = 'admin@bejo.id' AND business_role = 'SUPER_ADMIN' AND deleted_at IS NULL;

UPDATE core.users
   SET email = 'anton.rifco@gmail.com'
 WHERE email = 'admin@bejo.id' AND business_role = 'SUPER_ADMIN' AND deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM core.users other
                    WHERE lower(other.email) = 'anton.rifco@gmail.com' AND other.deleted_at IS NULL);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_active ON core.users (lower(email)) WHERE deleted_at IS NULL;
