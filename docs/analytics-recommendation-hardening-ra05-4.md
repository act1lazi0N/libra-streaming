# Analytics: Recommendation Hardening (Milestone RA05.4)

Verification date: 2026-11-24.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA05.4 hardens the lists of [RA05.3](analytics-recommendation-lists-ra05-3.md) against a new profile, a small or
empty catalog, missing genres, repeated viewing, unpublishing during a request, partial results, timeouts and invalid
answers.

Done condition (plan): Core's 500 ms downstream budget holds; the fallback is honest; no cache or profile is mixed;
a recommendation never grants playback.

No contract or migration change beyond RA05.3. Code changed by this milestone: a SIMILAR list reads the seed's genres
first and answers an unknown seed before the heavier reads (review R4); the web reason filter uses `Object.hasOwn`
(R5).

## Cases

| Case | Where shown | Result |
| --- | --- | --- |
| New profile (no history) | `RecommendationListApiIntegrationTest`, `RankingTest` | PERSONAL is popular then newest; POPULAR and NEW as for anyone |
| Empty catalog | `RankingListsTest`, `RecommendationListApiIntegrationTest` | every list `[]`; Core: personal/new fall back to its own newest (`NO_VISIBLE_CANDIDATES` when Core shows nothing either), popular/similar stay `[]` from Analytics |
| Small catalog | `RankingListsTest` | lists shorter than the limit, never padded with another rule's titles |
| Missing genres | `RankingListsTest`, `ProfileRecommendationsListTest` | a seed without genres or unknown to Analytics: SIMILAR `[]`, without reading titles, history or popularity; a title without genres is never "similar" |
| Repeated viewing | RA05.1 caps (`GenreInterestsTest`), `RecommendationsApiIntegrationTest` | one rewatched title cannot dominate the interests; completed titles leave every list |
| Unpublished while Core waits | `AnalyticsRecommendationListIntegrationTest` | Core drops it, keeps the others in Analytics' order with Analytics' reasons |
| Partial result | same | unknown IDs dropped; `limit` applied after Core's filter |
| Timeout (700 ms downstream) | same | FALLBACK `UNAVAILABLE`, answered in under 1.5 s; personal/new show Core's newest as NEWLY_PUBLISHED, popular/similar `[]` |
| Invalid answers | 15 `list-*` fixtures, `RecommendationListRulesTest` | wrong profile or list, future time, duplicates, a reason outside the list, the seed in SIMILAR, PERSONAL tiers backwards: all `INVALID_RESPONSE` |
| Cache / profile mixing | Core IT (two profiles of one account), browser test 28 | every response `no-store`; each profile gets only its own list; after a switch every request names the new profile |
| Playback from a recommendation | Core IT, browser test 27 | a Premium title recommended to a free viewer is still refused (`PREMIUM_REQUIRED`); cards have no play control |

## Budget

`RecommendationListLoadIntegrationTest` (PostgreSQL, real socket, Core's request): 2,000 titles (1,500 movies, 500
series with a season and an episode), a profile with 20,000 facts on 400 items and its 30-day interests, 6,000
qualified sessions in the 7-day window. After warm-up, 20 sequential requests per list, then 3 rounds of 16
concurrent requests (Core's in-flight limit) mixing the four lists:

| Run | Median | Slowest |
| --- | --- | --- |
| PERSONAL, sequential | 92 ms | 146 ms |
| SIMILAR, sequential | 75 ms | 135 ms |
| POPULAR, sequential | 73 ms | 85 ms |
| NEW, sequential | 72 ms | 110 ms |
| 16 concurrent (worst round) | 158 ms | 224 ms |

The test fails if any request reaches 500 ms. The first version of the test failed for the wrong reason: its SIMILAR
seed had two equal genres, so only 99 other titles qualified; the seed is now chosen with two distinct genres.

## Browser acceptance on the real stack

`infra/smoke/check-web-ra05.ps1` runs `check-media-m29.ps1 -Milestone RA05`: the RA03 suite, then RA04's, then
`web/e2e/ra05`, on one disposable stack (real web image, Core, Media with real HLS, Analytics, Kafka, PostgreSQL behind
the repository Nginx; installed Google Chrome). This is the first run of Core's real list adapter against a real
Analytics. Evidence: `web-ra03.json`, `web-ra04.json` and `web-ra05.json`; the runner fails if any is missing.
Every list the page shows is compared with Core's own answer for the same profile.

| Test | What it shows |
| --- | --- |
| 25 | the free viewer's personal, popular and new rows equal Core's lists (titles, order, reasons, `rules-v1`); popular holds the free movie the RA03 player qualified, with its popularity reason; only movies and series, never the hidden title; responses `no-store` |
| 26 | the free movie's page shows Core's similar list, all labelled shared-genre, without the movie itself; Core answers 404 to a hidden or episode seed, 400 to a seed on another list |
| 27 | the recommended Premium movie has no play control and its page still shows the Premium denial |
| 28 | after switching profile A → B every list request sent names B, and the rows equal Core's lists for B |
| 29 | MOCKED: a FALLBACK personal list shows the unavailable note with a NEWLY_PUBLISHED label; an empty fallback popular list is hidden; the catalog still renders |

PASS on 2026-11-24 (`WEB_RA05_BROWSER_PASS`, 5/5), in one run with RA03 (18/18) and RA04 (6/6), 29 tests in 2.6 minutes; evidence copies in `tmp/ra05/browser/`.

Login budget: Core allows 10 logins per email per 15 minutes. The table in `web/e2e/ra03/support.ts` now includes the
RA04 and RA05 suites (free viewer 6, premium viewer 7, administrator 8); RA04 tests 20–21 reuse the administrator's
browser session instead of logging in again (review R3, which found the administrator at exactly 10).

## Review and mutants

Review findings (code-reviewer on the RA05 diff), all handled:

- R1 browser test 28 recorded list calls on response, so a late answer for profile A could look like a request after
  the switch: calls are now recorded when sent.
- R2 the budget was measured sequentially only: a 16-way concurrent measurement was added.
- R3 the administrator's logins reached Core's limit exactly once RA04 ran before RA05: two logins removed.
- R4 SIMILAR ran the history and popularity reads before discovering an unknown seed: the seed comes first.
- R5 `reason in REASON_LABELS` accepted inherited keys such as `toString`: `Object.hasOwn`.
- R6 the contracts pointed to these documents before they existed.

Mutants (`tmp/ra05/mutants.log`), 15/15 killed by a real test failure: the seed kept in SIMILAR; similarity without
the shared-genre count; POPULAR filled with unwatched titles; NEW with unknown publication first; completed titles
kept; SIMILAR falling back to popular; a seed accepted on NEW; PERSONAL's last tier labelled POPULAR; Core not
checking the reason set, the seed, or PERSONAL's tier order; a popular fallback filled with Core's newest; Analytics'
reason replaced by Core; a hidden seed accepted; a stale answer accepted.

## Gate

Run with `infra/ci/verify-local.ps1 -Offline` on commit `0159313` with the uncommitted RA04 and RA05 work in the tree, on 2026-11-24. Evidence copies are in `tmp/ra05/gate/`.

| Step | Result |
| --- | --- |
| `infra/ci` failure-path tests | 122 tests OK |
| `./mvnw -o -B -ntp clean verify` (JDK 21.0.10, offline) | BUILD SUCCESS |
| `infra/ci/check_test_reports.py` | PASS: 161 suites, 1,430 tests, 0 failures, errors or skips |
| `infra/ci/check_contracts.py` | PASS: `analytics-v1` 79 fixtures, `analytics-v2` 64 fixtures |
| Compose template (`--env-file .env.example`, throwaway keys) | exit 0 |
| Core packaged smoke / Media packaged smoke (ingress M29) | PASS |
| `check_smoke_evidence.py` | PASS |

| Module | Unit suites / tests | Integration suites / tests |
| --- | --- | --- |
| core | 17 / 179 | 18 / 181 |
| media | 37 / 244 | 27 / 214 |
| recommendation-analytics | 39 / 460 | 23 / 152 |

The gate requires three more suites: `AnalyticsRecommendationListIntegrationTest` (Core), `RecommendationListApiIntegrationTest` and `RecommendationListLoadIntegrationTest` (Analytics). The first attempt failed on two RA04.3 tests that selected corpus entries by `coreOutcome` and so also took RA05's `list-*` fixtures; they now select by schema pointer. Two later attempts failed because Docker Desktop stopped during Media's integration suites with the host out of memory (about 400 MB available); the passing run had about 1.4 GB free after Docker started. The web fix B2 of RA04.4 came after this gate; it touches only `web/`, whose unit tests (217), lint and build were rerun, and the browser run below includes it. Local deviations from CI: Windows 11 instead of ubuntu-26.04; FFmpeg 9.0.2 instead of the pinned 8; Maven offline; the workflow was not run remotely and does not run the browser suites.

## Limits

- The load test is one workstation with the database in Docker; production sizing is RA09.3.
- Each list request reads the whole candidate set; a cache per checkpoint would be the next step if catalogs grow
  past the measured size.
- One mocked browser step (test 29); the real fallback paths are covered by Core's integration suite.
- Local evidence only; the CI workflow was not run remotely and does not run the browser suites.
- Next per plan ("Thứ tự triển khai"): RA06.1–RA06.5.
