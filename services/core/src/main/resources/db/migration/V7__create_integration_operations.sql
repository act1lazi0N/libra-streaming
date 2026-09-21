ALTER TABLE outbox_events
    ADD COLUMN delivery_state VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (delivery_state IN ('PENDING', 'SENT', 'PARKED')),
    ADD COLUMN lease_id UUID,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_error_code VARCHAR(40),
    ADD COLUMN delivery_version BIGINT NOT NULL DEFAULT 1;
UPDATE outbox_events SET delivery_state = 'SENT' WHERE published_at IS NOT NULL;
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_delivery_state CHECK ((delivery_state = 'SENT') = (published_at IS NOT NULL));
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_lease CHECK ((lease_id IS NULL) = (lease_until IS NULL));
CREATE INDEX ix_outbox_delivery ON outbox_events(next_attempt_at, created_at, event_id) WHERE delivery_state = 'PENDING';

CREATE TABLE media_dead_letters (
    id UUID PRIMARY KEY,
    dlt_partition INTEGER NOT NULL CHECK (dlt_partition >= 0),
    dlt_offset BIGINT NOT NULL CHECK (dlt_offset >= 0),
    source_partition INTEGER,
    source_offset BIGINT,
    event_key VARCHAR(128),
    event_body TEXT,
    event_id UUID,
    payload_available BOOLEAN NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivery_state VARCHAR(10) NOT NULL DEFAULT 'HELD' CHECK (delivery_state IN ('HELD', 'PENDING', 'SENT', 'PARKED')),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(40),
    delivery_version BIGINT NOT NULL DEFAULT 1,
    redriven_at TIMESTAMPTZ,
    UNIQUE(dlt_partition, dlt_offset),
    CHECK ((lease_id IS NULL) = (lease_until IS NULL)),
    CHECK (event_body IS NULL OR octet_length(event_body) <= 262144),
    CHECK ((source_partition IS NULL) = (source_offset IS NULL)),
    CHECK (source_partition IS NULL OR (source_partition >= 0 AND source_offset >= 0))
);
CREATE INDEX ix_media_dlt_delivery ON media_dead_letters(next_attempt_at, created_at, id) WHERE delivery_state = 'PENDING';
CREATE INDEX ix_media_dlt_page ON media_dead_letters(received_at DESC, id);

CREATE TABLE integration_operation_audit (
    id UUID PRIMARY KEY,
    administrator_id UUID NOT NULL REFERENCES identity_accounts(id),
    request_id UUID NOT NULL,
    action VARCHAR(30) NOT NULL CHECK (action IN ('OUTBOX_RETRY', 'MEDIA_DLT_REDRIVE')),
    target_id UUID NOT NULL,
    expected_version BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(administrator_id, request_id)
);
CREATE INDEX ix_integration_audit_page ON integration_operation_audit(created_at DESC, id);
