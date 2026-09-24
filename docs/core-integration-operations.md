# Core: Integration operations (Milestone 07)

Core now owns durable outbox publication, Media dead-letter capture/redrive,
scoped inbound service authentication and administrator operational visibility.
The companion [Analytics adapter](core-analytics-adapter.md) implements bounded
recommendation/statistics reads. Actual Media transcoding/HLS and Analytics
projections remain separate service implementations; no other service database is
queried or mutated by Core.

## Durable delivery and recovery

Flyway V7 extends the existing transactional outbox without changing event IDs or
payloads. Previously published rows become `SENT`; pending rows become `PENDING`.
The scheduled publisher starts by default. A short PostgreSQL transaction claims
one due row using `FOR UPDATE SKIP LOCKED`, a random lease ID and a 60-second lease.
Kafka I/O holds no database transaction open. The aggregate UUID remains the Kafka
key; all envelope fields retain their original values.

Core marks `SENT` and `published_at` only after Kafka acknowledgement. Database
updates are fenced by lease ID so an expired worker cannot overwrite its successor.
An expired lease is reclaimable after a restart. A timeout or lost acknowledgement
can cause duplicate publication of the **same** event. Consumers must atomically
deduplicate event IDs with projection writes and reject stale aggregate versions.
This is at-least-once delivery, not a transaction spanning PostgreSQL and Kafka.

The publisher waits up to five seconds for a send future; Kafka metadata acquisition
is additionally bounded by the configured producer `max.block.ms`. Failed attempts
back off exponentially (2 seconds through a 300-second cap); attempt 10 parks the
row. Earlier unpublished rows, including parked rows, block later events of the
same topic/aggregate. Other aggregates continue. Broker-side arrival can still
repeat or reorder after an uncertain send; projection version checks remain necessary.
An operator must repair the cause, then explicitly retry a parked row.

`CORE_OUTBOX_PUBLISHER_ENABLED=false` pauses both outbox and approved Media redrive
publication; durable rows remain retained. `libra.integration.publisher-delay-ms`
defaults to 1000; a pass handles at most ten rows per queue. There is no automatic
deletion of outbox, DLT, audit or projection-receipt rows in this milestone.

## Media dead letters

The Media consumer retries a failed record twice with one-second delays. Recovery
publishes to the same partition of `media.assets.v1.DLT` and waits for broker ACK;
a failed DLT send throws, so the original record is not acknowledged as recovered.
Recovery preserves the exact original key/body and adds only trusted origin headers:
`libra-original-topic` (UTF-8), `libra-original-partition` (big-endian int32), and
`libra-original-offset` (big-endian int64). Incoming headers and exception text are
not copied. Listener errors are sanitized before the Kafka framework logs them.

The separate `core-media-dlt-v1` consumer persists records in `media_dead_letters`
before acknowledging them. A database failure retries indefinitely; it does not
skip the record or recursively publish another DLT. `(dlt_partition, dlt_offset)`
deduplicates capture after restart. Older Spring DLT origin headers are accepted
for migration compatibility. Broker ACLs, not HTTP JWTs, authenticate this transport.

Bounded original data is private to the database: key at most 128 characters and
body at most 256 KiB UTF-8. Null, oversized or PostgreSQL-incompatible NUL data is
recorded as unavailable and cannot be redriven. Malformed bounded data is retained
for restricted operator diagnosis but never exposed through HTTP or metrics.
Newly captured rows are `HELD`; capture never automatically replays poison data.

Provision the Media topic and DLT with matching partition counts and appropriate
retention before starting consumers. Grant Media write access to its asset topic;
Core needs consume access plus DLT production/capture and tightly controlled write
access to the asset topic **for approved redrive only**. Keep brokers private and use
TLS/SASL/ACLs outside local Compose. Retain projection receipts for at least the
supported replay window, including operator redrive. Do not recreate the DLT under
the same name while retaining its offset-based capture records without a migration.

## Administrator operations

The [operations contract](../contracts/http/core-operations.v1.json) defines:

- `GET /v1/admin/operations/outbox`: unsent rows, oldest first.
- `GET /v1/admin/operations/media-dead-letters`: captured rows, newest first.
- `GET /v1/admin/operations/audit`: action receipts, newest first.
- `GET /v1/admin/operations/summary`: nonempty unsent queue/state counts and oldest age.
- `POST /v1/admin/operations/outbox/{id}/retry`: requeue a parked outbox row.
- `POST /v1/admin/operations/media-dead-letters/{id}/redrive`: queue the unchanged original event.

Lists accept `limit=1..100` (default 20), `offset=0..10000` (default 0). All routes
require a currently active ADMIN session; POST also requires the existing CSRF
cookie/header flow. No raw event body, key, broker exception, secret or storage URL
appears in a queue response. The supplied `reason` is retained in the ADMIN-only
audit trail; operators should enter an operational explanation, never credentials.

Commands contain `requestId` (UUID), `expectedVersion` (current queue row version),
and a nonblank `reason` up to 500 characters. Repeating the same request by the same
administrator returns its original receipt, even if delivery has since progressed.
Reusing that ID with different arguments returns `409 IDEMPOTENCY_CONFLICT`.
Stale versions return `409 VERSION_CONFLICT`. The state update and audit insert
commit together; audit failure rolls back the operation. Current authorization is
checked before idempotency replay.

Redrive revalidates the original envelope/key and exact Core-owned Media binding.
Missing bindings, unavailable data and malformed events fail closed. There is no
HTTP payload editor or force-apply endpoint. Repair the underlying binding/configuration
through its authorized owning workflow before retrying. A `PENDING` redrive cannot
be queued again; a completed redrive may be explicitly repeated with a new request ID
and current version. Event ID, aggregate version, correlation ID, key and body remain
unchanged. Media projection deduplication/stale-event rejection still applies.
An operation receipt means **queued**; a `SENT` queue row means **broker acknowledged**,
not that a downstream projection or Media processing succeeded.

## Scoped Media HTTP identity

The [internal contract](../contracts/http/core-internal-media.v1.json) exposes only
`GET /internal/v1/media/bindings/{id}`. It returns binding/content/asset UUIDs,
asset version and current candidate/active flags. A retired binding can have both
flags false. This read neither grants playback nor accepts a READY transition.

Inbound authentication is disabled by default and rejects all tokens while disabled.
To enable it, configure `CORE_MEDIA_SERVICE_AUTH_ENABLED=true`,
`CORE_MEDIA_SERVICE_PUBLIC_KEY` (Base64 X.509 RSA DER, at least 2048 bits) and
`CORE_MEDIA_SERVICE_KEY_ID`. The private key stays with Media. This key must differ
from Core playback and enabled Analytics signing keys. Invalid enabled configuration
fails startup; there is no remote JWKS discovery or automatic key generation.

The Bearer JWT must use RS256 and the configured `kid`, issuer `libra-media-services`,
subject `libra-media`, sole audience `libra-core-internal`, purpose `service-access`,
nonblank `jti`, `iat` not in the future and a strictly future `exp` no more than
60 seconds after `iat`. There is no timestamp leeway. Require scope
`media.bindings:read`; wrong scope is 403, invalid/missing credentials are 401.
Tokens may be reused within that short lifetime; this is not a one-time-token protocol.

The isolated internal filter chain never authenticates browser cookies or user
access/playback tokens, accepts no mutation endpoint, and denies all other internal
routes. Nginx blocks `/api/core/internal/`; call Core directly over a private service
network with TLS in deployed environments. The current Media scaffold does not yet
issue these tokens or consume this endpoint.

## Metrics and operational checks

`/actuator/metrics` and child paths require a current ADMIN session. Health/info
retain their existing exposure. Micrometer exports:

- `libra.integration.delivery`: count by fixed `kind` and `outcome` (`acknowledged`,
  `failed`, `fenced`); process-local counters reset on restart.
- `libra.integration.kafka.errors`: asynchronous producer failures without record
  values, keys or exception text; replaces Kafka's payload-logging default listener.
- `libra.integration.backlog`: durable row count by fixed `queue` and `state`.
- `libra.integration.oldest.seconds`: age of the oldest unsent row per queue.

Database read failure produces NaN for gauges, never a fabricated healthy zero.
Metrics have no account/event IDs or payload-derived labels. Alert on parked rows,
growing backlog/oldest age and repeated failures, and inspect the queue/audit API.
Dashboards, alert routing, receipt cleanup and production key rotation remain
deployment responsibilities.

## Verification boundary

`IntegrationOperationsIntegrationTest` exercises real PostgreSQL and Kafka, including
concurrent claims, lease fencing, uncertain-ACK replay, broker outage recovery,
bounded retries, failed DLT publication and DB capture without offset loss,
durable DLT capture, redrive deduplication, migration V6 to V7,
transactional audit and HTTP authorization. `InternalServiceSecurityIntegrationTest`
uses real Core HTTP security and PostgreSQL for pinned service JWTs and cookie isolation.
Analytics tests use a local HTTP fixture; they do not prove live Analytics projections.
Run root `mvnw.cmd clean verify` with JDK 21 and Docker for all three service builds.
No production deployment, real Media HLS or end-to-end Analytics consumption is claimed.

Local verification on 2026-09-20: root `mvnw.cmd clean verify` passed all three
reactor services and 143 tests (zero failures, errors or skips), including 11
integration-operation tests and three inbound service-security tests. All four new
OpenAPI 3.1 contracts passed `openapi-spec-validator` with external references
resolved. Compose configuration and `nginx -t` in the configured Nginx image passed;
`git diff --check` passed. These are local checks, not remote CI/deployment evidence.
