CREATE TABLE subscriptions (
    account_id UUID PRIMARY KEY REFERENCES identity_accounts(id),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Retain receipts and account-scoped keys together: retries never extend twice.
CREATE TABLE subscription_purchases (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES identity_accounts(id),
    idempotency_key UUID NOT NULL,
    plan VARCHAR(32) NOT NULL CHECK (plan = 'PREMIUM_30_DAYS'),
    simulated BOOLEAN NOT NULL CHECK (simulated),
    purchased_at TIMESTAMPTZ NOT NULL,
    term_start TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (account_id, idempotency_key),
    CHECK (term_start >= purchased_at),
    CHECK (expires_at = term_start + INTERVAL '720 hours')
);
CREATE INDEX ix_subscription_purchases_history
    ON subscription_purchases(account_id, purchased_at DESC, id DESC);
