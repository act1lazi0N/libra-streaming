-- RA02.3: records parked by the consumers, captured from the Analytics dead-letter topic
-- (docs/analytics-dead-letters-ra02-3.md). Rows are private operational data: they may hold the original payload.
CREATE TABLE dead_letters (
    id uuid PRIMARY KEY,
    dlt_partition integer NOT NULL CHECK (dlt_partition >= 0),
    dlt_offset bigint NOT NULL CHECK (dlt_offset >= 0),
    source_topic text CHECK (source_topic IN ('core.catalog.v1', 'core.profiles.v1', 'core.playback.v1')),
    source_partition integer CHECK (source_partition >= 0),
    source_offset bigint CHECK (source_offset >= 0),
    event_key text CHECK (event_key IS NULL OR length(event_key) <= 128),
    event_body text CHECK (event_body IS NULL OR octet_length(event_body) <= 262144),
    -- The parked record's own reason; redrive attempts never overwrite it.
    reason text NOT NULL CHECK (reason IN ('SCHEMA_INVALID', 'SEMANTIC_INVALID', 'EVENT_CONFLICT', 'RETRY_EXHAUSTED',
        'ORIGIN_UNAVAILABLE', 'PAYLOAD_UNAVAILABLE')),
    state text NOT NULL DEFAULT 'HELD' CHECK (state IN ('HELD', 'REDRIVEN', 'UNREDRIVABLE')),
    redrive_attempts integer NOT NULL DEFAULT 0 CHECK (redrive_attempts >= 0),
    last_redrive_code text CHECK (last_redrive_code IS NULL OR last_redrive_code ~ '^[A-Z_:]{1,80}$'),
    received_at timestamptz NOT NULL,
    redriven_at timestamptz,
    UNIQUE (dlt_partition, dlt_offset),
    CHECK ((source_partition IS NULL) = (source_offset IS NULL)),
    CHECK ((state = 'REDRIVEN') = (redriven_at IS NOT NULL)),
    -- Only a record with its origin and payload can be redriven.
    CHECK (state <> 'HELD' OR (source_topic IS NOT NULL AND event_body IS NOT NULL))
);
CREATE INDEX ix_dead_letters_held ON dead_letters (received_at, id) WHERE state = 'HELD';
-- A source record is parked once even if a slow broker acknowledged a dead-letter send the consumer retried.
CREATE UNIQUE INDEX ux_dead_letters_source ON dead_letters (source_topic, source_partition, source_offset)
    WHERE source_topic IS NOT NULL;
