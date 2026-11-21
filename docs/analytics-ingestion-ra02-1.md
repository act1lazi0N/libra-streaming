# Analytics: Catalog and Profile Ingestion (Milestone RA02.1)

Verification date: 2026-11-16.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA02.1 is the first consumer milestone of the Recommendation & Analytics roadmap. Its baseline is `c4f6610`
(RA01.2–RA01.4). Analytics now consumes `core.catalog.v1` and `core.profiles.v1` and keeps a versioned read model
of the catalog tree and of the profile lifecycle, with terminal profile tombstones. RA02.1–RA02.5 were implemented in
one pass and share one gate (see [RA02.5](analytics-ingestion-hardening-ra02-5.md#gate)); each has its own document.

Done condition (plan): an old event never overwrites newer state, and a deleted profile is never recreated.

Contract and migration changes:

- No HTTP or event contract changed. Analytics implements the RA01.1 consumer rules for the two topics.
- New migration `V2__create_catalog_and_profile_projections.sql`: `processed_events` (one receipt per applied event),
  `catalog_nodes` (latest state per node), `profiles` (latest state per profile, `deleted` = tombstone).
- Dependency: `spring-kafka` → `spring-boot-starter-kafka` (Kafka auto-configuration in Boot 4); test dependency
  `testcontainers-kafka`.

## Design

### One intake path

Every Core record becomes data through `ingestion.application.EventIntake`, whatever delivered it: the live
consumer, a dead-letter redrive (RA02.3) or the bootstrap replay (RA02.4). The intake:

1. decodes and validates the record (`JsonCoreEventDecoder`, below); a violation is an `EventRejection`;
2. in **one transaction**, inserts the receipt `processed_events(event_id, topic, event_type, aggregate_id,
   aggregate_version)` with `ON CONFLICT DO NOTHING` and, only when the row is new, applies the projection;
3. returns after the commit. The listener runs with `AckMode.RECORD`, so the Kafka offset is committed only after the
   method returns, that is after the database commit.

A redelivered event finds its receipt and changes nothing (`DUPLICATE`). An event ID that is already stored for a
different topic, type, aggregate or version is `EVENT_CONFLICT` and is parked (RA02.3). Two transactions applying
the same event ID at once serialize on the receipt's primary key: one applies, the other sees a duplicate.

Each Core topic has its own listener container and consumer group (`analytics-catalog-v1`,
`analytics-profiles-v1`, `analytics-playback-v1`), so each topic's progress can later be measured on its own
(RA04.2).

### Decoding

`JsonCoreEventDecoder` is a hand-written mirror of the V1 event schemas, not a generic schema engine. The shared
corpus `contracts/fixtures/analytics-v1` pins it to the schemas (`CoreEventContractTest`, see Verification):

- The envelope is open, as the schema says; every payload object (catalog payload, revision, metadata, binding,
  profile payload) is closed. Duplicate JSON keys are rejected.
- Every format and bound is checked before a value is used: dashed UUIDs (either case), RFC 3339 date-times with
  seconds and an explicit offset, `schemaVersion` exactly 1, the closed enums, genre/language/image patterns,
  string lengths counted in code points, array sizes.
- It is narrower than JSON Schema only in the fail-safe direction, like Core's `StrictContractJson`: an integer must
  be written as an integer (`1.0` is rejected), years start at 0001, and a record above 256 KiB is rejected.
- The Kafka key must equal `aggregateId`, which must equal the payload's own identity.

Semantic rules checked in the domain records (`ingestion.domain`), each a `SEMANTIC_INVALID` rejection:

| Topic | Rule | Source |
| --- | --- | --- |
| all | key = `aggregateId` = payload identity | RA01.1 |
| catalog | `CatalogPublished`/`CatalogAvailabilityChanged` carry `published`; `CatalogUnpublished` carries neither `published` nor `active` | RA01.1 |
| catalog | MOVIE/SERIES have no parent and ordinal; SEASON/EPISODE have both | Core's table constraint |
| catalog | `active.contentId` equals `contentId` | Core's binding ownership |

The last two rules are new; Core cannot produce events that break them. A rejection's message is a fixed code
(`SCHEMA_INVALID:PLAYBACK_PAYLOAD`, ...) and never contains payload text or identifiers.

### Catalog: latest version wins

`catalog_nodes` holds one row per content node: version, kind, parent, ordinal, the published revision and active
binding exactly as Core sent them (`jsonb`), copies of genres, access tier, binding state and duration for later
queries, `published_at` and `changed_at`.

- `JdbcCatalogStore.upsert` is one `INSERT … ON CONFLICT DO UPDATE … WHERE stored.version < EXCLUDED.version`
  statement. Concurrent writers of a node (live consumer, bootstrap, redrive) serialize on the row, and the newest
  version stays whatever order they commit in. An equal or older version changes nothing (`STALE`).
- Kind and parent never change in Core. A node whose kind or parent differs from the stored one is
  `EVENT_CONFLICT`, not an older version.
- The parent is not a foreign key: Core gives no order across aggregates, so an episode may arrive before its season.
  `CatalogStore.placement(contentId)` walks the parent chain when it is read and reports the root movie/series, whether
  the chain is complete, and whether the node and every ancestor are published. Unpublishing a series therefore hides
  its episodes without an event per descendant, as RA01.1 requires.

**Publication time.** V1 events carry no `publishedAt`; the `occurredAt` of the latest `CatalogPublished` stands
for it (RA01.1). `CatalogAvailabilityChanged` keeps the stored time and `CatalogUnpublished` clears it. Designing
RA02.5 showed that a strict "latest state wins" loses this fact when an availability change (v3) arrives before
the publication (v2) it follows: the late v2 is stale, so the node would stay published with no publication time
forever. The store therefore keeps `published_at_version`, and a stale `CatalogPublished` still supplies the time
when its version is the highest publication version known for the currently published node. An unpublication
between them implies a later publication, which overrides it when it arrives, so the stored value converges for
every arrival order. The rest of a stale event is ignored. Three tests pin this (Verification).

### Profiles: terminal tombstones

`profiles` holds the account, version and `deleted` flag per profile.

- A live event (`ProfileCreated`, `ProfileUpdated`) applies only when it is newer and the profile is not deleted.
- `ProfileDeleted` is terminal whatever its version: Core deletes at the final version plus one, so no live version
  can follow. Deleting an unknown profile stores the tombstone, which blocks a later or replayed creation.
- A profile that appears under another account is `EVENT_CONFLICT`.
- When the tombstone is written, every `ProfileErasure` runs in the same transaction. In RA02.2 the playback store
  removes the profile link from facts and sessions; aggregate history stays (RA01.1).
- The tombstone takes a transaction-scoped PostgreSQL advisory lock on the profile in exclusive mode; every writer of
  a profile link takes it in shared mode. A link written concurrently with the deletion is therefore either erased by
  it or sees the tombstone. The key folds the UUID to 64 bits; a collision only serializes two unrelated profiles.

### Layout

```text
com.libra.streaming.recommendation
├── ingestion   domain: Envelope, CoreEvent (CatalogChange, ProfileChange, PlaybackProgress), EventRejection
│               application: EventIntake, CoreEventDecoder, ProcessedEvents, Transactions, IntakeProbe
│               infrastructure: JsonCoreEventDecoder, JdbcProcessedEvents, CoreEventListener, Failures, …
├── catalog     domain: CatalogNode, Placement; application: CatalogProjection, CatalogStore; infrastructure: JDBC
├── profiles    domain: ProfileState; application: ProfileProjection, ProfileStore, ProfileErasure; infrastructure
└── playback    (RA02.2)
```

`BackendArchitectureTest` still holds: application and domain layers use no Spring, Jakarta, Jackson, JDBC or
Kafka class.

## Verification

| Suite | Kind | Tests | What it proves |
| --- | --- | --- | --- |
| `CoreEventContractTest` | unit | 51 | All 32 event fixtures of the shared corpus (14 playback, 11 catalog, 7 profile) reach their declared outcome: `valid` decodes, `schema-invalid` is `SCHEMA_INVALID`, `semantic-invalid` is `SEMANTIC_INVALID`; plus edge cases: duplicate keys, oversized and empty records, key mismatch, uppercase UUIDs, `1.0` integers, lowercase `t`/`z`, missing seconds or offset, year 0000, open envelope, closed payload, `schemaVersion` 2, another topic's event type, title length in code points; no rejection message contains a UUID or payload text |
| `JsonCoreEventDecoderTest`, `IngestionDomainTest` | unit | 3 + 6 | decoder helpers and the semantic rules of the domain records |
| `IngestionIntegrationTest` | integration, PostgreSQL | 21 | newest version wins (v5 then v3), equal version is a no-op, kind/parent change is `EVENT_CONFLICT`; publication time kept, cleared, and supplied by a late `CatalogPublished` in both orders; placement of an episode before its season/series and after the series is unpublished; profile tombstones not revived by later or replayed events, deleting an unknown profile, account change; receipts (duplicate, reused ID); 8 concurrent writers of one event and of one node; RA02.2 and RA02.3 cases |
| `BackendArchitectureTest` | unit | 6 | layers stay framework-free |

The shared gate of RA02.1–RA02.5 is in [RA02.5, Gate](analytics-ingestion-hardening-ra02-5.md#gate).

## Limits

- Snapshot and replay of history older than the Kafka retention arrive in RA02.4; before that, a new Analytics
  database sees only what the topics still hold (`auto-offset-reset: earliest`).
- Freshness checkpoints are not advanced yet: RA04.2 computes them from consumer progress. Until then every answer
  that depends on these projections stays `ANALYTICS_NOT_READY`, as RA01.2 left it.
- The "Không quan tâm"/watchlist effects of a deleted profile do not exist yet (RA06); erasure covers playback only.
- The decoder is checked against the 32 event fixtures and the edge cases listed in Verification, not against a
  generic JSON Schema engine on the Java side.
- Next per plan: RA02.2, playback facts.
