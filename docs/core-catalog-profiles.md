# Core: Profiles and catalog (Milestone 03)

Core implements owned profiles, immutable catalog metadata revisions, explicit
publication, PostgreSQL search, and versioned Media readiness projections.
The [HTTP contract](../contracts/http/core-catalog-profiles.v1.json) uses `/v1`
internally and `/api/core/v1` through Nginx. Browser authentication and CSRF follow
[Core identity](core-identity.md). All admin routes require a live ADMIN session;
administrators do not gain playback entitlement from that role.

## Profiles

- Registration and administrator bootstrap create one default profile atomically
  with identity/email writes. V3 backfills existing accounts and lifecycle events.
- Accounts keep 1–5 profiles. Account row locks serialize creation, deletion, and
  renaming across instances. Deleting the default promotes the oldest remaining
  profile (creation timestamp, then UUID). Both changes emit lifecycle events.
- Every lookup and mutation uses the authenticated account and explicit profile
  ID. Another account's profile returns `404`, including to administrators.
- Rename/delete require `expectedVersion`. Name is plain text, nonblank, at most
  80 characters. A profile never changes identity, role, verification or Premium.
- `ProfileCreated`, `ProfileUpdated`, and `ProfileDeleted` enter `core.profiles.v1`
  through the transactional outbox. Payloads contain only account/profile IDs,
  without display names, email or credentials. Aggregate version is profile version;
  a deleted profile emits its final version plus one.

Milestone 5 adds [history and playback sessions](core-playback.md), with cascading
removal when a profile is deleted. [Milestone 6 watchlists](core-community.md) use
the same ownership checks and cascade on deletion. Analytics personalization remains
later work. Analytics must consume
`ProfileDeleted` as a tombstone and reject older lifecycle events. The current
outbox appends durably; [Milestone 7](core-integration-operations.md) adds its background publisher.

## Catalog and revisions

`MOVIE` and `SERIES` are roots. `SEASON` belongs to a series; `EPISODE` belongs to
a season. Kind, parent and ordinal are immutable in V1. Siblings have unique
ordinals from 1–10000. Child results sort by ordinal then UUID. There is no catalog
hard-delete operation; unpublication is the visibility control.

Each metadata edit appends an immutable revision. Metadata contains title,
description, genre slugs, release year, language, plain-text credits, opaque image
references and `FREE`/`PREMIUM` access tier. Image references are identifiers, not
URLs or private media object paths; Core does not fetch them. Clients must render
text as text. Movie/episode tier belongs to that playable unit, independently of
container metadata. Profile defaults and catalog metadata accept unverified users
where the API is public or owned; playback/Premium require verified email through
the [central entitlement policy](core-entitlements.md).

Admin state includes both draft and published revisions, plus candidate and active
bindings. `expectedVersion` is the catalog control version, not the metadata
revision number. Edit, bind, publish and unpublish each increment it; an event that
changes the active published asset's availability also increments it. A conflict
returns `409 VERSION_CONFLICT`; reread the state before an intentional retry.

Publication copies the current draft revision pointer and candidate binding into
the public snapshot. Movies and episodes require that exact binding to be READY;
series and seasons can publish without media. Publishing a container does not
claim that its descendants are published or playable. READY events never publish
a draft automatically.

Replacement creates a new candidate binding to an immutable `(assetId,
assetVersion)`. The existing public revision and active binding remain selected
while the candidate processes or fails. An explicit publication selects the new
candidate only once it is READY. The same asset/version cannot be assigned to
two bindings, including across content IDs.

Public reads join only the published metadata revision and require all ancestors
to be published. Unpublishing a series/season hides its descendants in search,
child listing and direct lookup without deleting their individual editorial
states. Republish restores visibility subject to those states. Future admission
and renewal must apply the same effective-visibility rule and current entitlement.
`mediaReady` is an availability fact, not a playback permission or HLS URL.

Search uses PostgreSQL `simple` full-text token matching over published title and
description. It is case-insensitive token matching, without stemming, fuzzy matching
or accent folding. Optional kind/genre/year/language/tier filters use bound SQL
parameters. Genres are exact slugs; filters are ANDed. Default search includes all
four kinds. Search sorts by publication time descending then UUID; pagination is
20 by default, 100 maximum, with offset limited to 10000. Ordering is deterministic
for unchanged data; concurrent publication can move entries between offset pages.

## Media contract and failure handling

The Core Kafka listener consumes `media.assets.v1` with group
`core-media-projection-v1`. The producer key and envelope `aggregateId` must both
equal `payload.assetId`. The
[Media event schema](../contracts/events/media-asset-state.v1.schema.json) requires
the exact Core-owned content/binding/asset/version tuple. The Media producer must
receive this binding from the future authenticated integration, not invent it.
No HTTP endpoint, including admin, accepts a readiness state.

`aggregateVersion` is Media's monotonic event version for the asset, distinct from
the immutable `assetVersion`. Core keeps the latest accepted projection version
per binding. Duplicate event IDs and stale versions have no repeated effects;
receipts and projection/outbox changes commit in one PostgreSQL transaction.
Event IDs are immutable producer identities and must never be reused with another
payload. Known old bindings can update only themselves, never the selected binding.
READY requires a positive duration of at most seven days. A newer failure of the
active binding changes `mediaReady` to false while retaining editorial metadata.

The listener acknowledges each Kafka record after the database operation succeeds.
Transient failures get two retries after the first attempt, one second apart;
Spring's non-retryable conversion failures can go straight to the DLT. Failed
input is sent to `media.assets.v1.DLT` on the original partition. Failed DLT
publication throws rather than acknowledging the source record. A crash between
database commit and Kafka offset commit can redeliver the record safely.

Provision `media.assets.v1` and `media.assets.v1.DLT` with matching partition counts
and appropriate retention before starting Core. The existing development broker
can auto-create topics. `CORE_MEDIA_LISTENER_ENABLED` defaults to `true`; disabling
it leaves new candidates PENDING until consumption resumes. Configure Kafka TLS,
SASL and ACLs through standard Spring Kafka properties outside isolated local
Compose: only Media may produce asset states, Core may consume them and produce
the DLT. Broker access is the trust boundary; no service HTTP JWT adapter is added
in this milestone. Public exposure of the development broker would allow forged
readiness events.

DLT inspection/redrive, the general outbox publisher and operational metrics are
implemented in [Milestone 7](core-integration-operations.md). Redrive preserves original
event identity. Operators must retain receipts for the supported replay window;
automated receipt deletion is not enabled.
Actual Media transcoding/storage/HLS and Analytics consumers remain separate work.

Catalog outbox events use
[core-catalog-publication.v1.schema.json](../contracts/events/core-catalog-publication.v1.schema.json).
Consumers must honor parent hierarchy and control versions; a parent unpublication
does not emit one event per descendant. Profile lifecycle uses
[core-profile-lifecycle.v1.schema.json](../contracts/events/core-profile-lifecycle.v1.schema.json).

## Verification

```powershell
.\mvnw.cmd -pl services/core '-Dtest=CatalogProfilesIntegrationTest' test
.\mvnw.cmd clean verify
```

The new integration suite uses actual PostgreSQL/Flyway, Kafka and HTTP security
filters. It covers upgrade backfill, concurrent profile limits/deletion, ownership,
CSRF, stale edits, hierarchy, published search isolation, processing/failing
replacement, duplicate/stale/mismatched Media events, transaction rollback, Kafka
listener restart/replay and dead-letter routing. Synthetic Media events prove the
Core contract; they do not prove FFmpeg, private storage or protected HLS delivery.

Local verification on 2026-09-15: the focused milestone suite passed 13/13 tests;
root `clean verify` completed at 10:55:42 +07:00 with 58 tests, zero failures,
errors or skips (56 Core, one Media wiring, one Analytics wiring). Core includes
49 PostgreSQL/Kafka/Mailpit integration tests. The five JSON contract documents
parsed and all 56 local references resolved; this was a syntax/reference check,
not a full OpenAPI schema-validator run. Compose configuration parsing and
`git diff --check` also passed. No deployment or real Media pipeline was verified.

Implementation references: [PostgreSQL full-text search](https://www.postgresql.org/docs/current/textsearch-controls.html)
and [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html).
