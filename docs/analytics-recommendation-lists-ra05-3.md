# Analytics: Recommendation Lists with Reasons (Milestone RA05.3)

Verification date: 2026-11-24.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA05.3 brings the RA05.2 ranking to Core and the web as four lists, each title with the reason that placed it, and
the algorithm version: personal, similar (to a title), popular and newly published.

Done condition (plan): the final metadata and availability come from Core; the reason shown matches the rule that
actually selected the title.

Contract and migration changes:

| Change | Kind |
| --- | --- |
| `analytics-internal.v2.json`: `POST /internal/v2/recommendations/query` (V2 is new in RA04.3 and unreleased, so it stays 2.0.0 with two operations) | additive |
| `contracts/fixtures/analytics-v2`: 28 `list-*` fixtures (13 query, 15 list), 64 in total | additive |
| `core-analytics.v1.json` 1.1.0 → 1.2.0: `GET /v1/profiles/{profileId}/recommendations/lists/{list}` | additive |
| V1 recommendations (internal and public) | unchanged |

No Flyway migration. No new setting.

## The lists (Analytics)

`POST /internal/v2/recommendations/query` takes `{accountId, profileId, list, seedContentId, limit}`, scope exactly
`recommendations:read`. `list` is `PERSONAL`, `SIMILAR`, `POPULAR` or `NEW` (exact case); `seedContentId` is a UUID
for SIMILAR and JSON `null` otherwise; `limit` is an integer token 1–100. Body limit, media type, problems and
readiness are V1's: until the catalog, profile and playback checkpoints are all confirmed the answer is 503
`ANALYTICS_NOT_READY`, never an empty list.

The answer is `{profileId, list, algorithmVersion: "rules-v1", updatedAt, items: [{contentId, reason}]}`.

| List | Reasons it can carry | Selection and order |
| --- | --- | --- |
| PERSONAL | GENRE_INTEREST, then POPULAR, then NEWLY_PUBLISHED | the RA05.2 ranking unchanged; the reason is the tier that placed the title |
| SIMILAR | SIMILAR_GENRES | titles sharing a genre with the seed, by the cosine of the two genre sets `|A∩B| / sqrt(|A||B|)`, then RA05.2's tie-breaks; the seed is never listed |
| POPULAR | POPULAR | titles with at least one viewer (distinct account, qualified session) in the last 7 days, by viewers, then the tie-breaks |
| NEW | NEWLY_PUBLISHED | every candidate, newest publication first (unknown last), then viewers, then the ID as text |

Common to every list: candidates are RA05.2's (published, playable movies and series; episodes collapse into their
series), titles the profile completed are left out, items are distinct, at most `limit`. **No list is filled with a
title another rule chose**: a SIMILAR list for a seed without genres (or one Analytics does not know as a published
movie or series) is empty, and so is POPULAR when nobody watched anything in 7 days. An empty list is a truthful
answer. `ListSnapshot` refuses, at construction, a title whose rule does not belong to the list, so a reason outside
the table above cannot be produced.

`algorithmVersion` (`ListKind.ALGORITHM_VERSION`) names these rules; it changes whenever a rule or an order changes.

## The public lists (Core)

`GET /v1/profiles/{profileId}/recommendations/lists/{list}?limit&contentId`, `list` in lower case, `limit` 1–20
(default 12). The account must be live and own the profile (404 otherwise). `contentId` is required for `similar` and
rejected for the others (400); the seed must be a movie or series Core shows publicly (404 otherwise, as for a hidden
title, before Analytics is called).

Core asks Analytics for 100 titles and rejects the answer as `INVALID_RESPONSE` when the profile or list is not
echoed, `updatedAt` is in the future, an item repeats, a reason is not allowed for the list, the SIMILAR seed is
listed, or PERSONAL's tiers go backwards (`RecommendationListRules`). An answer older than 15 minutes is `STALE`.
Core then keeps, in Analytics' order, the titles it can still show and recommend (`CatalogQueries.recommendedCandidates`:
published under a published parent chain, READY media), up to `limit`, each with Analytics' reason. Metadata,
availability and the access tier are Core's.

| Outcome | `source` | `items` |
| --- | --- | --- |
| Analytics answered, Core shows some titles | ANALYTICS | those titles with Analytics' reasons |
| Analytics answered an empty popular or similar list | ANALYTICS | `[]` |
| Analytics answered, Core can show none of it | FALLBACK, `NO_VISIBLE_CANDIDATES` | as below |
| Analytics disabled, unavailable, slow (500 ms), invalid or stale | FALLBACK with that reason | personal and new: Core's newest titles, each labelled `NEWLY_PUBLISHED`; popular and similar: `[]` |

The fallback never claims a reason Core did not apply: Core has no popularity or similarity data, so those fallbacks
are empty. `algorithmVersion` and `updatedAt` are null in a fallback. A recommendation never grants playback: the
card links to the title page and the existing admission decides (a Premium title recommended to a free viewer is
still refused with `PREMIUM_REQUIRED`).

## Web

- Home, with a profile selected: rows "Dành cho bạn" (personal), "Phổ biến" (popular), "Mới phát hành" (new) above
  the catalog. Title pages of movies and series: "Tương tự" (similar to that title).
- Each card shows the title, kind, access tier and the reason label: "Hợp với thể loại bạn hay xem",
  "Cùng thể loại", "Nhiều người xem tuần này", "Mới phát hành". An unknown reason is never shown.
- A fallback row says "Gợi ý cá nhân tạm thời không khả dụng"; an empty row is hidden; a failing request hides the row
  (the catalog stays usable). Cards have no play button.
- Rows load through `useProfileScoped`: a profile switch aborts the old requests and never shows the previous
  profile's row. Responses are `no-store`.
- Found while testing: the profile picker redirect applied to every page, so the RA04.3 dashboard sent an
  administrator without a selected profile to `/profiles`. Administration pages (`/admin/...`) are now exempt.

## Verification

| Suite | Lane | Tests | What it shows |
| --- | --- | --- | --- |
| `RankingListsTest` | Analytics unit | 5 | similar cosine, seed and completed excluded, no fill; popular only watched; newest order with unknown publication last |
| `RecommendationListRequestsTest` | Analytics unit | 30 | list names exact case, seed rules, strict UUIDs and limit, closed body |
| `ProfileRecommendationsListTest` | Analytics unit | 18 | not ready reads nothing; seed/list mismatch; unknown seed empty without reading titles; rule/list consistency |
| `RecommendationListApiIntegrationTest` | Analytics, PostgreSQL | 7 | each list's selection and reasons on real data; PERSONAL equals V1's order; completed and unplayable absent; contract keys; wrong scope 403 before the body; every `list-query-*` fixture |
| `RecommendationListRulesTest` | Core unit | 11 | every consumer rule |
| `AnalyticsRecommendationListIntegrationTest` | Core, PostgreSQL + stub Analytics | 13 | exact V2 request per list; every `list-*` fixture's outcome; unpublished during the call and partial results keep order and reasons; invalid queries and hidden or non-title seeds never call Analytics; honest fallback for failure, 700 ms timeout (answered under 1.5 s) and disabled adapter; ownership; Premium still refused; two profiles never mixed |
| `lists.test.ts` | web unit | 7 (incl. inherited keys) | paths, fetch, row view (fallback, hidden, unknown reason) |

RA05.4 (`docs/analytics-recommendation-hardening-ra05-4.md`) adds the load measurement, the mutants and the browser
acceptance.

## Limits

- Similarity uses genres only; with a catalog whose titles share one genre, every title is "similar".
- The four reads of a list are separate statements, as in RA05.2; Core rechecks every title.
- A title's reason is the rule that placed it in Analytics; Core may drop some titles (no longer visible), never
  relabel them.
- Local evidence only; the CI workflow was not run remotely.
