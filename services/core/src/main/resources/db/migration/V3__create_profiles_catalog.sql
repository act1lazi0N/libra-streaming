CREATE TABLE profiles (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    name VARCHAR(80) NOT NULL CHECK (length(trim(name)) > 0),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_profiles_account ON profiles(account_id, created_at, id);
CREATE UNIQUE INDEX ux_profiles_default ON profiles(account_id) WHERE is_default;

INSERT INTO profiles(id, account_id, name, is_default)
SELECT gen_random_uuid(), id, display_name, TRUE FROM identity_accounts;
INSERT INTO outbox_events(event_id, topic, event_type, schema_version, aggregate_id,
    aggregate_version, occurred_at, correlation_id, payload)
SELECT gen_random_uuid(), 'core.profiles.v1', 'ProfileCreated', 1, id, 1,
    CURRENT_TIMESTAMP, gen_random_uuid(), jsonb_build_object('accountId', account_id, 'profileId', id)
FROM profiles;

CREATE TABLE catalog_contents (
    id UUID PRIMARY KEY,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('MOVIE', 'SERIES', 'SEASON', 'EPISODE')),
    parent_id UUID REFERENCES catalog_contents(id),
    ordinal INTEGER CHECK (ordinal BETWEEN 1 AND 10000),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    draft_revision BIGINT NOT NULL DEFAULT 1,
    published_revision BIGINT,
    published_at TIMESTAMPTZ,
    candidate_binding UUID,
    active_binding UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((kind IN ('MOVIE', 'SERIES') AND parent_id IS NULL AND ordinal IS NULL)
        OR (kind IN ('SEASON', 'EPISODE') AND parent_id IS NOT NULL AND ordinal IS NOT NULL)),
    UNIQUE(parent_id, ordinal),
    CHECK ((published_revision IS NULL) = (published_at IS NULL))
);
CREATE INDEX ix_catalog_public ON catalog_contents(published_at DESC, id) WHERE published_revision IS NOT NULL;

CREATE TABLE catalog_revisions (
    content_id UUID NOT NULL REFERENCES catalog_contents(id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    search_vector TSVECTOR GENERATED ALWAYS AS
        (to_tsvector('simple', coalesce(metadata->>'title', '') || ' ' || coalesce(metadata->>'description', ''))) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(content_id, revision)
);
CREATE INDEX ix_catalog_search ON catalog_revisions USING GIN(search_vector);
CREATE INDEX ix_catalog_genres ON catalog_revisions USING GIN((metadata->'genres'));
ALTER TABLE catalog_contents ADD CONSTRAINT fk_catalog_draft
    FOREIGN KEY(id, draft_revision) REFERENCES catalog_revisions(content_id, revision) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE catalog_contents ADD CONSTRAINT fk_catalog_published
    FOREIGN KEY(id, published_revision) REFERENCES catalog_revisions(content_id, revision);

CREATE TABLE catalog_media_bindings (
    id UUID PRIMARY KEY,
    content_id UUID NOT NULL REFERENCES catalog_contents(id),
    asset_id UUID NOT NULL,
    asset_version BIGINT NOT NULL CHECK (asset_version > 0),
    projection_version BIGINT NOT NULL DEFAULT 0 CHECK (projection_version >= 0),
    state VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    duration_seconds INTEGER CHECK (duration_seconds BETWEEN 1 AND 604800),
    UNIQUE(asset_id, asset_version),
    UNIQUE(content_id, id),
    CHECK (state <> 'READY' OR duration_seconds IS NOT NULL)
);
ALTER TABLE catalog_contents ADD CONSTRAINT fk_catalog_candidate
    FOREIGN KEY(id, candidate_binding) REFERENCES catalog_media_bindings(content_id, id);
ALTER TABLE catalog_contents ADD CONSTRAINT fk_catalog_active
    FOREIGN KEY(id, active_binding) REFERENCES catalog_media_bindings(content_id, id);

CREATE TABLE media_projection_receipts (
    event_id UUID PRIMARY KEY,
    binding_id UUID NOT NULL REFERENCES catalog_media_bindings(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
