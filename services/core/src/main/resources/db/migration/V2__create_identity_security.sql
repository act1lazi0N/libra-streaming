CREATE TABLE identity_accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(10) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE identity_sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_identity_sessions_account ON identity_sessions(account_id, created_at DESC);

CREATE TABLE identity_refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES identity_sessions(id),
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_identity_refresh_session ON identity_refresh_tokens(session_id);

CREATE TABLE identity_email_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    purpose VARCHAR(10) NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_identity_email_account ON identity_email_tokens(account_id, purpose);

CREATE TABLE identity_mail_queue (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    purpose VARCHAR(10) NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    encrypted_payload TEXT,
    state VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD', 'EXPIRED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ
);
CREATE INDEX ix_identity_mail_pending ON identity_mail_queue(available_at, created_at)
    WHERE state IN ('PENDING', 'PROCESSING');

CREATE TABLE identity_rate_limits (
    bucket_key CHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
CREATE INDEX ix_identity_rate_window ON identity_rate_limits(window_start);

CREATE TABLE identity_audit (
    id UUID PRIMARY KEY,
    actor_id UUID,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    action VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_identity_audit_account ON identity_audit(account_id, occurred_at DESC);

CREATE TABLE identity_bootstrap (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
