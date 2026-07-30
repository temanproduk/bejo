-- Transaction authorship audit: track which account/sub-account created and last edited a
-- MANUAL/PARSED transaction. Additive, applied on top of the V1 baseline (production is live
-- and never dropped, so schema changes are versioned migrations from here on).
--
-- Both columns are nullable: existing rows and server-recorded MANAGED callback rows have no
-- human author. Idempotent (IF NOT EXISTS) so a re-run is a no-op.
ALTER TABLE commerce.bejo_history ADD COLUMN IF NOT EXISTS created_by VARCHAR(50);
ALTER TABLE commerce.bejo_history ADD COLUMN IF NOT EXISTS updated_by VARCHAR(50);
