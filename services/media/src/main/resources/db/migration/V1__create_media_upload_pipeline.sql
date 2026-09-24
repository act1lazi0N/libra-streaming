CREATE TABLE media_assets (
    asset_id UUID NOT NULL,
    asset_version BIGINT NOT NULL CHECK (asset_version > 0),
    content_id UUID NOT NULL,
    binding_id UUID NOT NULL UNIQUE,
    aggregate_version BIGINT NOT NULL DEFAULT 0 CHECK (aggregate_version >= 0),
    state VARCHAR(12) NOT NULL DEFAULT 'UPLOADING'
        CHECK (state IN ('UPLOADING', 'QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    selected_source_key VARCHAR(512),
    output_prefix VARCHAR(512),
    master_manifest_key VARCHAR(512),
    duration_seconds INTEGER CHECK (duration_seconds BETWEEN 1 AND 600),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (asset_id, asset_version),
    UNIQUE (asset_id, asset_version, content_id, binding_id),
    UNIQUE (selected_source_key),
    UNIQUE (output_prefix),
    CHECK (selected_source_key IS NULL OR length(trim(selected_source_key)) > 0),
    CHECK (output_prefix IS NULL OR length(trim(output_prefix)) > 0),
    CHECK (master_manifest_key IS NULL OR length(trim(master_manifest_key)) > 0),
    CHECK (state <> 'READY' OR (selected_source_key IS NOT NULL AND output_prefix IS NOT NULL
        AND master_manifest_key IS NOT NULL AND duration_seconds IS NOT NULL)),
    CHECK (state = 'READY' OR duration_seconds IS NULL)
);

CREATE TABLE media_uploads (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    content_id UUID NOT NULL,
    binding_id UUID NOT NULL UNIQUE,
    asset_id UUID NOT NULL,
    asset_version BIGINT NOT NULL CHECK (asset_version > 0),
    byte_length BIGINT NOT NULL CHECK (byte_length BETWEEN 1 AND 268435456),
    expected_sha256 CHAR(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    staging_key VARCHAR(512) NOT NULL UNIQUE CHECK (length(trim(staging_key)) > 0),
    state VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'SUBMITTED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (asset_id, asset_version),
    FOREIGN KEY (asset_id, asset_version, content_id, binding_id)
        REFERENCES media_assets(asset_id, asset_version, content_id, binding_id),
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '1 hour')
);
CREATE INDEX ix_media_uploads_expiry ON media_uploads(expires_at, id) WHERE state = 'OPEN';

CREATE TABLE media_jobs (
    id UUID PRIMARY KEY,
    upload_id UUID NOT NULL UNIQUE REFERENCES media_uploads(id),
    stage VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (stage IN ('QUEUED', 'CLAIMED', 'SOURCE_SELECTED', 'TRANSCODING',
            'FINALIZING', 'SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ix_media_jobs_claim ON media_jobs(next_attempt_at, created_at, id) WHERE stage = 'QUEUED';

CREATE TABLE media_outbox_events (
    event_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL,
    asset_version BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version > 0),
    topic VARCHAR(32) NOT NULL DEFAULT 'media.assets.v1' CHECK (topic = 'media.assets.v1'),
    event_type VARCHAR(40) NOT NULL DEFAULT 'MediaAssetStateChanged'
        CHECK (event_type = 'MediaAssetStateChanged'),
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    correlation_id UUID NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'
        AND octet_length(payload::text) <= 262144),
    delivery_state VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_state IN ('PENDING', 'LEASED', 'SENT')),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    FOREIGN KEY (asset_id, asset_version) REFERENCES media_assets(asset_id, asset_version),
    UNIQUE (asset_id, aggregate_version),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (delivery_state <> 'LEASED' OR lease_token IS NOT NULL),
    CHECK ((delivery_state = 'SENT') = (sent_at IS NOT NULL))
);
CREATE INDEX ix_media_outbox_claim ON media_outbox_events(next_attempt_at, created_at, event_id)
    WHERE delivery_state = 'PENDING';
