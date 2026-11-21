-- RA02.1: event receipts plus the versioned catalog and profile read models (docs/analytics-ingestion-ra02-1.md).

-- One row per applied Core event. The receipt and the projection change commit in one transaction, so a redelivered
-- event finds its receipt and changes nothing. The identity columns detect an event ID reused for another event.
CREATE TABLE processed_events (
    event_id uuid PRIMARY KEY,
    topic text NOT NULL CHECK (topic IN ('core.catalog.v1', 'core.profiles.v1', 'core.playback.v1')),
    event_type text NOT NULL CHECK (event_type ~ '^[A-Za-z][A-Za-z0-9.]{0,99}$'),
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    processed_at timestamptz NOT NULL
);

-- Latest known state per catalog node. The parent is not a foreign key: a child may arrive before its parent
-- (Core gives no order across aggregates), and visibility is resolved along the chain when it is read.
CREATE TABLE catalog_nodes (
    content_id uuid PRIMARY KEY,
    version bigint NOT NULL CHECK (version > 0),
    kind text NOT NULL CHECK (kind IN ('MOVIE', 'SERIES', 'SEASON', 'EPISODE')),
    parent_id uuid,
    ordinal integer CHECK (ordinal > 0),
    -- The published revision and active binding exactly as Core sent them; the columns below are copies for queries.
    published jsonb CHECK (published IS NULL OR jsonb_typeof(published) = 'object'),
    active jsonb CHECK (active IS NULL OR jsonb_typeof(active) = 'object'),
    genres text[] NOT NULL DEFAULT '{}',
    access_tier text CHECK (access_tier IN ('FREE', 'PREMIUM')),
    active_state text CHECK (active_state IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    duration_seconds integer CHECK (duration_seconds BETWEEN 1 AND 604800),
    -- Time of the latest publication and the version of the CatalogPublished event (or snapshot) that gave it.
    -- A late CatalogPublished with a higher such version still supplies it; see JdbcCatalogStore.
    published_at timestamptz,
    published_at_version bigint CHECK (published_at_version > 0),
    changed_at timestamptz NOT NULL,
    CHECK ((kind IN ('MOVIE', 'SERIES')) = (parent_id IS NULL)),
    CHECK ((parent_id IS NULL) = (ordinal IS NULL)),
    CHECK (published IS NOT NULL OR (active IS NULL AND published_at IS NULL AND access_tier IS NULL
        AND cardinality(genres) = 0)),
    CHECK ((active IS NULL) = (active_state IS NULL)),
    CHECK ((published_at IS NULL) = (published_at_version IS NULL)),
    CHECK (published_at_version <= version)
);
CREATE INDEX ix_catalog_nodes_parent ON catalog_nodes (parent_id) WHERE parent_id IS NOT NULL;

-- Latest lifecycle state per profile. deleted = true is a terminal tombstone: no later or replayed event revives it.
CREATE TABLE profiles (
    profile_id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    deleted boolean NOT NULL,
    changed_at timestamptz NOT NULL
);
