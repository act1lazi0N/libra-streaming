# Analytics: Bootstrap from Core (Milestone RA02.4)

Verification date: 2026-11-16.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

A new or rebuilt Analytics database must not depend on how long Kafka keeps `core.*.v1`. RA02.4 lets Analytics
read the catalog and profile state as versioned snapshots from Core, and replay `core.playback.v1` from Core's
transactional outbox with the original event identity. It shares the gate of [RA02.1](analytics-ingestion-ra02-1.md).

Done condition (plan): snapshots and live events do not lose updates; replay does not add watched time again; the
bootstrap can run again and gives the same result. Replay into a separate data area moved to RA09.2.

Contract and migration changes:

- **New contract** `contracts/http/core-internal-analytics.v1.json` 1.0.0 (Core is the server), three operations
  under `/internal/v1/analytics/`: `GET snapshots/catalog`, `GET snapshots/profiles`, `GET events/playback`.
  Existing contracts are unchanged; the V1 event schemas are reused for the replayed events.
- Core migration `V9__index_outbox_for_analytics_bootstrap.sql`: two indexes on `outbox_events`, no data change.
- Analytics migration `V5__create_bootstrap_progress.sql`: `bootstrap_progress`.
- New service identity **Analytics → Core** (the reverse direction of RA01.3), configured on both sides:

| Side | Variables | Holds |
| --- | --- | --- |
| Core | `CORE_ANALYTICS_SERVICE_AUTH_ENABLED`, `CORE_ANALYTICS_SERVICE_PUBLIC_KEY`, `CORE_ANALYTICS_SERVICE_KEY_ID` | Analytics' public key and kid |
| Analytics | `ANALYTICS_BOOTSTRAP_ENABLED`, `…_CORE_BASE_URL`, `…_PRIVATE_KEY`, `…_PUBLIC_KEY`, `…_KEY_ID`, `…_GENERATION` (1), `…_PAGE_SIZE` (200) | the key pair |

  Compose maps both from one set of `ANALYTICS_BOOTSTRAP_*` variables in `.env` (`.env.example` lists them empty);
  only the Analytics container receives the private half. Everything is disabled by default.

## Core side

**Authentication.** Core's internal chain (`InternalServiceSecurity`) now pins two keys and selects the decoder by
`kid` before any signature check; an unknown or missing `kid` is 401. The Analytics token rules mirror Media's:
RS256, issuer `libra-analytics-services`, subject `libra-analytics`, sole audience `libra-core-internal`, purpose
`service-access`, a `jti`, `iat`/`exp` with at most 60 s lifetime and zero leeway. The key must be at least 2048
bits and differ from the playback key, Core's outbound Analytics key, Media's key and Core's own Media control key
(Core holds that private key; the review found this check missing); equal Media and Analytics key IDs refuse
startup.

A token's authorities are its scopes **intersected with the scopes its verified subject may hold**:
`libra-media` → `media.bindings:read`, `libra-analytics` → `analytics.bootstrap:read`. A token signed with Analytics'
key that claims `media.bindings:read` therefore gets 403 on the Media endpoint, and the reverse. Media's behaviour is
unchanged (its existing tests pass unmodified).

**Snapshots** (`AnalyticsBootstrapStore`, one repeatable-read transaction per page, `Cache-Control: no-store`,
`limit` 1–500, default 200, 400 problem for a bad `after`/`limit`):

- *Catalog*, ordered by `contentId`: each node at Core's current `version` with exactly what the latest catalog
  event would carry (`published` revision and `active` binding only while published) plus `publishedAt`. Core also
  increments the version for changes that emit no event (draft edits, candidate bindings), so a snapshot version can be
  higher than the latest event's; the published state at that version is the state that event carried. The test
  compares each item with the payload of the node's latest outbox event.
- *Profiles*, ordered by `profileId`: live profiles at their version, plus a tombstone (`deleted = true`, the event's
  account and version) for every `ProfileDeleted` in the outbox. Core deletes profile rows, so the outbox is the only
  record of a deletion.
- *Playback*, ordered by `(created_at, event_id)` with an opaque cursor: every `core.playback.v1` outbox row,
  serialized exactly as the outbox publisher serializes it for Kafka (`DeliveryStore.claim`).

Each query has a first-page and a next-page form instead of `? IS NULL OR id > ?`, so the range is always an index
condition even when PostgreSQL switches the prepared statement to a generic plan. Pages are not mutually
consistent; Analytics' version rules absorb that (below). The playback order is the insert
time, not the commit time: an event whose transaction commits after a page passed its position is not replayed, but
the publisher still sends it live, and Analytics consumes the live topic at the same time.

## Analytics side

`bootstrap.application.Bootstrap.run(generation, pageSize)` reads three sources in order:

1. **Profiles** — each item through `ProfileProjection.apply(...)`: the same rules as live events (newer version
   wins, tombstone terminal, erasure of playback links on deletion). Profiles go first so that tombstones exist before
   playback is replayed; the result would be the same in another order.
2. **Catalog** — each item becomes a `CatalogNode` and goes through the same `upsert` as a live event (version rule,
   identity check). A snapshot gives the publication time at its version.
3. **Playback** — each event goes through `EventIntake` like a live record: same decoder, same receipt, same
   projection. An event that was already consumed live is a duplicate; one that arrives live later is a duplicate
   then.

Progress (`bootstrap_progress`: cursor, applied, rejected per generation and source) is stored after each page. For
profiles and catalog, the items and the new position commit in one transaction; playback events commit one by one,
as live records do, and the position follows. A crash repeats at most one page, and repeating it changes nothing.
An item that can never become data (an identity conflict, an invalid event) is counted as `rejected`, not parked.
A page that names a further page but has no items, or repeats the cursor, stops the run (`PAGE_WITHOUT_PROGRESS`).

`HttpCoreSnapshots` signs a fresh token per request (`BootstrapTokens`), uses a 2 s connect and 10 s request timeout,
no redirects, and a 4 MiB body limit. It accepts only `200 application/json` bodies that match the contract exactly:
closed objects, duplicate keys rejected, the RA02.1 rules for revisions and bindings, strictly ascending items after
`after`, `next` only on a full page and equal to the last item. Anything else is `SnapshotUnavailableException` with a
fixed code; no body, query or token text appears in a message.

`BootstrapRunner` (only when `ANALYTICS_BOOTSTRAP_ENABLED=true`) starts one daemon thread after startup and retries
with back-off from 5 s to 60 s until the generation completes. It logs only the exception class name. Gauge
`libra.analytics.bootstrap.completed`, counter `libra.analytics.bootstrap.failures`.

**Running again.** A new `ANALYTICS_BOOTSTRAP_GENERATION` reads everything again from the start. Every step is
idempotent, so the result equals the previous one; `BootstrapIntegrationTest` compares every column of the four
projection tables after generation 1 and generation 2, and after a run interrupted on the second catalog page and
resumed.

## Verification

| Suite | Module | Kind | Tests | What it proves |
| --- | --- | --- | --- | --- |
| `AnalyticsBootstrapIntegrationTest` | Core | integration, PostgreSQL | 5 | paging with `limit` 1–2 returns every node, profile and tombstone, and playback row exactly once in order; each catalog item equals its node's latest outbox payload and version; replayed events deep-equal the publisher's record; 400 problems for bad cursors and limits; `no-store`; paging across concurrent publish/unpublish |
| `InternalServiceSecurityIntegrationTest` | Core | integration, PostgreSQL | 6 | Analytics token reaches only the bootstrap reads; scope cannot cross identities; invalid claims, keys, `kid`, algorithms, cookies and playback tickets are refused without echoing the token; Media unchanged |
| `InternalServiceSecurityAnalyticsTest` | Core | unit | 4 | key reuse (small, playback, outbound Analytics, Media, Media control), equal key IDs, disabled identity |
| `BootstrapTest` | Analytics | unit | 11 | page order, position per page, resume after a failure, skipped completed source, rejections counted, `PAGE_WITHOUT_PROGRESS`, new generation |
| `HttpCoreSnapshotsTest` | Analytics | unit, local HTTP server | 55 | request shape and token per call; every body rule; status, redirect, content type, size and timeout failures; no body text in messages |
| `BootstrapPropertiesTest`, `BootstrapTokensTest` | Analytics | unit | 28 + 3 | settings validation; exact token header and claims, key checks |
| `BootstrapIntegrationTest` | Analytics | integration, PostgreSQL + local Core stand-in | 5 | snapshots vs live events in both orders (catalog versions, tombstones); live then replayed playback equals canonical sums, replaying again changes nothing, an invalid event is counted; generation 2 and an interrupted-and-resumed run equal an uninterrupted run in every column; snapshot pages and replay running concurrently with live delivery (10 rounds each) |

The shared gate of RA02.1–RA02.5 is in [RA02.5, Gate](analytics-ingestion-hardening-ra02-5.md#gate).

## Limits

- No test runs Analytics against a real Core process. Core's endpoints are proven against Core's real use cases and
  outbox (`AnalyticsBootstrapIntegrationTest`), and Analytics' client and bootstrap against a local stand-in that
  serves contract-shaped pages built from the shared fixtures. The contract file and the Core test that compares each
  item with the outbox are what tie the two together. A Compose run with both enabled is for RA09.4.
- Freshness: the bootstrap does not yet gate the freshness checkpoint. RA04.2 must not confirm a checkpoint for a
  generation that has not completed.
- The outbox is never purged today, so the playback replay covers all history. Once RA09.2 adds outbox retention,
  history older than it has to come from an Analytics backup instead.
- Snapshot `publishedAt` is Core's `published_at` column; an event-built node uses the `CatalogPublished`
  `occurredAt`. Core sets them from two reads of the same clock in one transaction, so they can differ by a few
  microseconds between a bootstrapped and an event-built database. A snapshot node's `changed_at` is Analytics' clock
  at the fetch, not an event time. The test stand-in serves `publishedAt = occurredAt`, so neither difference is
  exercised by a test.
- The Media decoder does not compare its key with the Media control key; that check is outside RA02.
- `rejected` items are counted, not kept: there is nothing to redrive, because Core's own state produced them.
- Next per plan: RA02.5, fault injection and convergence.
