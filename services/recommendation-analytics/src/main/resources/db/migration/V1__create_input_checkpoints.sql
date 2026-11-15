-- Confirmed processing checkpoint per consumed Core topic (docs/analytics-contracts-ra01-1.md, "Freshness").
-- A NULL confirmed_at means no checkpoint has been confirmed yet: answers that depend on the topic are not ready.
CREATE TABLE input_checkpoints (
    topic text PRIMARY KEY
        CHECK (topic IN ('core.catalog.v1', 'core.profiles.v1', 'core.playback.v1')),
    confirmed_at timestamptz
);

INSERT INTO input_checkpoints (topic)
VALUES ('core.catalog.v1'), ('core.profiles.v1'), ('core.playback.v1');
