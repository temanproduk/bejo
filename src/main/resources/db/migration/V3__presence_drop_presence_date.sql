-- ── Drop presences.presence_date ─────────────────────────────────────────────
-- It was pure duplication: the calendar day is always CAST(clock_datetime AS DATE), so the
-- column added a second copy of the same fact that could drift out of step with it. The two
-- things it was carrying are kept without it:
--   * the once-per-day clock rule -> a unique index on the expression;
--   * the month/year filter + history sort -> an index on clock_datetime.
-- The API still returns presenceDate; it is now derived from clockDatetime.

-- Recreate the daily-clock guard against the expression instead of the column. (PINGs stay
-- excluded: the super admin may ping the same member more than once in a day.)
CREATE UNIQUE INDEX uq_presence_clock_daily_v3
    ON presences (user_id, type, (CAST(clock_datetime AS DATE)))
    WHERE type IN ('CLOCK_IN', 'CLOCK_OUT');

-- Dropping the column also drops uq_presence_clock_daily and the two indexes keyed on it.
ALTER TABLE presences DROP COLUMN presence_date;

ALTER INDEX uq_presence_clock_daily_v3 RENAME TO uq_presence_clock_daily;

CREATE INDEX idx_presences_business_clock ON presences (business_id, clock_datetime DESC);
CREATE INDEX idx_presences_user_clock     ON presences (business_id, user_id, clock_datetime DESC);
