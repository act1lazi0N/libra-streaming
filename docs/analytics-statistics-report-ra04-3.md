# Analytics: Statistics Report and Admin Dashboard (Milestone RA04.3)

Verification date: 2026-11-24.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA04.3 adds daily totals and a ranking of the most watched titles, behind a new versioned contract, and the
administrator dashboard that shows them. The V1 statistics operation, which returns totals only, is unchanged.

Done condition (plan): the UI shows the right numbers, the stale and unavailable states and UTC days; the backend
checks the ADMIN role.

Contract and migration changes:

| Change | Kind |
| --- | --- |
| `contracts/http/analytics-internal.v2.json` 2.0.0: `POST /internal/v2/statistics/query` | new contract |
| `contracts/fixtures/analytics-v2/manifest.json`: 36 fixtures (10 query, 26 report) | new corpus |
| `contracts/http/core-analytics.v1.json` 1.0.0 → 1.1.0: `GET /v1/admin/statistics/report` | additive |
| `analytics-internal.v1`, its corpus and `GET /v1/admin/statistics` | unchanged |

No Flyway migration in either service. The report reads the RA02 facts and sessions, the RA04.1 rollup and the
indexes of V3 (`ix_playback_facts_occurred`) and V8 (`ix_playback_sessions_qualified_at`). No new setting.

## The V2 report (Analytics)

`POST /internal/v2/statistics/query` takes `{from, to, topLimit}`. The window rules are V1's (inclusive UTC dates,
`from <= to`, at most 31 days, `to` not after today UTC). `topLimit` must be a JSON integer token from 1 to 50:
`10.0`, `1e1`, `"10"`, `true` and values beyond `int` are `400 INVALID_REQUEST`. Authentication, the scope
(exactly `statistics:read`, the same platform-aggregate data as V1), the 1,024-byte body limit, media types,
problem bodies and the 503 rules are V1's. Nginx keeps `/api/recommendations/internal/` closed to browsers.

The 200 body is `{from, to, updatedAt, totals, days, topContent}`:

| Field | Meaning |
| --- | --- |
| `totals` | V1's three numbers for the same window |
| `days` | exactly one entry per date of the window, ascending, zeros included; a day's `uniqueViewers` counts that day alone |
| `topContent` | at most `topLimit` titles, each `{contentId, qualifiedViews, uniqueViewers, acceptedWatchedMs}` |

**Title.** The ranking unit is a movie or a series. A session or fact on an episode counts for its series once
Analytics' catalog projection knows the episode → season → series chain. Until then (metadata not arrived, or a
season whose series is unknown) the watched item's own ID is used, so no view disappears from the ranking. A season
is never a title.

**Numbers per title.** `qualifiedViews`: the title's sessions whose qualifying instant has its UTC date in the
window. `uniqueViewers`: distinct accounts among those sessions. `acceptedWatchedMs`: the accepted increments of
the title's facts whose `occurredAt` UTC date is in the window. A title with neither a view nor watched time is
absent. Order: `qualifiedViews`, `uniqueViewers`, `acceptedWatchedMs`, all descending, then `contentId` as
lower-case text ascending. Ties therefore always have one answer.

**Source of the ranking.** `topContent` is read from `playback_sessions` and `playback_facts` with half-open UTC
bounds `[from 00:00Z, to+1 00:00Z)`, the day rule of `DayContribution.dayOf`. The RA04.1 rollup is per account and
day and has no title; a second rollup was not needed at V1 catalog size. The facts and sessions are written in the
transaction that updates the rollup, so both sources always agree.

**One snapshot.** The checkpoint, totals, days and ranking are read in one read-only `REPEATABLE READ` transaction
(`StatisticsConfiguration.snapshotReads`), checkpoint first. PostgreSQL gives every statement of such a transaction
the same snapshot, so the days add up to the totals, the ranking cannot hold a view the totals lack, and nothing is
older than `updatedAt`. Separate statements, as in V1, could disagree when a fact commits between them; Core would
then reject the report. Read-only transactions in PostgreSQL never fail with a serialization error.

**Statement timeout.** Each statement of that transaction runs under `SET LOCAL statement_timeout = 2000`. Core gives
up after 500 ms and shows UNAVAILABLE, but PostgreSQL would otherwise keep running an abandoned scan, so repeated
retries could pile up. A cancelled statement becomes 503 `ANALYTICS_UNAVAILABLE`; `SET LOCAL` ends with the
transaction, so pooled connections keep no timeout.

A 31-day report with 50 titles is about 10 KB, well under the 65,536-byte limit.

## The admin report (Core)

`GET /v1/admin/statistics/report?from&to&top` (`top` 1..50, default 10) checks the live ADMIN role, validates the
window exactly like `/v1/admin/statistics` (400 otherwise, before any downstream call), and calls V2 with
`topLimit = top` through the existing client (500 ms budget, no retry, 16 in flight, 64 KiB, strict JSON).

Core rejects a report as `INVALID_RESPONSE`, and then shows no number at all, when any of these does not hold
(`StatisticsReportRules`):

1. `from`/`to` echo the request; `updatedAt` is not after Core's clock.
2. `days` has one entry per date of the window, in order.
3. Every count is nonnegative (Bean Validation); in totals, each day and each title, `uniqueViewers <= qualifiedViews`.
4. The days' views and watched time add up exactly to the totals (`Math.addExact`; an overflow is invalid).
5. The busiest day's viewers `<=` the window's viewers `<=` the sum of the days' viewers.
6. At most `top` titles, distinct, each with a view or watched time, in the contract's strict order, and together
   holding no more views or watched time than the totals.

After the call, `AnalyticsReadAccess.administratorLabels` locks the account again and requires ADMIN in the same
transaction that names the titles. This happens whether Analytics answered or failed, so an administrator demoted
during the call gets 403 and learns nothing, not even that Analytics was unavailable. A suspended account loses its
session and gets 401, as everywhere in Core. Titles are named from Core's own catalog: the published revision's
title, or the draft's when the title is not published (`published: false`); an ID Core does not know keeps its
numbers with `kind` and `title` null. Analytics' order is kept.

The answer is `{status, reason, from, to, updatedAt, totals, days, topContent}`. `STALE` (checkpoint older than
15 minutes) keeps the numbers; `UNAVAILABLE` has `updatedAt`, `totals`, `days` and `topContent` all null. The
response never carries an account, profile or session identifier.

## The dashboard (web)

`/admin/statistics` (`web/src/app/admin/statistics/page.tsx`), linked as "Thống kê" in the header for ADMIN
accounts. The role in the browser is only a hint: the page asks Core, and Core's 403/401 are shown as such.

| State | What the page shows |
| --- | --- |
| anonymous / 401 | a link to log in |
| not ADMIN / 403 | "Bạn không có quyền xem thống kê.", and no report request for a non-admin session |
| invalid range | the reason, without a request (client check mirrors Core's; Core's 400 is shown the same way) |
| range in the URL is invalid | the default range with a notice that it was replaced |
| UNAVAILABLE | an error panel with the reason code; no number, chart or table |
| STALE | a warning with the last update in UTC, and the numbers |
| AVAILABLE, nothing watched | the empty state, with the update time |
| AVAILABLE | three totals, a daily bar chart with a table of every day, the ranked titles |

Dates are UTC calendar dates everywhere (`utcToday`, inputs labelled "(UTC)", times printed as `… UTC`). The default
range is the last 7 days ending today; presets for 7 and 30 days; the applied range lives in the URL. The chart is
inline SVG with an accessible table of the same numbers; no new dependency. The page's logic is in
`web/src/lib/statistics/report.ts` (pure functions, unit-tested).

## Verification

| Suite | Tests | What it shows |
| --- | --- | --- |
| `StatisticsReportRequestsTest` (Analytics, unit) | 53 | integer-only `topLimit` 1..50, closed body, dates, media type and size |
| `PlatformStatisticsReportTest` (Analytics, unit) | 5 | not ready without reading aggregates; limit bounds; checkpoint first and every read inside one snapshot |
| `StatisticsReportApiIntegrationTest` (Analytics, PostgreSQL) | 9 | 503 before a checkpoint; zero days; movie, known episode and unknown placement; midnight split; every tie-break and the limit; totals equal V1 and add up; no identifiers; requests never write; 415/413/400 closed problems; wrong scope 403 before the body is read; every `report-query-*` fixture |
| `StatisticsReportHttpCompatibilityIntegrationTest` (Analytics, real HTTP) | 1 | Core's exact request; 31 days × 50 titles is 200, `application/json`, ≤ 65,536 bytes, under 500 ms after warm-up |
| `StatisticsSnapshotReadsIntegrationTest` (Analytics, PostgreSQL) | 3 | the snapshot is read-only REPEATABLE READ with a 2 s statement timeout that ends with it; a slow statement is cancelled (57014); writes are refused |
| `StatisticsReportRulesTest` (Core, unit) | 28 | every consumer rule, overflow, valid zeros and ties |
| `AnalyticsReportIntegrationTest` (Core, PostgreSQL + stub Analytics) | 12 | exact V2 request and service identity; every V2 report fixture reaches its declared outcome with exact numbers; failures and the disabled adapter give nulls; anonymous 401 and USER 403 without a downstream call; invalid queries never sent; demotion (403) or suspension (401) during a successful or failed call discloses nothing; titles named from the published or draft revision, unknown IDs kept |
| `report.test.ts` (web, unit) | 15 | UTC dates, range rules, URL range, request path, views, failures, formatting |

The browser acceptance of the dashboard on the real stack is RA04.4's (`docs/analytics-statistics-hardening-ra04-4.md`).

## Limits

- The ranking scans the window's facts and qualified sessions on each request. That is fine for V1 data volumes and
  bounded by the 2 s statement timeout; a per-title rollup, or a cache, belongs to RA09.3 (load) if it is needed.
- `topContent` names a title from Core's catalog as it is now: a renamed title shows its current name.
- An episode watched before its series reached Analytics ranks under its own ID until the catalog event arrives;
  Core then names it as the episode.
- The dashboard does not refresh by itself; "Xem" or a reload asks again.
- Local evidence only; the CI workflow was not run remotely.
