-- RA05.1: per-profile watched time for genre interests (docs/analytics-preferences-ra05-1.md).
--
-- One row per profile, watched item (movie or episode) and UTC day of the fact's occurredAt, summing the accepted
-- increments (acceptedWatchedMs) only. The item is kept as watched, not as its movie/series: an episode may arrive
-- before its season and series, so the title and its genres are resolved from the catalog when interests are read.
-- account_id is the owner the facts carried; a query for another account finds nothing.
--
-- Maintained in the transaction that stores each playback fact, and only while the fact keeps its profile link:
-- a tombstone deletes the profile's rows in its own transaction (personalization, RA01.1), and a later fact for a
-- deleted profile is stored without a link and never reaches this table.
CREATE TABLE profile_watch_days (
    profile_id uuid NOT NULL,
    content_id uuid NOT NULL,
    day date NOT NULL,
    account_id uuid NOT NULL,
    accepted_watched_ms bigint NOT NULL CHECK (accepted_watched_ms > 0),
    PRIMARY KEY (profile_id, content_id, day)
);

-- Facts stored before this migration keep their profile link only while the profile is live, so they are rolled up
-- once with the same day rule as the Java code: the UTC calendar date of the stored instant. Core never moves a
-- profile between accounts; should facts disagree, only the account of the profile's earliest fact is kept (the live
-- path keeps the first writer of each row, see JdbcPreferenceStore). Either way no account reads another's data.
INSERT INTO profile_watch_days (profile_id, content_id, day, account_id, accepted_watched_ms)
SELECT profile_id, content_id, day, owner, sum(accepted_watched_ms) FILTER (WHERE account_id = owner)
FROM (
    SELECT profile_id, content_id, (occurred_at AT TIME ZONE 'UTC')::date AS day, account_id, accepted_watched_ms,
        first_value(account_id) OVER (PARTITION BY profile_id ORDER BY occurred_at, session_id, sequence) AS owner
    FROM playback_facts
    WHERE profile_id IS NOT NULL AND accepted_watched_ms > 0
) facts
GROUP BY profile_id, content_id, day, owner
HAVING sum(accepted_watched_ms) FILTER (WHERE account_id = owner) > 0;
