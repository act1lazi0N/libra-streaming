CREATE TABLE watchlist_entries (
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    content_id UUID NOT NULL REFERENCES catalog_contents(id),
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(profile_id, content_id)
);
CREATE INDEX ix_watchlist_page ON watchlist_entries(profile_id, added_at DESC, content_id);

-- Retain moderation after author deletion; erase the deleted stars and text.
CREATE TABLE reviews (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    content_id UUID NOT NULL REFERENCES catalog_contents(id),
    stars SMALLINT CHECK (stars BETWEEN 1 AND 5),
    text VARCHAR(2000),
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(account_id, content_id),
    CHECK ((deleted AND stars IS NULL AND text IS NULL) OR (NOT deleted AND stars IS NOT NULL))
);
CREATE INDEX ix_reviews_public ON reviews(content_id, updated_at DESC, id) WHERE NOT hidden AND NOT deleted;
CREATE INDEX ix_reviews_admin ON reviews(updated_at DESC, id);
CREATE INDEX ix_playback_qualified_account ON playback_sessions(account_id, content_id) WHERE watched_ms >= 30000;

CREATE TABLE review_reports (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(id),
    reporter_id UUID NOT NULL REFERENCES identity_accounts(id),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    review_version BIGINT NOT NULL CHECK (review_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(review_id, reporter_id)
);
CREATE INDEX ix_review_reports_page ON review_reports(review_id, created_at DESC, id);

CREATE TABLE review_moderation_audit (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES reviews(id),
    administrator_id UUID NOT NULL REFERENCES identity_accounts(id),
    action VARCHAR(10) NOT NULL CHECK (action IN ('HIDDEN', 'VISIBLE')),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    previous_hidden BOOLEAN NOT NULL,
    review_version BIGINT NOT NULL CHECK (review_version > 0),
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(review_id, review_version)
);
CREATE INDEX ix_review_audit_page ON review_moderation_audit(review_id, review_version DESC);
