# Analytics: Candidate Ranking (Milestone RA05.2)

Verification date: 2026-11-18.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA05.2 generates and ranks recommendation candidates and serves them through the V1 query
`POST /internal/v1/recommendations/query` of `analytics-internal.v1.json`. Before RA05.2, an authorized call got
503 `ANALYTICS_UNAVAILABLE`. Candidates use the genre interests of [RA05.1](analytics-preferences-ra05-1.md).
Without history, the ranking falls back to 7-day popularity and then to newly published titles. Integration into
Core's public answer and the web, and a reason and algorithm version in a `/internal/v2` contract, are RA05.3.

Done condition (plan): useful results without history or with little interaction; several episodes of one series are
never repeated; equal scores keep a stable order.

Contract and migration changes:

- No contract changed. The V1 request and response schemas are served as frozen in RA01.1.
- New migration `V8__index_recommendation_reads.sql`: two partial indexes (sessions by qualifying instant; published
  movies and series). No data change.

## Candidates

A candidate is a **movie or series** (never a season or episode) that the catalog projection shows as published and
playable. A playable movie is one with a READY active asset. A playable series has a published season that holds a
published episode with a READY asset. This mirrors Core's `RECOMMENDABLE` rule, and Core still rechecks it on its own
tables and drops anything it no longer shows. Since a candidate is a title, a series appears at most once however
many of its episodes were played.

Two more reads feed the ranking. Both resolve a played episode to its series the same way as RA05.1:

- **Viewers in 7 days.** For each title, the number of distinct accounts with a session on the title (the movie or
  any episode of the series) whose qualifying instant is in the last 7 days. Accounts, not sessions or profiles:
  one household rewatching cannot make a title popular.
- **Completed titles.** Core's completion rule (`ResumeState.completes`) is a position at or past 95% of the
  duration. Core's history applies it to the latest accepted position, so seeking back or rewatching reopens an item.
  Analytics does the same: an item is completed when the profile's latest fact for it (by `occurredAt`, then
  sequence) is at or past 95%. A movie is completed when the movie is, a series when every playable episode is. The
  qualified-view threshold plays no part. Like RA05.1 interests, this read is scoped to the profile and its account.

## Ranking

`Ranking.rank` is pure domain code. It drops completed titles and fills the requested number of places tier by tier:

| Tier (rule) | Selection | Order |
| --- | --- | --- |
| 1. `GENRE_INTEREST` | Titles sharing at least one genre with the profile's interests | Affinity, then the tie-breaks |
| 2. `POPULAR` | Remaining titles with at least one viewer in 7 days | Viewers, then the tie-breaks |
| 3. `NEWLY_PUBLISHED` | Every remaining title | Newest publication first, then the ID |

**Affinity** is the cosine between the title's distinct genres (weight 1 each) and the interest shares:
`sum(share(g) for g in genres) / sqrt(|genres|)`. A title that matches one interest exactly outranks a title that
carries the same genre among several unrelated ones.

**Tie-breaks**, in this order: more viewers in 7 days, newer publication (an unknown publication time sorts last),
then the title ID as text. The text order of a UUID equals PostgreSQL's `uuid` order, so equal inputs always give
the same list, also across hosts.

A new profile, a deleted profile or a profile paired with another account has no interests and no completed titles.
It gets the cold-start list: popular titles, then the newest. A profile with little history gets its few genre
matches first, and the next tiers fill the remaining places. The tier that placed each title is kept in
`RankedTitle.rule`, so the reason planned for RA05.3 can report the rule that actually selected the title. V1
returns only the IDs.

## The recommendations endpoint

| Step | Behavior |
| --- | --- |
| Authentication | Unchanged from RA01.3: service JWT with exactly `recommendations:read`. |
| Request | Strict `RecommendationQuery`: closed object with `accountId`, `profileId` (hyphenated UUID text) and `limit` (JSON integer 1–100). At most 1,024 bytes of valid UTF-8, `application/json`. Failures are 400/413/415 problems without request text. The body reader is now shared with the statistics query (`QueryBodies`), which also rejects malformed UTF-8 instead of replacing it. `"20"`, `20.0` and `2e1` are rejected: JSON Schema's `integer` would accept `20.0`, but Core always sends an integer token, and the stricter reader is deliberate. |
| Readiness | The recommendations checkpoint is the oldest of the catalog, profiles and playback checkpoints (RA01.1). Until all three are confirmed, the answer is 503 `ANALYTICS_NOT_READY`, never a 200 with an empty list. |
| Consistency | The checkpoint is read before any data, so the data is never older than `updatedAt`. The four reads (interests, completed titles, viewers, titles) are separate statements, not one snapshot. A catalog change between them can shift the list slightly; Core rechecks every candidate, so this was accepted rather than adding a repeatable-read transaction. |
| Response | `profileId` echoes the request; `updatedAt` is the checkpoint in whole seconds, rounded down; `contentIds` are distinct movie and series IDs in rank order, at most `limit`. `Cache-Control: no-store`. |
| Writes | None. Reading never moves a checkpoint. |

Core asks for 100 candidates and keeps the ones it can still show (`AnalyticsReadAccess`). An empty list from
Analytics (an empty catalog) leads Core to its own newest-first fallback, as before.

## Verification

Shared by RA05.1 and RA05.2. Run on 2026-11-18 on commit `c4f6610` with the uncommitted RA02, RA04 and RA05 work
in the tree. Module runs are in `tmp/ra05-analytics-verify-*.log`; mutants are in `tmp/ra05-mutant-*.log`.

| Suite | Lane | Tests | Proves |
| --- | --- | --- | --- |
| `GenreInterestsTest`, `InterestRulesTest` | unit | 6 + 4 | RA05.1 caps, genre split, order-independent weights |
| `ProfilePreferencesTest` | unit | 3 | UTC day, zero increments, 30-day window by the clock |
| `RankingTest` | unit | 8 | Tier order, cold start, completed excluded, cosine affinity, tie-breaks (text UUID order), limit, duplicates |
| `ProfileRecommendationsTest` | unit | 4 | Not ready reads no data; checkpoint read first; limit bounds; 7-day window |
| `RecommendationRequestsTest` | unit | 28 | Closed body, UUID and integer strictness, size, media type, malformed UTF-8 |
| `InternalQueryControllerTest` | unit | 3 | Response keys, whole-second `updatedAt`, `no-store`, 503 when not ready |
| `PreferenceRollupIntegrationTest` | PostgreSQL | 5 | RA05.1 rollup through the real intake, tombstone, title resolution, window boundary, V7 backfill |
| `CandidateRankingIntegrationTest` | PostgreSQL | 5 | Playable titles vs Core's rule, distinct-account viewers, completion from the latest position (movie and series) |
| `RecommendationsApiIntegrationTest` | PostgreSQL, HTTP | 9 | 503 until all three checkpoints; cold start; genre-first; one series for several episodes; completed absent; tombstoned profile = cold start; little history fills with popular then new; repeat viewing capped through the database; contract schema and Core body rules; bad bodies |
| `ProfileErasureRaceIntegrationTest` | PostgreSQL | 2 | Now also asserts no `profile_watch_days` row survives either ordering of a fact and a concurrent tombstone |

Existing suites were adjusted for the new table and migrations (table and Flyway version lists, convergence dump,
truncations), and for the served endpoint (security suites now send a valid body).

**Mutants.** 18 source mutants were run, each against its targeted suites; all were killed by a real test failure.
The first driver run reported M1 as surviving because a wrong `-Dtest` separator ran no test. The driver now reports
`NO_TESTS_RAN` for that case, and the rerun killed M1.

| Mutant | Killed by |
| --- | --- |
| Item cap ignores duration; no title cap; genres not deduplicated | `GenreInterestsTest` / `InterestRulesTest` |
| Interests ignore the account; erasure keeps rows; deleted profile still recorded; 31-day window | `PreferenceRollupIntegrationTest`, `ProfilePreferencesTest` |
| Completed not excluded; popular tier skipped; ties by `UUID.compareTo`; affinity without `sqrt` | `RankingTest`, `RecommendationsApiIntegrationTest` |
| Completion at 94%; completion from the highest instead of the latest position; viewers count sessions; movie READY not required | `CandidateRankingIntegrationTest` |
| Limit up to 1,000; malformed UTF-8 replaced; not-ready serves an empty list | `RecommendationRequestsTest`, `ProfileRecommendationsTest`, `RecommendationsApiIntegrationTest` |

**Review.** A read-only review of the RA05 diff found no correctness or security defect of high severity. It raised
three risks, all handled:

- Completion counted any fact that once reached 95%. Core's history uses the latest position, so seeking to the end
  and back would hide a movie for good. Fixed: the latest fact decides. Two new IT cases and mutant M17 cover it.
- The concurrent fact/tombstone race test did not check the new rollup. Fixed: both orderings now assert it is empty.
- The V7 backfill runs once, so an old consumer running after the migration would leave gaps. Documented as a
  deployment condition (RA05.1 Limits).

The request reader now also rejects malformed UTF-8 (it used to replace it). Not changed: the four ranking reads are
not one snapshot (see the endpoint table), and `completed` is unbounded in time (Limits).

## Gate

Run on 2026-11-18 by the `ci-gate` agent, in the order of `.github/workflows/core-ci.yml`. Logs are in
`tmp/ci-gate/`. The first attempt failed at the gate's own tests: `infra/ci/test_report_gate.py` pinned the suite
counts that changed when the three new Analytics suites became required. The pins were updated and the gate was
rerun in full.

| Step | Result |
| --- | --- |
| `infra/ci` failure-path tests | 122 tests OK |
| `./mvnw -o -B -ntp clean verify` (JDK 21.0.10, offline) | BUILD SUCCESS |
| `infra/ci/check_test_reports.py` | PASS: 146 suites, 1,228 tests, 0 failures, errors or skips; the 17 required Analytics suites present |
| `infra/ci/check_contracts.py` | PASS, `analytics-v1` manifest 79 fixtures |
| Compose template (`--env-file .env.example`, throwaway keys) | exit 0 |
| Core packaged smoke / Media packaged smoke | PASS / PASS |
| `check_smoke_evidence.py` | PASS |

| Module | Unit suites / tests | Integration suites / tests |
| --- | --- | --- |
| core | 15 / 140 | 16 / 155 |
| media | 37 / 244 | 27 / 214 |
| recommendation-analytics | 34 / 349 | 17 / 126 |

The CI gate now requires three more Analytics suites: `PreferenceRollupIntegrationTest`,
`CandidateRankingIntegrationTest` and `RecommendationsApiIntegrationTest` (`REQUIRED_ANALYTICS_SUITES`;
`infra/ci/test_report_gate.py` updated). Local deviations from CI: Windows 11 instead of ubuntu-26.04; FFmpeg
9.0.2 instead of the pinned 8 (the version assertion step was not run); Maven offline; the smoke evidence check was
rerun once its run environment variables were set; the workflow was not run remotely. No smoke images or volumes were
left to remove.

## Limits

- `completed` reads every fact of the profile, without a time bound, on each request; it grows with use. Together
  with the next point, this is what RA05.4 measures against the 500 ms budget.
- Each request reads every candidate title and computes the ranking in memory. That is fine for the V1 catalog of
  hundreds of titles. A bound or a precomputed list belongs to RA05.4 (500 ms budget) and RA09.3 (load).
- Popularity counts qualified sessions only, and the 7-day window is relative to the request time. Core's admin
  statistics define a view the same way.
- A title in progress (started, not completed) stays eligible and may rank high. Blending with Core's continue-watching
  list and removing duplicates at title level is RA06.3.
- Completion uses the item's current duration (see the RA05.1 limits). A history deletion in Core does not reset it.
- Watchlist and "Không quan tâm" signals do not exist yet (RA06). Core's filtering of hidden titles is RA06.2.
- Analytics and Core were not run together; Core's real adapter against a real Analytics runs in RA09.
- Next per plan ("Thứ tự triển khai"): RA03.1–RA03.5, the web and player.
