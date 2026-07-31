-- Transaction authorship audit + the dashboard-summary index on commerce.bejo_history.
--
-- EVERY statement here is idempotent (IF NOT EXISTS) BY DESIGN. This file is re-applied
-- deliberately: when it changes, the deploy procedure deletes its flyway_schema_history row so
-- Flyway runs it again and records the new checksum, instead of incrementing to a new version.
-- Re-running must therefore always be a safe no-op on a database that already has these
-- objects. See "Re-applying V2" in CLAUDE.md before editing.
--
-- 1) Authorship: which account/sub-account created and last edited a MANUAL/PARSED transaction.
--    Both nullable — existing rows and server-recorded MANAGED callback rows have no human author.
ALTER TABLE commerce.bejo_history ADD COLUMN IF NOT EXISTS created_by VARCHAR(50);
ALTER TABLE commerce.bejo_history ADD COLUMN IF NOT EXISTS updated_by VARCHAR(50);

-- 2) Composite index behind GET /api/transactions/summary and the paginated history list, which
--    both filter by (business_id, transaction_date) with deleted_at IS NULL. V1 indexes
--    business_id and transaction_date SEPARATELY, so Postgres scans an index range covering
--    EVERY merchant's transactions in the period and ANDs it down — a cost that grows with total
--    table volume rather than with the merchant's own activity. Measured on 300k rows / 300
--    businesses, the monthly aggregate read 42,567 index entries to return 142 rows; with this
--    index it reads 142.
--
--    PARTIAL (deleted_at IS NULL) because every one of those queries excludes soft-deleted rows.
--
--    Deliberately NOT "CONCURRENTLY": Flyway runs each Postgres migration inside a transaction and
--    CREATE INDEX CONCURRENTLY cannot run in one. The plain form takes a SHARE lock that blocks
--    writes (reads are unaffected) while it builds — measured at ~1.2s for 2,000,000 rows (29 MB
--    index) on Postgres 17, proportionally less below that — and it rolls back cleanly on failure
--    instead of leaving an invalid index behind.
CREATE INDEX IF NOT EXISTS idx_bejo_history_business_date
    ON commerce.bejo_history (business_id, transaction_date)
    WHERE deleted_at IS NULL;

-- 3) QRIS merchant identity, parsed from the code at upload time.
--    The app captions the QR with a merchant name + NMID, and those must describe the QR actually
--    on screen: a sub-account with its own QRIS has a different merchant identity from the
--    business's shared one. Both are read out of the EMV payload (tag 59, and sub-tag 02 of the
--    ID.CO.QRIS.WWW template) rather than from manually-entered business info, so they cannot
--    drift from the QR they label. NMID therefore lives per-QRIS here; businesses.nmid is no
--    longer editable through business info.
ALTER TABLE core.business_qris ADD COLUMN IF NOT EXISTS qris_merchant_name VARCHAR(255);
ALTER TABLE core.business_qris ADD COLUMN IF NOT EXISTS nmid               VARCHAR(50);
ALTER TABLE core.user_qris     ADD COLUMN IF NOT EXISTS qris_merchant_name VARCHAR(255);
ALTER TABLE core.user_qris     ADD COLUMN IF NOT EXISTS nmid               VARCHAR(50);
ALTER TABLE core.business_qris ADD COLUMN IF NOT EXISTS acquirer           VARCHAR(100);
ALTER TABLE core.user_qris     ADD COLUMN IF NOT EXISTS acquirer           VARCHAR(100);
