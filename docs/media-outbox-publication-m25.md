# Media: Outbox publication (Milestone 25)

Verification date: 2026-10-12.
Status: VERIFIED_LOCAL (Media publishes to a real Kafka broker and Core projects the same records; the packaged
Compose stack was not run).

## Scope

M25 delivers the `MediaAssetStateChanged` rows that M23/M24 commit as `PENDING` to the existing topic
`media.assets.v1`, and proves that Core's existing consumer accepts exactly what Media publishes. It adds one Flyway
migration (`V6__guard_media_outbox_delivery.sql`), the `media.events` package, the `libra.media.outbox.*` settings
and three shared wire examples in `contracts/events/examples/`. It adds no API, event schema, failure code or job
stage, and changes no Core production code: Core's consumer, deduplication and stale-version rule already
existed and were exercised here against Media's real records.

## Gate

Every committed asset transition reaches the broker, under its asset ID key, in aggregate-version order per asset,
with a wire form that matches the contract; a broker outage loses nothing; a duplicate is the same event under the
same event ID; and Core turns a published READY into a READY candidate that an administrator can then publish,
while a published FAILED keeps publication refused.

## Delivery

`OutboxRelay` (one platform thread, `media-outbox`) drains up to 100 events per poll. Each step is a short
`REQUIRES_NEW` transaction (5 s timeout) judged by database time (`clock_timestamp()`); the broker call never runs
inside one.

| Step | Database write | Next |
| --- | --- | --- |
| Claim | The oldest event (`created_at`, `event_id`) that is `PENDING` and due, or `LEASED` with an expired lease, and whose earlier aggregate versions of the same asset are all `SENT`: `LEASED`, new `lease_token`, `lease_until = now + lease`, `attempt_count + 1`. `FOR UPDATE SKIP LOCKED`, so concurrent relays never lease the same row. | Publish |
| Broker acknowledged | `SENT`, `sent_at`, lease cleared, only if the token is still ours. No expiry check: the broker has the record. | Next event |
| Another lease took over | Nothing (the `sent` update matches no row); `OUTBOX_DELIVERY_FENCED` is reported. | Next event |
| Broker failed or timed out | `PENDING`, lease cleared, `next_attempt_at = now + min(max-retry-delay, 1 s x 2^(attempt-1))`. | Drain stops until the next poll |
| Relay interrupted (shutdown) | `PENDING`, due at once. | Relay exits |
| Database fails after the broker acknowledged | Nothing; the lease expires and the event is published again. | Drain stops |

The contract's `PENDING -> LEASED -> SENT` machine is unchanged; "expired LEASED returns to claimable PENDING" is
implemented by claiming an expired `LEASED` row directly. There is no `PARKED` state: an undeliverable event keeps
retrying at the capped delay, and, through the ordering rule, holds the later events of its asset only.

The producer is a plain `KafkaProducer` (not `KafkaTemplate`, whose default listener logs record values):
`acks=all`, idempotence on, `linger.ms=0`, and every wait bounded by the send timeout (`request.timeout.ms` and
`max.block.ms` half of it, `delivery.timeout.ms` all of it). A failure is reported as a fixed code; no broker
message, address or record value is logged.

## Wire record

The value is the envelope rebuilt from the row's columns in the contract's field order, with the stored payload:

| Field | Source |
| --- | --- |
| `eventId`, `aggregateId`, `aggregateVersion` | `event_id`, `asset_id`, `aggregate_version` |
| `eventType`, `schemaVersion` | `event_type`, `schema_version` (constrained to the contract values since V1) |
| `occurredAt` | `created_at` (UTC, ISO-8601) |
| `correlationId` | `correlation_id`, the upload's immutable `request_id` |
| `payload` | `payload`, written in the transition's transaction (`durationSeconds` for READY only) |

Key: the asset ID, so one asset's events share a partition. No headers. A record is a few hundred bytes; the
contract's 256 KiB bound is far away.

`contracts/events/examples/media-asset-state.v1.{processing,ready,failed}.json` are templates of these records
(`{{eventId}}`, `{{assetId}}`, `{{contentId}}`, `{{bindingId}}`, `{{correlationId}}`, `{{occurredAt}}`). Three checks
share them: `infra/ci/check_contracts.py` validates each against `media-asset-state.v1.schema.json` (examples are
no longer treated as schemas themselves); Media's integration test compares every consumed record with its
template, filled from the database row; Core's integration test fills them with its own binding and publishes them
to Kafka.

## Ordering and duplicates

- **Order.** An event is leased only when every earlier version of its asset is `SENT`, and `SENT` follows the
  broker's acknowledgement, so version n+1 is never sent before version n is acknowledged, whatever the number of
  relays. Events of different assets are independent.
- **Duplicates.** Delivery is at least once. A crash or database failure after the acknowledgement, a send slower
  than its timeout (the producer cannot cancel it), or a lease that runs out mid-send can publish an event twice,
  always with the same event ID and value. Core records `event_id` in `media_projection_receipts` and ignores an
  aggregate version it has already projected.
- **Lease and send.** `lease-duration` must be at least twice `send-timeout`, so a normal acknowledgement is
  recorded before another relay may take the event over.

## Database guards

`V6` adds a `BEFORE UPDATE OR DELETE` trigger on `media_outbox_events` (SQLSTATE `23000`, like M24):

| Refused | Message |
| --- | --- |
| Deleting an event | `media outbox events are retained` |
| Changing ID, asset, versions, topic, type, schema, correlation, payload or `created_at` | `media outbox event is immutable` |
| Any change to a `SENT` event (including back to `PENDING`) | `media outbox SENT is terminal` |
| Lowering `attempt_count` | `media outbox attempts only grow` |
| `SENT` while an earlier version of the asset is not `SENT` | `media outbox events are sent in version order` |

It also indexes expired-lease candidates (`ix_media_outbox_leased`). The one M24 test case that deleted the
PROCESSING event to forge a wrong-version READY event now forges one at a version the asset has not reached; the
deferred READY check refuses it as before.

## Settings

| Setting | Environment | Default |
| --- | --- | --- |
| `libra.media.outbox.enabled` | `MEDIA_OUTBOX_ENABLED` | `false` (Compose: `true`) |
| `libra.media.outbox.poll-interval` | `MEDIA_OUTBOX_POLL_INTERVAL` | `1s` (10 ms to 1 min) |
| `libra.media.outbox.lease-duration` | `MEDIA_OUTBOX_LEASE_DURATION` | `30s` (2 x send timeout to 5 min) |
| `libra.media.outbox.send-timeout` | `MEDIA_OUTBOX_SEND_TIMEOUT` | `10s` (100 ms to 1 min) |
| `libra.media.outbox.max-retry-delay` | `MEDIA_OUTBOX_MAX_RETRY_DELAY` | `5m` (1 s to 1 h) |
| `libra.media.outbox.shutdown-timeout` | `MEDIA_OUTBOX_SHUTDOWN_TIMEOUT` | `10s` (10 ms to 30 s) |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092`; required when enabled |

The relay is off by default so persistence-only tests and tools need no broker. Invalid timing or an empty
bootstrap address fails startup. The relay is independent of the worker: enabling it publishes whatever transitions
are committed, including from a worker running in another process.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `OutboxRelayTest` | In-memory ports | 10 (new) |
| `OutboxPublicationConfigurationTest` | Spring context runner, mocked port | 3 (new) |
| `EventsArchitectureTest` | ArchUnit | 2 (new): framework-free application/domain; no dependency between `events` and `processing`/`upload` |
| `OutboxPublicationIntegrationTest` | **Real PostgreSQL 18.6 and Kafka 4.3.1** (3-partition topic), the production claim, READY selection and terminal failure | 6 (new) |
| `CatalogProfilesIntegrationTest` (Core) | Real PostgreSQL 18.6 and Kafka 4.3.1, Core's real listener | 14 (1 new) |
| `FinalizationConsistencyIntegrationTest` | Real PostgreSQL, SeaweedFS, ffprobe/ffmpeg | 7 (one case adjusted for V6) |
| `check_contracts.py` | jsonschema 4.23.0 | 18 contracts and 3 wire examples |

`OutboxPublicationIntegrationTest` covers:

- PROCESSING + READY of one asset and PROCESSING + FAILED of another reach the broker; each record equals its shared
  template filled from the row; per key the broker order is version 1 then 2 on one partition; no headers; every row
  `SENT` after one attempt with its lease cleared.
- An unreachable broker (`127.0.0.1:1`): `OUTBOX_BROKER_UNAVAILABLE`, the row back to `PENDING` with a future
  `next_attempt_at` that a claim respects; once due, a working broker delivers it.
- A READY event waits while its PROCESSING predecessor is leased; an expired lease is taken over with a new token
  and attempt 2; the stale token can neither acknowledge, retry nor release; release makes an event due at once.
- An acknowledgement lost after the broker has the record: the event is published again, the two records are
  identical, the row is `SENT` once with attempt 2.
- Three relays with three producers over twelve events of six assets: every event ID once, per-asset version order,
  no event leased twice.
- The V6 refusals above, with the immutable columns and payloads compared before and after.

Core's new case publishes the PROCESSING and READY templates (READY twice, same event ID) and the PROCESSING and
FAILED templates for a second candidate. The listener projects READY (version 2, 7 s) with exactly two receipts,
nothing is published by readiness alone, the administrator's publish selects that candidate, and the FAILED
candidate's publish is refused `MEDIA_NOT_READY`.

Two mutants were run against `OutboxPublicationIntegrationTest` and both were killed: dropping the predecessor rule
from the claim (two failures: the predecessor case, and the concurrent case, where V6 refused out-of-order `SENT`),
and dropping the token from the `sent` fence (the takeover case). Logs: `tmp/m25-mutation-*.log`.

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 11m05s). The report gate confirmed 73
suites and 552 tests with zero failures, errors or skips: Core 187 (44 Surefire, 143 Failsafe), Media 363 (184
Surefire, 179 Failsafe), recommendation and analytics 2. M25 added 22 tests. `check_contracts.py` passed 18 contracts
and 3 wire examples, the Python contract tests passed (8), and `git diff --check` was clean. Raw logs:
`tmp/m25-unit-*.log`, `tmp/m25-focused-it-1.log`, `tmp/m25-core-it-1.log`, `tmp/m25-reactor-verify-1.log`
(ignored).

Runs that failed and are not counted: the first unit run, where `OutboxPublicationConfigurationTest`'s context
runner had no duration conversion service (a test-harness gap; its failure case had passed for the wrong reason
and now asserts the exact root cause), and the two deliberate mutant runs above.

## Limits

- At least once, not exactly once: duplicates are bounded to the cases above and are absorbed by Core's receipts.
- No `PARKED` state, operator view or replay command; a permanently undeliverable event retries every
  `max-retry-delay` and holds only its own asset's later events. Replay and delivery operations are M26.
- Topic creation is the platform's job (Compose's broker auto-creates; tests create the topic). The relay does not
  manage partitions or retention.
- Processes were not killed: crash points are simulated by expiring a lease after a publish, and shutdown by
  interrupting the relay thread.
- Upload expiry still emits no FAILED event. Protected delivery (M27-M30), M31 cleanup, the packaged image with the
  relay on, the full Compose stack with the worker on, multi-host brokers, remote CI and deployment were not
  exercised. GitNexus was not used.
