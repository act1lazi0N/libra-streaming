-- RA04.1: daily platform statistics (docs/analytics-statistics-ra04-1.md).
--
-- One row per UTC day and account that has a qualified view or accepted watched time on that day. A per-day count
-- of distinct viewers could not give the distinct viewers of a range (a viewer active on two days would count
-- twice), so the account itself is kept: the unique viewers of [from, to] are the distinct accounts with a view on
-- any day of the range. No profile is stored: deleting a profile changes nothing here (RA01.1, platform totals).
--
-- Maintained in the transaction that stores each playback fact:
--   accepted_watched_ms  += the fact's acceptedWatchedMs, on the UTC date of its occurredAt;
--   qualified_views      +1 on the UTC date of the session's qualifying event with the lowest sequence, and -1 on
--                        the date it held before when a late event with a lower sequence moves it.
CREATE TABLE statistics_account_days (
    day date NOT NULL,
    account_id uuid NOT NULL,
    qualified_views bigint NOT NULL CHECK (qualified_views >= 0),
    accepted_watched_ms bigint NOT NULL CHECK (accepted_watched_ms >= 0),
    PRIMARY KEY (day, account_id)
);

-- Facts stored before this migration (RA02) are rolled up once, with the same day rule as the Java code:
-- the UTC calendar date of the instant.
INSERT INTO statistics_account_days (day, account_id, qualified_views, accepted_watched_ms)
SELECT day, account_id, sum(views), sum(watched_ms)
FROM (
    SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, account_id, 0 AS views, accepted_watched_ms AS watched_ms
    FROM playback_facts
    UNION ALL
    SELECT (qualified_at AT TIME ZONE 'UTC')::date, account_id, 1, 0
    FROM playback_sessions
    WHERE qualified_at IS NOT NULL
) contributions
GROUP BY day, account_id
HAVING sum(views) > 0 OR sum(watched_ms) > 0;
