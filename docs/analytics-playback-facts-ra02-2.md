# Analytics: Playback Facts (Milestone RA02.2)

Verification date: 2026-11-16.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA02.2 consumes `core.playback.v1`. Each accepted heartbeat becomes an additive fact keyed by session and sequence,
and the session totals are maintained in the same transaction. It shares the intake, receipts and gate of
[RA02.1](analytics-ingestion-ra02-1.md).

Done condition (plan): a redelivered event does not add time; Kafka is acknowledged only after the database commit;
playback that arrives before its catalog or profile metadata is kept and becomes usable when the metadata arrives.

Contract and migration changes:

- No contract changed.
- New migration `V3__create_playback_facts.sql`: `playback_facts` (primary key `(session_id, sequence)`, unique
  `event_id`) and `playback_sessions` (running totals per session).

## Design

**Facts, not state.** `playback_facts` keeps one row per accepted event with every payload field and `occurred_at`.
The table's checks repeat the contract's bounds and the RA01.1 semantic rules (`accepted_watched_ms <=
total_watched_ms`, `qualified = (total_watched_ms >= 30000)`), so a stored row can never contradict them.

**Dedup and update in one transaction.** The receipt (RA02.1) and the fact and session update commit together, and
the offset is acknowledged after the commit. A redelivered event is a `DUPLICATE` before anything is added. A
*different* event ID at an occupied `(session_id, sequence)` is `SEQUENCE_CONFLICT`; the whole transaction rolls
back, so neither the receipt nor any total changes, and the record is parked (RA02.3). Core never emits one.

**Session totals** (`playback_sessions`), updated by one `INSERT … ON CONFLICT DO UPDATE` whose expressions all read
the row as it was, so the result does not depend on arrival order:

| Column | Rule |
| --- | --- |
| `accepted_watched_ms` | sum of `acceptedWatchedMs` of the facts received; the only watched-time figure |
| `max_sequence`, `max_total_watched_ms` | highest sequence received and Core's cumulative total at it; never added |
| `fact_count` | facts received; `complete()` when it equals `max_sequence` |
| `qualified_sequence`, `qualified_at` | the lowest sequence received with `qualified = true` and its `occurredAt` |
| `first_occurred_at`, `last_occurred_at` | min and max `occurredAt` |

`max_total_watched_ms - accepted_watched_ms` is the watched time Core accepted whose increments have not arrived yet
(`SessionTotals.missingWatchedMs()`); it is zero for a complete session. A late event with a lower sequence still
adds its increment, and a late qualifying event moves the qualification to the lower sequence. Seeking earns nothing
because Core sends `acceptedWatchedMs = 0`.

A session belongs to one account, profile and content from admission on. A fact that changes any of them is
`SESSION_IDENTITY_CHANGED` (`EVENT_CONFLICT`).

**Playback before metadata.** Facts reference `content_id` and `profile_id` only; nothing requires the catalog node
or the profile to exist. Content is resolved when it is read, through `CatalogStore.placement` (RA02.1), so a fact
stored before its episode, season or series arrived is placed as soon as they do. Nothing is skipped or re-processed.

**Deleted profiles.** A fact for a profile with a stored tombstone is stored with `profile_id = NULL`; the tombstone's
erasure sets `profile_id = NULL` on existing facts and sessions. The fact still counts for aggregate statistics
(account, content, time), and personalization loses the link (RA01.1). The shared/exclusive advisory lock of RA02.1
orders a fact and a concurrent deletion of its profile.

## Verification

`IngestionIntegrationTest` (real PostgreSQL) covers: replaying every event of a session twice and in reverse order
leaves the canonical sum and `complete()`; the lowest qualifying sequence wins whatever arrives first; a second
event ID at an occupied sequence is `EVENT_CONFLICT` and leaves no receipt, fact or total behind; a changed session
identity is `EVENT_CONFLICT`; playback for an unknown profile and content is stored and placed once the catalog event
arrives; after `ProfileDeleted` facts and sessions lose the profile link and a later fact is stored unlinked but
counted; 8 threads delivering one event count it once.

`ProfileErasureRaceIntegrationTest` (2 tests × 10 rounds, PostgreSQL) holds a playback append and a tombstone open
at the same time in both orders and checks that the second blocks on the advisory lock and that no fact or session
keeps a link to the deleted profile. `KafkaIngestionIntegrationTest` proves through a real broker that the committed
offset passes a record only after its rows exist. Order independence over many permutations is RA02.5.

The shared gate of RA02.1–RA02.5 is in [RA02.5, Gate](analytics-ingestion-hardening-ra02-5.md#gate).

## Limits

- The statistics of RA01.1 (qualified views, unique viewers, watched time per UTC day) are not computed or served
  yet; RA04.1–RA04.2 build them on these tables.
- A gap (`missingWatchedMs > 0`) is visible per session but not yet reported as a metric (RA09.1).
- `playback_facts` grows without bound; retention and purge are RA09.2.
- Once a profile is deleted, its sessions keep no profile link, so a later fact of the same session that names a
  different profile is no longer detected as `SESSION_IDENTITY_CHANGED` (Core never sends one).
- Next per plan: RA02.3, bounded retry, poison classification, DLT and redrive.
