-- RA02.2: accepted playback facts and per-session totals (docs/analytics-playback-facts-ra02-2.md).

-- One additive fact per accepted heartbeat. (session_id, sequence) is the session's own order; a second event for
-- the same position is a conflict, never a second increment. profile_id becomes NULL when the profile is deleted:
-- aggregate statistics keep the fact, personalization loses the link.
CREATE TABLE playback_facts (
    session_id uuid NOT NULL,
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 9007199254740991),
    event_id uuid NOT NULL UNIQUE,
    account_id uuid NOT NULL,
    profile_id uuid,
    content_id uuid NOT NULL,
    asset_id uuid NOT NULL,
    asset_version bigint NOT NULL CHECK (asset_version > 0),
    position_ms bigint NOT NULL CHECK (position_ms BETWEEN 0 AND 604800000),
    accepted_watched_ms integer NOT NULL CHECK (accepted_watched_ms BETWEEN 0 AND 30000),
    total_watched_ms bigint NOT NULL CHECK (total_watched_ms >= accepted_watched_ms),
    qualified boolean NOT NULL CHECK (qualified = (total_watched_ms >= 30000)),
    state text NOT NULL CHECK (state IN ('PLAYING', 'PAUSED', 'BUFFERING', 'SEEKING', 'ENDED')),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (session_id, sequence)
);
CREATE INDEX ix_playback_facts_profile ON playback_facts (profile_id) WHERE profile_id IS NOT NULL;
CREATE INDEX ix_playback_facts_occurred ON playback_facts (occurred_at);

-- Running totals per session, maintained in the transaction that stores each fact. accepted_watched_ms sums the
-- increments received so far; max_total_watched_ms is Core's cumulative total at the highest sequence received.
-- They are equal once every fact up to max_sequence has arrived, and never added together.
CREATE TABLE playback_sessions (
    session_id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    profile_id uuid,
    content_id uuid NOT NULL,
    fact_count integer NOT NULL CHECK (fact_count > 0),
    accepted_watched_ms bigint NOT NULL CHECK (accepted_watched_ms >= 0),
    max_sequence bigint NOT NULL CHECK (max_sequence > 0),
    max_total_watched_ms bigint NOT NULL CHECK (max_total_watched_ms >= 0),
    qualified_sequence bigint CHECK (qualified_sequence > 0),
    qualified_at timestamptz,
    first_occurred_at timestamptz NOT NULL,
    last_occurred_at timestamptz NOT NULL,
    CHECK ((qualified_sequence IS NULL) = (qualified_at IS NULL)),
    CHECK (fact_count <= max_sequence)
);
CREATE INDEX ix_playback_sessions_profile ON playback_sessions (profile_id) WHERE profile_id IS NOT NULL;
