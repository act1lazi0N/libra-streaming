-- RA02.4: position of each bootstrap generation per source (docs/analytics-bootstrap-ra02-4.md). A new generation
-- reads everything again; the version rules and event receipts make the second pass a no-op on unchanged data.
CREATE TABLE bootstrap_progress (
    generation bigint NOT NULL CHECK (generation > 0),
    source text NOT NULL CHECK (source IN ('PROFILES', 'CATALOG', 'PLAYBACK')),
    cursor text CHECK (cursor IS NULL OR length(cursor) <= 64),
    applied bigint NOT NULL DEFAULT 0 CHECK (applied >= 0),
    rejected bigint NOT NULL DEFAULT 0 CHECK (rejected >= 0),
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (generation, source)
);
