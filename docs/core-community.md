# Core: Watchlist and community (Milestone 06)

Core owns profile watchlists and account-level reviews. The versioned
[OpenAPI contract](../contracts/http/core-community.v1.json) uses `/v1` internally
and `/api/core/v1` through Nginx. Authentication, live account/session checks,
cookie configuration, CSRF and Problem Details follow [identity](core-identity.md).
All responses use the existing Security no-store headers. No new secrets or
runtime configuration are required. V6 adds four tables and a qualified-session
index; it does not modify previously applied migrations.

## Watchlist

- `GET /profiles/{profileId}/watchlist` lists current published movie/series
  titles, kind, content ID and saved timestamp. Draft metadata is never returned.
- `PUT` and `DELETE /profiles/{profileId}/watchlist/{contentId}` return `204`.
  Duplicate adds preserve the first saved timestamp; repeated removes are safe.
- Every operation checks profile ownership, including for administrators. Login
  is sufficient; email verification and Premium are not required to save titles.
- Add requires a published movie or series. Season/episode targets return `400`;
  missing/unpublished titles return `404`. Unpublished saved titles disappear
  from lists but remain saved and reappear after publication. Removal remains
  possible while hidden. Deleting a profile cascades its watchlist entries.
- Pages use `limit` 1–100 (default 20) and `offset` 0–10000 (default 0), ordered by
  saved timestamp descending then content UUID. Pagination is stable for an
  unchanged collection; concurrent mutations may shift offset pages.

## Qualified reviews

- `PUT /me/reviews/{contentId}` accepts `expectedVersion`, `stars` (1–5), and
  optional plain `text` (at most 2,000 characters). The target must be a published
  movie or series. Unknown fields are rejected. Text is never interpreted as HTML;
  clients must render it as text, including names and report/moderation reasons.
- The live account must be verified and have **one playback session with at least
  30,000 ms of Core-accepted watched duration** for that movie, or an episode of
  that series. Sessions across the account's profiles qualify, including ended or
  expired sessions. Separate short sessions are not summed. Timeline seeking and
  completion do not qualify a review. The client cannot submit qualification.
- Clearing history retains playback evidence. Deleting a profile cascades its
  sessions, so their evidence is no longer available for later review writes.
  Existing account reviews remain; writes require another retained qualified
  session if all qualifying profiles were deleted. Premium expiry does not remove
  prior qualification. Future session retention must preserve this policy or
  introduce an explicit durable qualification record before purging sessions.
- Database uniqueness enforces one review per account/content, regardless of
  profile. Use `expectedVersion: 0` only for the first creation. The response
  includes review ID, content ID, stars/text, hidden/deleted flags and version.
  Later writes require the current version. Stale requests receive `409
  VERSION_CONFLICT`; fetch `GET /me/reviews/{contentId}` to reconcile an uncertain
  response. Create/repost/edit return `200`.
- Reviews publish immediately. `DELETE /me/reviews/{contentId}?expectedVersion=N`
  erases stars/text and retains an identity/version tombstone and moderation flag.
  Hidden review edits and reposts stay hidden. Only an administrator can restore
  visibility. Restoring a deleted review never restores its text or public vote.
  Owners can read/delete their review even when its catalog title is unpublished.
- Write attempts are limited to 30 per account per 10 minutes using the existing
  durable rate limiter. Validly shaped rejected attempts count too; `429` includes
  `Retry-After`. These counters commit separately from review transactions.

## Public listing and aggregates

`GET /catalog/{contentId}/reviews` is public for published movies/series. It returns
`count`, `averageStars` rounded to two decimals (`null` when count is zero), and
paged `items`. Count/mean include only nonhidden, nondeleted reviews, across all
pages. Items contain only review ID, account display name, stars, text and updated
timestamp, without account/profile/session IDs, email or moderation/report data.
Items sort by updated timestamp descending then review UUID. Count, mean, items
and publication visibility are read from one PostgreSQL transaction snapshot.
Catalog browsing and watchlisting do not grant playback entitlement.

## Reports and moderation

- Verified accounts can `POST /reviews/{reviewId}/reports` with a nonblank plain
  `reason` of at most 500 characters. The review and its movie/series must be
  public. Self-reporting returns `400`. Hidden/deleted reviews return `404`.
- Reports are deduplicated per reporter/review for the review's lifetime; retry
  returns `204` and preserves the first reason, timestamp and reported version.
  Reporting does not automatically hide a review. Attempts are limited to 20 per
  account per 10 minutes. Reporter identity is retained internally for deduplication
  and is not returned by the report API, public API or administrator report list.
- `GET /admin/reviews?reportedOnly=true` lists reviews with report counts. Set
  `reportedOnly=false` to include unreported reviews. This is a historical report
  queue: reports remain after moderation and are not treated as newly unresolved.
  Hidden/deleted reviews remain available to moderators. Use the same bounded
  pagination as public lists.
- `GET /admin/reviews/{reviewId}/reports` exposes reasons, reported review versions
  and timestamps. It does not retain snapshots of deleted review text.
- `PUT /admin/reviews/{reviewId}/moderation` requires `expectedVersion`,
  `visibility` (`HIDDEN` or `VISIBLE`) and a nonblank reason of at most 500
  characters. Every accepted command increments the review version and atomically
  appends an audit record containing administrator ID, action, reason, prior
  hidden flag, resulting version, correlation ID and server timestamp.
- `GET /admin/reviews/{reviewId}/audit` returns newest version first. Every admin
  operation checks the live ADMIN role in the service as well as the HTTP filter.
  Audit failure rolls back the visibility change. Owner edits/deletes and moderator
  changes lock the review and use the same version, so stale commands cannot
  silently overwrite another decision.

Stable feature errors include `NOT_FOUND`, `INVALID_REQUEST`, `VERSION_CONFLICT`,
`EMAIL_VERIFICATION_REQUIRED`, `QUALIFIED_VIEW_REQUIRED`, and `RATE_LIMITED`.
Authorization/identity errors follow the identity contract. Mutating HTTP methods
require both the matching CSRF cookie and masked `X-CSRF-TOKEN` obtained from
`GET /v1/auth/csrf`, including report and administrator mutations.

## Verification and boundaries

`CommunityIntegrationTest` uses PostgreSQL Testcontainers, Flyway, the real HTTP
security filter chain, and accepted playback progress. It covers profile isolation,
duplicate/concurrent mutations, qualification boundaries, account-level votes,
post-moderation bypass attempts, aggregates, minimal public data, report deduplication,
audit rollback, role/CSRF/input denials, rate limits and migration from V5.
Run `./mvnw -pl services/core -Dit.test=CommunityIntegrationTest verify`, then root
`clean verify` (use `mvnw.cmd` on Windows with JDK 21 and Docker).

Local verification on 2026-09-19: root `mvnw.cmd clean verify` passed all 116 tests
(zero failures, errors or skips), including 14 community integration tests on real
PostgreSQL and the existing Kafka/Mailpit integration suites. All three reactor
services built successfully. The new OpenAPI 3.1 contract passed
`openapi-spec-validator`; `git diff --check` passed. These are local checks,
not remote CI or deployment evidence.

No frontend, Media delivery, Analytics, external moderation, new Kafka topic,
outbox publisher, production deployment or real HLS integration is implemented
by this milestone. Existing profile deletion events remain the lifecycle contract
for future Analytics cleanup.
