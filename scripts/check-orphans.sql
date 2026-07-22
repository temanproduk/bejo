-- Orphan detection for the commerce → core soft reference (business_id).
--
-- After the modular-monolith split the commerce tables live in the `commerce` schema and reference
-- `core.businesses(id)` by a plain business_id column with NO foreign key, so the database no longer
-- rejects a row whose business no longer exists. business_id is always assigned server-side from
-- BusinessContext and businesses are never hard-deleted, so an orphan can only come from a code bug
-- — this query surfaces one if it ever happens.
--
-- Usage (production): psql "$DB_URL" -f scripts/check-orphans.sql
-- Any non-zero count is an integrity problem to investigate. The same query runs in the test suite
-- (OrphanDetectionTest) so the detection logic itself is covered on CI.

SELECT 'bejo_history'      AS table_name, COUNT(*) AS orphan_count
FROM commerce.bejo_history      h WHERE NOT EXISTS (SELECT 1 FROM core.businesses b WHERE b.id = h.business_id)
UNION ALL
SELECT 'products',          COUNT(*)
FROM commerce.products          p WHERE NOT EXISTS (SELECT 1 FROM core.businesses b WHERE b.id = p.business_id)
UNION ALL
SELECT 'product_categories', COUNT(*)
FROM commerce.product_categories c WHERE NOT EXISTS (SELECT 1 FROM core.businesses b WHERE b.id = c.business_id)
UNION ALL
SELECT 'stock_mutations',   COUNT(*)
FROM commerce.stock_mutations   m WHERE NOT EXISTS (SELECT 1 FROM core.businesses b WHERE b.id = m.business_id);
