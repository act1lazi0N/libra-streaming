# Analytics: Daily Statistics (Milestone RA04.1)

Verification date: 2026-11-17.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA04.1 computes the three platform statistics of [RA01.1](analytics-contracts-ra01-1.md#statistics-semantics-v1-statistics-endpoint)
per UTC day from the playback facts of [RA02.2](analytics-playback-facts-ra02-2.md): qualified views, distinct
accounts and accepted watched duration. It keeps enough data to count unique viewers over a whole date range.
Serving them through the V1 API is [RA04.2](analytics-statistics-api-ra04-2.md).

Done condition (plan): one session has at most one qualified view; the unique viewers of a range are not the sum of
the daily unique viewers.

Contract and migration changes:

- No contract changed.
- New migration `V6__create_statistics_account_days.sql`: table `statistics_account_days` and a one-time backfill
  of the facts stored before it.

## Design

**One row per UTC day and account.** `statistics_account_days(day, account_id, qualified_views,
accepted_watched_ms)`, primary key `(day, account_id)`. A daily count of distinct viewers cannot be combined into a
range: a viewer active on two days would count twice. Keeping the account per day lets the range query count
`DISTINCT account_id` over every day of the window. No profile is stored, so several profiles of one account are
one viewer, and deleting a profile changes nothing here. These are platform totals, as RA01.1 states.

The row count is bounded by active accounts per day. There is no platform-wide hot row: every heartbeat updates the
row of its own account and day.

**Maintained in the intake transaction.** `PlaybackProjection.apply` stores the fact, then calls
`PlatformStatistics.record` in the same transaction as the receipt (`EventIntake`). A redelivered event is stopped by
the receipt before either step. A rolled-back transaction leaves the facts and the rollup unchanged together.

| Statistic | Rule | Row it changes |
| --- | --- | --- |
| `acceptedWatchedMs` | `+acceptedWatchedMs` of the fact; `totalWatchedMs` is never added | UTC date of the fact's `occurredAt` |
| `qualifiedViews` | `+1` when the session first qualifies | UTC date of the qualifying event with the lowest sequence |
| `qualifiedViews` | `-1` / `+1` when a late event with a lower qualifying sequence moves the view | old date / new date |

`playback_sessions` already keeps the session's lowest qualifying sequence and its instant (RA02.2). The session
upsert now returns that instant before and after the statement with PostgreSQL 18's `RETURNING old.qualified_at,
new.qualified_at`. Both values are read under the row lock the upsert takes, so a concurrent writer of the same
session cannot slip between the read and the update. `DayContribution.of` turns the fact into per-day changes,
merges changes on the same day (a move within one day is no change), drops empty ones and sorts them by day. The
store applies them in that order, so two transactions touching the same account always lock its rows in the same
order.

A `-1` goes through `UPDATE` with a guard, never through the upsert. PostgreSQL checks the `CHECK` constraints of
a proposed row before it detects the conflict, so a negative value in the upsert would fail even when the row
exists. If the row to move the view from is missing, the update changes no row and the store throws. The intake
transaction rolls back and the record is retried, then parked: the totals are never corrupted silently. A row whose
two values both fall to zero is deleted.

**The day rule** is the calendar date of the instant in UTC (`DayContribution.dayOf`), never the server's zone.
`occurredAt` is first truncated to microseconds (`PlaybackProjection.storedInstant`), the precision PostgreSQL keeps.
The stored fact, the session's qualifying instant and the day its watched time goes to all use that value.
Otherwise PgJDBC would round `23:59:59.9999995Z` up to the next midnight, putting the view and the watched time
of one fact on different days (found in review, test `anInstantPostgresqlWouldRoundIntoTheNextDay…`). The V6
backfill uses `(instant AT TIME ZONE 'UTC')::date` on those stored values, which gives the same date.

**Deploying V6.** The backfill reads the facts in one snapshot. An older Analytics build still consuming playback
would commit facts after that snapshot without rollup rows. Stop every Analytics instance (or at least its playback
consumer) before the first instance with V6 starts. The backfill runs under the database socket timeout
(`RECOMMENDATION_DB_SOCKET_TIMEOUT_SECONDS`, 10 s); a larger `playback_facts` table needs a higher value for that
start.

**Reading.** `JdbcStatisticsStore.totals(window)` is one statement over `day BETWEEN from AND to`:
`sum(qualified_views)`, `count(DISTINCT account_id) FILTER (WHERE qualified_views > 0)` and
`sum(accepted_watched_ms)`. One statement reads one snapshot, so the three values are mutually consistent.
`StatisticsTotals` refuses negative values and more viewers than views. `days(window)` returns one entry per day of
the window, empty days included. V1 does not serve it; it is the base for the per-day API of RA04.3.

**The window** (`StatisticsWindow`) is the inclusive range `[from, to]`, `from <= to`, at most 31 days.
`requested(from, to, now)` also refuses a `to` after the UTC date of `now + 5 s`, the skew margin of RA01.1.

## Verification

Unit tests (no database): `DayContributionTest` (10: UTC day rule including a +07:00 instant, watch time on
`occurredAt`, first view, move to an earlier day as `-1`/`+1` in day order, move within one day is no change, merge,
a view never disappears), `StatisticsWindowTest` (9: order, 31/32 days, "today" with the 5 s margin at 23:59:54Z /
23:59:55Z, fixed messages), `StatisticsTotalsTest` (5), `PlatformStatisticsTest` (4: empty contributions write
nothing; no checkpoint means no totals query; the checkpoint is read before the totals).

`StatisticsRollupIntegrationTest` (17 tests, PostgreSQL 18, real decoder and intake) covers:
- several qualifying heartbeats are one view; a session over UTC midnight splits watched time by event and keeps
  the view on the day of its lowest qualifying sequence;
- a late lower sequence moves the view and deletes the emptied row;
- two profiles of one account on two days give per-day uniques 1 and 1 but 1 over the range, with 2 views;
- a redelivered event and a real bootstrap replay change nothing; `ProfileDeleted` keeps the statistics;
- `days()` includes empty days;
- three seeded random schedules (60 events, 4 accounts, 6 profiles, 15 sessions, 4 days, shuffled, a guaranteed
  move across midnight) where every stored row equals the V6 recomputation from facts and sessions, and every
  sub-window's totals and daily figures equal it;
- the V6 backfill on a database migrated to version 5 (Flyway `target`) with legacy rows;
- `append` returning the qualifying instant before and after;
- the `CHECK` constraint;
- added after review: two sessions of one account moving their views across the same two days on two threads at
  once (10 rounds, no deadlock, reconciled); two qualifying facts of one session at once (one view, on the lower
  sequence's day); a view move without its rollup row rolls back the receipt, the fact and the session update
  together; a fact at `23:59:59.9999995Z` keeps view and watched time on one day.

**Mutation check** (scratchpad driver: each mutant applied to the source, a named suite run, the file restored;
KILLED only on a test failure, never on a compile error). All of RA04.1–RA04.2's mutants were killed; the ones
of this milestone:

| Mutant | Killed by |
| --- | --- |
| `count(account_id)` instead of `count(DISTINCT account_id)` | `StatisticsRollupIntegrationTest` |
| a moved view never leaves its old day | `StatisticsRollupIntegrationTest` |
| `occurredAt` not truncated to microseconds | `StatisticsRollupIntegrationTest` |
| totals read before the checkpoint | `PlatformStatisticsTest` |

The repository gate is in [RA04.2, Gate](analytics-statistics-api-ra04-2.md#gate).

## Limits

- V1 serves totals only; per-day and per-content figures need the new contract version of RA04.3. The rollup has
  no content dimension yet, so RA04.3 adds a migration for top content.
- `statistics_account_days` keeps the account identifier for as long as the facts are kept; retention and purge of
  facts and rollup are RA09.2.
- A fact that is parked instead of applied (RA02.3) is missing from the statistics until it is redriven; a redriven
  fact corrects the buckets of its own dates.
- Next per plan: RA04.2, the V1 statistics API and freshness from consumer progress.
