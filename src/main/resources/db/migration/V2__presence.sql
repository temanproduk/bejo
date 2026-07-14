-- ── Employee daily presence (attendance) ─────────────────────────────────────
-- One row per attendance entry: a member's CLOCK_IN / CLOCK_OUT (with the location and
-- the client-supplied time of the entry), or a PING the super admin sent that member.
-- username/name snapshot the member so the history survives a rename or a soft delete;
-- user_id is a plain reference (no FK) for the same reason.
--
-- clock_datetime is the moment the entry is about (the ping's send time for a PING);
-- presence_date is its calendar day, which the month-year filter ranges over.
-- response_datetime is set only when a member acknowledges their PING from the app.
CREATE TABLE presences (
    id                BIGSERIAL        PRIMARY KEY,
    business_id       BIGINT           NOT NULL,
    user_id           BIGINT           NOT NULL,
    username          VARCHAR(50)      NOT NULL,   -- same width as users.username
    name              VARCHAR(255)     NOT NULL,
    type              VARCHAR(20)      NOT NULL,   -- CLOCK_IN | CLOCK_OUT | PING
    clock_datetime    TIMESTAMP        NOT NULL,
    presence_date     DATE             NOT NULL,
    latitude          DOUBLE PRECISION,            -- null on a PING (admin sends no location)
    longitude         DOUBLE PRECISION,
    note              VARCHAR(255),
    response_datetime TIMESTAMP,                   -- PING only: when the member tapped it
    created_at        TIMESTAMP        NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_presence_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE INDEX idx_presences_business_date ON presences (business_id, presence_date DESC);
CREATE INDEX idx_presences_user_date     ON presences (business_id, user_id, presence_date DESC);

-- A member clocks in at most once and out at most once per calendar day. The service
-- checks this first (to return a friendly 400), but two simultaneous taps could both pass
-- that check — so the rule is also enforced here. PINGs are excluded: the super admin may
-- ping the same member repeatedly in a day.
CREATE UNIQUE INDEX uq_presence_clock_daily
    ON presences (user_id, type, presence_date)
    WHERE type IN ('CLOCK_IN', 'CLOCK_OUT');
