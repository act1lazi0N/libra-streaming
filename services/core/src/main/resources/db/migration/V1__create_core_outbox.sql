CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(100) NOT NULL
        CHECK (topic IN ('core.catalog.v1', 'core.playback.v1', 'core.profiles.v1')),
    event_type VARCHAR(100) NOT NULL CHECK (event_type ~ '^[A-Za-z][A-Za-z0-9.]{0,99}$'),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT uk_outbox_aggregate_event UNIQUE (topic, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX ix_outbox_pending ON outbox_events (created_at, event_id)
    WHERE published_at IS NULL;
