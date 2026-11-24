# Analytics: Statistics Hardening (Milestone RA04.4)

Verification date: 2026-11-24.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA04.4 hardens the statistics of RA04.1–RA04.3 against the cases the plan names: the 30-second threshold, a session
across UTC midnight, late events, several profiles of one account, unique viewers over the whole range, queries over
the limits and an administrator who loses the role. It is a product stop point: the real player (RA03) and the basic
statistics dashboard, hardened.

Done condition (plan): the numbers match a reference dataset; the historical bucket is corrected; no personal data
in any response.

No contract or migration change beyond RA04.3. Code changes found necessary by this milestone: the 2-second statement
timeout on report reads (review finding R3 below) and the notice when the URL's range is replaced (R5).

## Reference dataset

`StatisticsReferenceIntegrationTest` (Analytics, PostgreSQL) builds about 70 sessions over four UTC days from a fixed
seed and sends every event through the real intake (`EventIntake`). Its expected answers come from a reference written
in the test that folds the raw heartbeats only and never calls projection code: a session's view is on the UTC date of
its lowest-sequence heartbeat whose Core total reaches 30 s; watched time on each heartbeat's UTC date; viewers are
distinct accounts; titles map episodes to series as the contract says.

| Case in the data | Expected |
| --- | --- |
| a session of 10 + 10 + 9.999 s | never qualifies |
| a session of 10 + 10 + 10 s | qualifies at its third heartbeat |
| heartbeats at 23:59:45, 23:59:55 and 00:00:05 | 20 s on the first day; 10 s and the view on the second |
| qualifies at 23:59:55, last heartbeat after midnight | view on the first day, watched time split |
| a heartbeat at exactly 00:00:00Z | belongs to the new day only |
| sequence 1 (30 s, 23:59:50) delivered after sequence 2 (00:00:20) | the view moves from day 2 to day 1 when sequence 1 arrives |
| two profiles of one account qualify the same day | two views, one viewer |
| an account that views on two days | one viewer over the range, one on each day |
| a profile deleted mid-data, then a session under it | still counted (platform totals keep the fact) |
| an episode under an unknown season; a season whose series is unknown; an item with no catalog event | each ranked under its own ID |
| 40 random sessions with zero, small and 30-second increments | whatever the reference says |

Tests:

- **Every window matches the reference in six delivery orders.** Each run shuffles the events and delivers some
  twice. For each single day from the day before the data to the day after it, the whole range, a middle range and a
  wider range, the V2 report (top 1, 3 and 50) equals the reference exactly (totals, every day, the ranking), and the
  V1 totals equal it too.
- **A late lower-sequence event moves the view to its earlier day.** Without sequence 1, day 2 holds the view; when
  it arrives, day 2 loses one view and day 1 gains one, and the whole dataset still matches the reference.
- **Threshold and unique-viewer edges hold exactly**: 29.999 s does not qualify, 30.000 s does; over the range,
  unique viewers are fewer than the sum of the days' viewers.

## Limits of a query and of authority

| Case | Where shown | Result |
| --- | --- | --- |
| 32-day window, `to` after today, `from > to` | Analytics IT, Core IT, browser | 400 `INVALID_REQUEST`; Core never calls Analytics |
| `top` 0, 51 or not a number; `topLimit` fractional, string, boolean or beyond `int` | Core IT, Analytics unit, browser | 400 |
| body over 1,024 bytes; not JSON; wrong charset | Analytics IT | 413 / 400 / 415, closed problems that never echo the body |
| a statement slower than 2 s | Analytics IT | cancelled (57014) → 503 `ANALYTICS_UNAVAILABLE` |
| service JWT with the wrong scope | Analytics IT | 403 before the body is read |
| anonymous; USER | Core IT, browser | 401; 403, no downstream call, no report request from the page |
| administrator demoted while Analytics answers or fails | Core IT | 403, no number in the body |
| administrator suspended during the call | Core IT | 401 (the session ends with the account) |

## Personal data

No account, profile or session identifier, and no email, is in any report: Analytics' (asserted against every seeded
ID in `StatisticsReportApiIntegrationTest`), Core's (`AnalyticsReportIntegrationTest`) and the bodies the dashboard
received in the browser (test 24 below, which also pins the field set).

## Browser acceptance on the real stack

`infra/smoke/check-web-ra04.ps1` runs `check-media-m29.ps1 -Milestone RA04`: the RA03 disposable stack (real `web`
image, Core, Media with real HLS, Analytics, Kafka, PostgreSQL behind the repository Nginx; installed Google Chrome),
the RA03 suite first (its test 7 produces a qualified view through the real player), then `web/e2e/ra04`. This is the
first run where Core's real adapter talks to a real Analytics for the report. Evidence: `target/verification/web-ra04.json`
(and RA03's `web-ra03.json`); the runner fails if either is missing.

| Test | What it shows |
| --- | --- |
| 19 | the administrator opens the dashboard from the header; default range = last 7 UTC days; fresh data shows the RA03 view on today's bar and row and the free movie, named from Core's catalog, in the ranking |
| 20 | the page's totals, each day's views and viewers and the ranking order equal Core's report; the report's totals equal V1's and its days add up |
| 21 | 30-day preset; a 41-day or future range is refused in the form with no request; the range survives a reload through the URL; an invalid URL range falls back to 7 days with a notice; Core answers 400 to 41 days, `top=51` and a future end |
| 22 | a viewer has no link, sees the access-denied message without a report request, gets 403 from Core; an anonymous visitor is asked to log in and gets 401 |
| 23 | MOCKED: STALE keeps the numbers under a UTC warning; UNAVAILABLE shows no number, bar or table; zero totals show the empty state |
| 24 | report bodies from Core hold only the contract's fields and no viewer email or profile ID |

PASS on 2026-11-24 (`WEB_RA04_BROWSER_PASS`, 6/6, Google Chrome headless), in the same run as RA03 (18/18) and RA05 (5/5); evidence copies in `tmp/ra05/browser/`. Two product bugs were found by this suite and fixed in the web app:

- B1 the profile picker redirect applied to every page, so an administrator without a selected viewing profile was sent from the dashboard to `/profiles`. Administration pages (`/admin/...`) are now exempt; test 19 asserts the dashboard stays put.
- B2 the date inputs carry `max = today`, so the browser's own form validation blocked a future end date silently and the page kept showing the previous message. The form now validates itself (`noValidate`) and always shows its own reason.

Login budget: the administrator's API checks in tests 20–21 reuse its browser session, so the run stays under Core's 10 logins per email per 15 minutes.

## Review and mutants

Review findings (code-reviewer on the full diff) fixed in this milestone:

- R1 the normative document the contracts point to did not exist yet (this document and RA04.3's).
- R2 `StatisticsSnapshotReadsIntegrationTest`, the only proof of the isolation level, was not a required suite.
- R3 an abandoned report scan kept running in PostgreSQL after Core's 500 ms budget: 2 s statement timeout.
- R4 the browser privacy test also scanned the mocked bodies of test 23: it now reads Core's bodies only.
- R5 an invalid range in the URL was replaced silently: the page says so.

Not changed: a gate freezing the V2 schema the way `test_analytics_contracts.py` freezes V1 (the corpus labels are
already checked by `check_contracts.py` for every manifest).

Mutants (`tmp/ra04/mutants.log`), killed only by a real test failure: unknown placement dropped; episode counted as
itself; window end inclusive for sessions and for facts; ranking ascending; viewer tie-break removed; snapshot at READ
COMMITTED; aggregates read outside the snapshot; `topLimit` 51 accepted; fractional `topLimit` accepted; Core's day-sum
check removed; busiest-day check removed; ID tie-break ignored; no role recheck after a failed call; draft title
preferred over the published one; `top` not forwarded. 16/16 killed. The session window-end mutant first survived:
the reference data had no heartbeat at exactly 00:00:00Z. The case was added (above) and it is now killed.

## Gate

Run with `infra/ci/verify-local.ps1 -Offline` (the order of `.github/workflows/core-ci.yml`) on commit `0159313` with the uncommitted RA04 work in the tree, on 2026-11-23. Evidence copies are in `tmp/ra04/gate/`.

| Step | Result |
| --- | --- |
| `infra/ci` failure-path tests | 122 tests OK |
| `./mvnw -o -B -ntp clean verify` (JDK 21.0.10, offline) | BUILD SUCCESS |
| `infra/ci/check_test_reports.py` | PASS: 154 suites, 1,343 tests, 0 failures, errors or skips |
| `infra/ci/check_contracts.py` | PASS: `analytics-v1` 79 fixtures, `analytics-v2` 36 fixtures |
| Compose template (`--env-file .env.example`, throwaway keys) | exit 0 |
| Core packaged smoke / Media packaged smoke (ingress M29) | PASS |
| `check_smoke_evidence.py` | PASS |

| Module | Unit suites / tests | Integration suites / tests |
| --- | --- | --- |
| core | 16 / 168 | 17 / 168 |
| media | 37 / 244 | 27 / 214 |
| recommendation-analytics | 36 / 407 | 21 / 142 |

The gate requires four more suites: `AnalyticsReportIntegrationTest` (Core), `StatisticsReportApiIntegrationTest`, `StatisticsReportHttpCompatibilityIntegrationTest`, `StatisticsReferenceIntegrationTest` and `StatisticsSnapshotReadsIntegrationTest` (Analytics); `infra/ci/test_report_gate.py` pins updated. Earlier attempts failed for reasons recorded here: once on Media's `ScratchSpaceTest` (intermittent on this machine: 1 failure in 3 isolated reruns, unrelated to RA04), twice because Docker Desktop stopped, and once on `CoreServiceSecurityIntegrationTest`, which pins the number of internal handlers and used `/internal/v2/statistics/query` as its example of an unknown path; it now counts the new handler and checks it is bound to exactly one scope.

The same tree with RA05.3–RA05.4 added passed again on 2026-11-24 (161 suites, 1,430 tests; see [RA05.4](analytics-recommendation-hardening-ra05-4.md)). Local deviations from CI: Windows 11 instead of ubuntu-26.04; FFmpeg 9.0.2 instead of the pinned 8; Maven offline; the workflow was not run remotely and does not run the browser suites.

## Limits

- The reference dataset is synthetic and small (four days, about 70 sessions); it checks the rules, not volume.
  Report latency on large data is RA09.3.
- Two Codex-assisted parts of this milestone (the web dashboard) could not run through the Codex companion, which
  kept attaching new tasks to an earlier thread; the dashboard was written directly instead. No effect on the result.
- The browser suite runs on one disposable local stack; the remote CI workflow does not run it (as for RA03 and M33).
- One mocked browser step (test 23) for the STALE and UNAVAILABLE renderings; the real unavailable path is covered by
  Core's integration suite.
- Local evidence only; the CI workflow was not run remotely.
- Next per plan ("Thứ tự triển khai"): RA05.3–RA05.4, done in the same pass ([RA05.3](analytics-recommendation-lists-ra05-3.md), [RA05.4](analytics-recommendation-hardening-ra05-4.md)).
