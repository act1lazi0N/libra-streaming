CREATE TABLE playback_sessions (
    id UUID PRIMARY KEY,
    opened_order BIGSERIAL UNIQUE NOT NULL,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    auth_session_id UUID NOT NULL REFERENCES identity_sessions(id),
    content_id UUID NOT NULL REFERENCES catalog_contents(id),
    binding_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    asset_version BIGINT NOT NULL CHECK (asset_version > 0),
    published_revision BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    opened_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_progress_at TIMESTAMPTZ NOT NULL,
    sequence BIGINT NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    position_ms BIGINT NOT NULL DEFAULT 0 CHECK (position_ms >= 0 AND position_ms <= duration_ms),
    watched_ms BIGINT NOT NULL DEFAULT 0 CHECK (watched_ms >= 0),
    state VARCHAR(10) NOT NULL DEFAULT 'PAUSED'
        CHECK (state IN ('PLAYING', 'PAUSED', 'BUFFERING', 'SEEKING', 'ENDED')),
    FOREIGN KEY(content_id, binding_id) REFERENCES catalog_media_bindings(content_id, id),
    CHECK (expires_at > opened_at)
);
CREATE INDEX ix_playback_profile_content ON playback_sessions(profile_id, content_id);
CREATE INDEX ix_playback_auth_session ON playback_sessions(auth_session_id);

CREATE TABLE watch_history (
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    content_id UUID NOT NULL REFERENCES catalog_contents(id),
    latest_session_id UUID NOT NULL REFERENCES playback_sessions(id) ON DELETE CASCADE,
    position_ms BIGINT NOT NULL CHECK (position_ms >= 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0 AND position_ms <= duration_ms),
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(profile_id, content_id)
);
CREATE INDEX ix_history_updated ON watch_history(profile_id, updated_at DESC, content_id);
CREATE UNIQUE INDEX ux_history_latest_session ON watch_history(latest_session_id);
