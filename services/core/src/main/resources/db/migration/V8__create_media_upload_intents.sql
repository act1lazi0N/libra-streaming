CREATE TABLE catalog_upload_intents (
    id UUID PRIMARY KEY,
    creator_account_id UUID NOT NULL REFERENCES identity_accounts(id),
    request_id UUID NOT NULL,
    content_id UUID NOT NULL,
    binding_id UUID NOT NULL UNIQUE,
    expected_version BIGINT NOT NULL CHECK (expected_version > 0),
    catalog_version_at_reservation BIGINT NOT NULL
        CHECK (catalog_version_at_reservation = expected_version + 1),
    byte_length BIGINT NOT NULL CHECK (byte_length BETWEEN 1 AND 268435456),
    expected_sha256 CHAR(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (creator_account_id, request_id),
    FOREIGN KEY (content_id, binding_id) REFERENCES catalog_media_bindings(content_id, id),
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '1 hour')
);
CREATE INDEX ix_catalog_upload_intents_creator ON catalog_upload_intents(creator_account_id, created_at DESC, id);
