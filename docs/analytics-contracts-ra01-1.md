# Analytics: Contracts and Ownership (Milestone RA01.1)

Verification date: 2026-11-06.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA01.1 is the first milestone of the Recommendation & Analytics roadmap (RA01.1–RA09.4). Its baseline is branch
`features/recommendation-and-analytic` at `cf73744` with a clean tree. It defines who owns which data and decision.
It also defines the HTTP and event contracts between Core and Recommendation/Analytics, the statistics and freshness
semantics, and the error model. The contracts are made executable as a shared fixture corpus. Core's real producer and
consumer code is proven to match the corpus exactly.

The Analytics service is still the deny-by-default scaffold. It does not yet consume any topic or serve any endpoint.
The rules below are therefore **normative for RA01.2 onward**: they describe what the Analytics implementation must
do, not what it already does. They are not evidence of a running projection.

Contract and migration changes:

- `contracts/http/analytics-internal.v1.json` 1.0.0 → **1.1.0**. It adds sanitized error responses and a closed
  `Problem` schema only. The request and response schemas are unchanged, and Core discards every non-200 body, so
  the change is compatible with the deployed Core adapter. The description no longer calls the contract
  "proposed".
- New corpus `contracts/fixtures/analytics-v1/`: a manifest and 79 fixtures. `infra/ci/check_contracts.py` now
  validates the corpus and excludes it from the schema documents.
- Core: `AnalyticsClient` parses downstream bodies with `StrictContractJson` (see finding C1–C3). No Core public
  contract, event schema or Flyway migration changed. Analytics has no migration yet.

### Findings

Each finding was reproduced before it was fixed. The failing run is in the ignored
`tmp/ra01-1-repro-before.log`. Before the fix, Core accepted three downstream bodies that the contract forbids:

- **C1 — epoch timestamps.** Core accepted `"updatedAt": 1788775000` (epoch seconds) as a valid snapshot time and
  returned it as `STALE`. The contract requires an RFC 3339 string.
- **C2 — Base64 UUIDs.** Core accepted a 24-character Base64 encoding of a content UUID, matched it to a published
  movie and returned it as an `ANALYTICS` result. The contract requires the dashed hexadecimal form.
- **C3 — array dates.** Core accepted `"from": [2026, 11, 1]` as the echoed statistics window and returned
  `AVAILABLE`. The contract requires an ISO `YYYY-MM-DD` string.

Jackson's default `java.time` and `UUID` readers caused all three. The Core adapter's private mapper now registers
`StrictContractJson`, which reads `UUID`, `LocalDate` and `Instant` only from JSON strings in the contract form:

- a dashed UUID, case-insensitive;
- an ISO date;
- an RFC 3339 date-time with seconds and an explicit offset.

Anything else becomes `INVALID_RESPONSE`, which leads to `FALLBACK` or `UNAVAILABLE` just like any other invalid
body. An epoch-day number for a date was already rejected before the fix.

Core's accepted profile is slightly narrower than the JSON Schema check, and only in the fail-safe direction:

- at most 9 fraction digits;
- offsets within ±18:00;
- no leap second (`:60`);
- no trailing newline;
- years from 0001 (year 0000 is rejected, as JSON Schema does);
- no braces or `urn:uuid:` around a UUID.

`StrictContractJsonTest` (43 cases) pins these edges. The review that found them is summarized under Gate.

## Ownership

| Data or decision | Authoritative owner | Analytics role |
| --- | --- | --- |
| Accounts, sessions, roles (ADMIN), profile ownership | Core | None. Core checks identity and role before and after every call; Analytics never sees a browser credential |
| Profile lifecycle | Core (`core.profiles.v1`) | Keeps a tombstone per deleted profile; deletes personalization for it |
| Catalog hierarchy, metadata, publication, active media | Core (`core.catalog.v1`) | Keeps a versioned read model for candidates; Core rechecks visibility and READY media on every answer |
| Playback admission, accepted progress, history | Core (`core.playback.v1`) | Stores accepted facts; never accepts browser telemetry directly |
| Asset readiness, HLS delivery | Media | None |
| Qualified views, unique viewers, watched duration, genre interests, candidate ranking | Analytics (derived, own database) | Owns these projections and their checkpoints |
| Public responses, fallback, freshness status shown to users | Core | Supplies a snapshot and its `updatedAt` only |

The three business services keep separate databases. Analytics never reads Core tables, and Core never reads
Analytics tables. A recommendation never grants playback: Core still enforces entitlement at admission.

## Interfaces

| Direction | Interface | Key / scope | Contract |
| --- | --- | --- | --- |
| Core → Analytics | Kafka `core.catalog.v1` | key = content ID; aggregate version = catalog version | `core-catalog-publication.v1.schema.json` |
| Core → Analytics | Kafka `core.profiles.v1` | key = profile ID; aggregate version = profile version | `core-profile-lifecycle.v1.schema.json` |
| Core → Analytics | Kafka `core.playback.v1` | key = session ID; aggregate version = accepted sequence | `core-playback-progress.v1.schema.json` |
| Core → Analytics | `POST /internal/v1/recommendations/query` | service JWT, exact scope `recommendations:read` | `analytics-internal.v1.json` |
| Core → Analytics | `POST /internal/v1/statistics/query` | service JWT, exact scope `statistics:read` | `analytics-internal.v1.json` |

All three event schemas share `core-event-envelope.v1.schema.json`. Core writes events through its transactional
outbox, and the publisher sends them with the aggregate ID as the Kafka key. Delivery is at least once, ordered per
aggregate, and has no global order across topics or partitions. The service JWT rules are those in
[Core Analytics read adapters](core-analytics-adapter.md#dedicated-outbound-service-identity). RA01.3 implements
the receiving side.

## Event consumption rules

These rules apply to every consumed topic:

1. Validate the envelope and the payload against the V1 schema before applying anything. Payloads are closed:
   unknown fields, an unknown `eventType` on the topic or a `schemaVersion` other than 1 are not silently skipped.
   The record is parked as a poison event (DLT in RA02.3), so a new Core version can never be partially decoded.
2. Check the semantic rules the schema cannot express (list below). A violation is a poison event, not a fact.
3. Deduplicate by `eventId`. The dedup record and the projection change commit in one transaction, and the Kafka
   offset is acknowledged only after that commit.

Semantic rules (each has a `semantic-invalid` fixture):

| Topic | Rule |
| --- | --- |
| all | `aggregateId` equals the Kafka key and the payload's own identity (`contentId`, `profileId`, `sessionId`) |
| playback | `aggregateVersion == payload.sequence`; `acceptedWatchedMs <= totalWatchedMs`; `qualified == (totalWatchedMs >= 30000)` |
| playback | A second event for the same `(sessionId, sequence)` with a different `eventId` is a conflict; it is parked, never added. Core never emits one: a non-increasing sequence is not accepted or published. |
| catalog | `CatalogPublished` and `CatalogAvailabilityChanged` carry `published`; `CatalogUnpublished` carries neither `published` nor `active` |

How each topic is applied:

- **Catalog — latest version wins.** The current state per `contentId` is kept with its `aggregateVersion`. An
  event at or below the stored version changes nothing. Visibility follows the parent chain (`parentId`):
  unpublishing a parent emits no event per descendant. The time of the latest `CatalogPublished` event
  (`occurredAt`) is the publication time used for "newly published", because V1 carries no `publishedAt`.
  `published.createdAt` is the draft revision's creation time. PostgreSQL's clock sets it, not Core's application
  clock, and it carries no business meaning for Analytics.
- **Profiles — tombstone.** `ProfileDeleted` at version *v* is terminal. Any later or replayed lifecycle event and
  any playback event for that profile must not recreate personalization for it. Aggregate statistics keep the
  historical facts (see Statistics). Tombstones are never purged before the matching replay guard exists (RA09.2).
- **Playback — facts, not state.** Each accepted event is an additive fact keyed by `(sessionId, sequence)`, so a
  late or reordered event still adds its increment. Watched time sums `acceptedWatchedMs` only. `totalWatchedMs`
  is never added; it serves to detect gaps (the session's highest-sequence total versus the sum of received
  increments) and to show convergence. Seeking earns nothing because Core sends `acceptedWatchedMs = 0`.

## Statistics semantics (V1 statistics endpoint)

| Metric | Unit | Definition | Bucket |
| --- | --- | --- | --- |
| `qualifiedViews` | sessions | Sessions with at least one event where `qualified = true`; at most one per session | UTC date of the qualifying event with the lowest sequence |
| `uniqueViewers` | accounts | Distinct `accountId` among the sessions counted in `qualifiedViews` for the whole window; not a sum of daily uniques | same as views |
| `acceptedWatchedMs` | milliseconds | Sum of `acceptedWatchedMs` of the accepted events in the window | UTC date of each event's `occurredAt` |

- The window is the inclusive UTC date range `[from, to]`: at most 31 days, `from <= to`, and `to` no later than the
  current UTC date. Analytics validates the window itself (400 `INVALID_REQUEST`) instead of trusting Core. The
  response echoes `from`/`to` exactly.
- `uniqueViewers <= qualifiedViews` always holds, and every count is a nonnegative int64.
- A late event corrects the bucket it belongs to. A late qualifying event with a lower sequence can move a session's
  qualification to an earlier date. It is still one view.
- Several profiles of one account count as one unique viewer. Deleting a profile removes personalization and the
  profile link but keeps aggregate history: these are platform totals, not personal data.
- The response carries aggregates only, never account or profile identifiers.

## Freshness

`updatedAt` is the **confirmed checkpoint** of the projections an answer depends on: the latest instant *T* at which
Analytics had committed every record available on those input partitions. A background checkpoint advances *T*
while consumer lag is zero. A quiet topic therefore stays fresh, and a stalled consumer stops advancing *T*.

- Statistics depend on `core.playback.v1`. Recommendations depend on all three topics; their `updatedAt` is the
  oldest of the three checkpoints.
- `updatedAt` is never set from the request time and never from the newest event's `occurredAt`. If it were, a quiet
  topic would look stale, and a request would make stale data look fresh.
- Before the first confirmed checkpoint, the server answers **503 `ANALYTICS_NOT_READY`**, never 200 with zeros. A
  real zero after a checkpoint is a valid 200.
- Core never accepts a future `updatedAt`, marks a snapshot older than 15 minutes `STALE`, and turns any failure into
  `UNAVAILABLE` or `FALLBACK` with a null timestamp. A lost connection is never shown as a zero.
- **Clock skew.** Core has no tolerance for a future `updatedAt`, so Analytics reports a checkpoint no later than
  its own clock minus 5 seconds. Both hosts run synchronized time (NTP) with skew below 5 seconds. When checking
  "`to` is not after today", Analytics uses the UTC date of its clock plus 5 seconds. A request Core accepted just
  after UTC midnight is then not rejected by an Analytics clock still on the previous day. A larger skew shows up as
  intermittent `INVALID_RESPONSE`/`UNAVAILABLE` in Core and is an operations fault, not data.

## Errors

Every non-200 answer is `application/problem+json`, with the closed `Problem` schema `type`, `title`, `status`,
`detail`, `code` and `correlationId`. It never contains tokens, SQL, stack traces, raw payloads or identifiers.

| Status | `code` | Core outcome |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` (schema or window violation) | `UNAVAILABLE` / `FALLBACK UNAVAILABLE` |
| 401 | `AUTHENTICATION_REQUIRED` (missing or invalid service JWT) | same |
| 403 | `ACCESS_DENIED` (wrong scope) | same |
| 413, 415, 429 | `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `RATE_LIMITED` | same |
| 500 | `INTERNAL_ERROR` | same |
| 503 | `ANALYTICS_NOT_READY`, `ANALYTICS_UNAVAILABLE` | same |
| 200 with a body that violates the schema or a body rule below | — | `INVALID_RESPONSE` |
| 200 whose body exceeds 65,536 bytes or arrives after the 500 ms budget | — | `UNAVAILABLE` |

Body rules Core enforces:

- `profileId` echoes the request.
- The statistics window echoes the request.
- `updatedAt` is not in the future.
- `uniqueViewers` does not exceed `qualifiedViews`.
- There are at most 100 candidates.
- The response is uncompressed `application/json`.

Error bodies are `application/problem+json` whatever the `Accept` header says. Core sends
`Accept: application/json` and never reads a non-200 body.

Analytics returns movie and series IDs only, in rank order, without duplicates, at most `limit` of them. Core
tolerates episodes, unknown IDs and duplicates by dropping them, and never pads.

## Versioning and compatibility

- **V1 is frozen.** `test_analytics_contracts.py` pins a canonical SHA-256 of every V1 subschema. The pinned
  subschemas are the envelope, the three event schemas, the Core catalog schemas the catalog event embeds, the five
  HTTP component schemas, and each operation's ID, scope, parameters, request body and 200 response. Any change to a
  type, format, bound, enum or field fails the gate. The test also names the field lists for readable failures.
- **HTTP:** both sides reject unknown fields, so adding a field or changing a meaning (for example the
  recommendation reason and algorithm version planned for RA05.3) requires `/internal/v2/...`. V1 stays served
  until Core no longer calls it. Purely additive documentation, such as the 1.1.0 error responses, keeps the path.
- **Events:** a new payload field or meaning gets a new schema file with `schemaVersion: 2`. Rollout is
  consumer-first: Analytics must accept v2 before Core emits it. Until then, a v1-only consumer parks v2 records
  rather than misreading them.
- **Corpus:** V1 fixture entries are append-only. A future change must keep every existing `valid` fixture valid and
  every `*-invalid` fixture rejected.

## Compatibility corpus

`contracts/fixtures/analytics-v1/manifest.json` lists every fixture: its contract and JSON pointer, `expect`
(`valid`, `schema-invalid` or `semantic-invalid`) and the `rule` it pins. Each `schema-invalid` fixture also
pins its single expected violation (`schemaError`: JSON Schema keyword and instance path). If it fails for any other
reason, the gate fails. Templates use `{{name}}` placeholders. The manifest holds sample values and states how the
time placeholders relate to the consumer's clock `now`. Tests substitute real identities and times. Two templates
use unquoted placeholders for non-string values; they become JSON only after substitution. There are 79 fixtures: 27 valid, 34 schema-invalid and 18
semantic-invalid. By contract, 32 are events (14 playback, 11 catalog, 7 profile) and 47 are HTTP (12 requests, 30
responses, 5 problem bodies).

| Proof | What it shows | Where |
| --- | --- | --- |
| Schema gate | Every `valid` and `semantic-invalid` fixture passes its schema with format checks (uuid, date, date-time required to be active); every `schema-invalid` fixture fails it; no unlisted, missing or duplicate files; no unknown placeholders | `infra/ci/check_contracts.py` |
| Gate self-tests | The gate fails on: mislabelled, missing, unlisted and duplicate fixtures; unknown placeholders; a wrong or missing `schemaError`; an HTTP fixture without a component pointer. Every V1 target has valid and invalid cases. Every Core event type is pinned to a producer fixture. V1 is frozen by hash | `infra/ci/test_analytics_contracts.py` (9 tests) |
| Core producer | The exact records the outbox publisher sends (`DeliveryStore.claim`: topic, key, body) equal the 12 producer fixtures. The fixtures cover: profile created/updated/deleted; movie published/unpublished/availability failed; series and episode published; four playback events (start from PAUSED, increment, qualifying at 35 s, seek earning 0). `correlationId` must be the one the use case was called with. `occurredAt` must be the test clock's instant at that step. The revision `createdAt` comes from PostgreSQL's clock, so only its RFC 3339 UTC form is checked. `eventId` must be a distinct UUID | `AnalyticsEventContractIntegrationTest` (real PostgreSQL) |
| Core consumer | For each of the 35 downstream bodies served by a local HTTP fixture, Core's real adapter reaches the declared outcome; ANALYTICS item order and statistics values are checked too. Core's request bodies equal the two canonical query fixtures | `AnalyticsReadIntegrationTest#everyContractFixture…` (real PostgreSQL) |
| Analytics side | Not yet: RA02.1/RA02.2 consumers, RA04.2 statistics and RA05.3 recommendations must load this manifest and prove the same expectations | UNVERIFIED (no Analytics runtime yet) |

## Known gaps handed to later milestones

- V1 catalog events carry no `publishedAt`. Analytics uses the `CatalogPublished` `occurredAt` (RA02.1, RA05.2).
- V1 playback events carry no session-open time, media duration or opening asset facts beyond `assetId`/`assetVersion`
  (RA07.1, versioned).
- There are no watchlist or "not interested" events (RA06.1/RA06.2), no Core snapshot API for bootstrap (RA02.4), and
  no recommendation reason or algorithm version in V1 (RA05.3, `/internal/v2`).
- Event `semantic-invalid` rules are specified and fixture-backed, and Core is shown never to produce them. Their
  rejection by Analytics is UNVERIFIED until RA02.

## Gate

Offline root `./mvnw -o clean verify` on 2026-11-06 (JDK 21, Docker Desktop, real PostgreSQL/Kafka/SeaweedFS,
FFmpeg) passed in about 12 minutes:

| Module | Unit | Integration |
| --- | --- | --- |
| Core | 136 | 147 |
| Media | 244 | 214 |
| Recommendation/Analytics | 2 | — |

- `python infra/ci/check_test_reports.py`: PASS, 95 Maven suites and 743 tests, with zero failures, errors or skips.
  The M36 baseline was 93 suites and 697 tests. The new suites are `StrictContractJsonTest` (43 tests) and
  `AnalyticsEventContractIntegrationTest` (2); `AnalyticsReadIntegrationTest` gained 1 test.
- `python infra/ci/check_contracts.py`: PASS. It covers all contracts, the three Media examples and 79 analytics
  fixtures.
- `python -m unittest discover -s infra/ci -p "test_*.py"`: PASS, 118 tests, 9 of them new for RA01.1.

A read-only `code-reviewer` pass found one confirmed defect. The fixture test built the `+07:00` timestamp with
`OffsetDateTime.toString()`, which drops `:00` seconds, so it would have failed about once a minute of wall-clock
seconds (1 in 60 runs). It now uses `ISO_OFFSET_DATE_TIME`. The review's other risks were also addressed:

- the frozen-surface test pinned field names only (now hashes);
- schema-invalid fixtures could fail for the wrong reason (now `schemaError`);
- producer correlation and time values were read back from the record (now supplied by the test);
- clock skew and midnight rules were missing;
- the error table was imprecise;
- year 0000 was accepted;
- the gate passed HTTP fixtures without a pointer;
- the contract lacked explicit 404/405/500 responses.

Tightening the producer test then showed that the revision `createdAt` is set by PostgreSQL's clock. This is now
documented as having no business meaning (see Event consumption rules). Raw logs: `tmp/ra01-1-*.log`.

## Limits

- No Analytics runtime: the consumer, statistics, freshness and error rules are specified, not executed, by
  Analytics. RA01.2 adds the service skeleton with PostgreSQL/Flyway; RA01.3 adds service JWT verification.
- The producer proof covers the Core use cases listed above. Other paths that emit the same event types (for example
  a default-profile promotion emitting `ProfileUpdated`) share the payload builders but are not separately pinned.
- The consumer proof uses a local HTTP fixture, not a deployed Analytics service, TLS or network faults beyond those
  already in `AnalyticsReadIntegrationTest`.
- The 5-second clock-skew rule and the checkpoint margin are specified for Analytics; Core still has no tolerance
  for a future `updatedAt` (unchanged behavior).
- Local evidence only; the CI workflow was not run remotely.
- Next per plan: RA01.2. It adds the feature-first Analytics skeleton, with framework-free application and domain
  layers, PostgreSQL, Flyway, transactions, health/readiness and configuration, and an architecture test. It must
  not create empty business packages.
