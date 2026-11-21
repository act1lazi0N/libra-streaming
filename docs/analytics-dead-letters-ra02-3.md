# Analytics: Retry, Dead Letters and Redrive (Milestone RA02.3)

Verification date: 2026-11-16.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA02.3 decides what happens to a record that cannot be applied: retry it, park it, or wait. It shares the intake and
gate of [RA02.1](analytics-ingestion-ra02-1.md).

Done condition (plan): a database or broker outage loses no event; a failed dead-letter publication is never treated
as successful processing.

Contract and migration changes:

- No Core contract changed. New Analytics-owned Kafka topic `analytics.dead-letters.v1` (created on first use by
  the broker's auto-creation, as Core's `media.assets.v1.DLT` is).
- New migration `V4__create_dead_letters.sql`: `dead_letters`.
- New settings: `ANALYTICS_CONSUMERS_ENABLED` (default `true`; turns the four consumers off),
  `ANALYTICS_DEAD_LETTERS_REDRIVE_ON_STARTUP` (default `false`).

## Failure classes

`ingestion.infrastructure.Failures` sorts every failure of the intake into one of three classes:

| Class | Examples | Policy | Ends as |
| --- | --- | --- | --- |
| Rejection (`EventRejection`) | schema or semantic violation, reused event ID, sequence conflict, changed identity | no retry: the answer cannot change | parked at once with its reason |
| Transient | SQLState `08*`, `53*`, `57P0*`, `40001`, `40P01`, `55P03`; Spring's transient/recoverable/resource-failure exceptions; no transaction | exponential back-off 1 s × 2, capped at 30 s, **no attempt limit** | applied when the outage ends |
| Other | anything else | 2 retries 1 s apart | parked as `RETRY_EXHAUSTED` |

Transient failures are never parked. Parking them during a database outage would let later versions of the same
aggregate overtake the parked one, and the outage would turn into data loss once the dead letters were forgotten. The
consumer instead stays on the record; the partition's lag grows and is visible. SQLState class `54` (program limits)
is excluded, because retrying cannot fix it. Retry is bounded in time between attempts; it is unbounded in count
only for this class, by design, as in Core's media consumer (M34, finding H1).

The listener replaces every non-rejection exception by a fixed-message exception before Spring Kafka sees it, so the
container's error log never carries SQL text, driver messages or payloads. Counters: `libra.analytics.events{topic,
outcome}`, `libra.analytics.retries{topic,kind}`, `libra.analytics.dead-letters.published{topic,reason}`,
`…publish-failures{topic}`, `…captured{reason}` — no label holds an identifier.

## Parking a record

`ConsumerConfiguration.deadLetterPublisher` is the `DefaultErrorHandler`'s recoverer. It builds a new record on
`analytics.dead-letters.v1` with the original key and value and four headers: original topic, partition (4 bytes),
offset (8 bytes) and reason. No source header and no exception text is copied. It then waits up to 10 s for the
broker's acknowledgement (`acks=all`, idempotent producer) for up to 35 s, longer than the producer's own
`delivery.timeout.ms` (30 s). A shorter wait would give up on a send that can still succeed and then park the
record a second time; the review found the first version waited only 10 s. The capture also keeps one row per
source position (partial unique index on `(source_topic, source_partition, source_offset)`).

If the publication fails or times out, the recoverer throws. Spring Kafka then treats the record as not recovered:
it seeks back, the offset is not committed and the record is tried again after the back-off. A record is therefore
never skipped without a durable copy. `KafkaIngestionIntegrationTest` proves it with a real broker: the dead-letter
topic is created with `max.message.bytes=64`, a poison record followed by a valid one is produced, and for 15 s
neither the capture nor the valid record's effect appears and the committed offset stays before the poison record.
After the topic limit is raised, the poison record is captured and the valid record applied.

A separate consumer (`analytics-dead-letters-v1`) copies each dead-letter record into `dead_letters` in one
transaction and retries forever on failure: it is the only durable copy once the source offset moved on. A record
whose key or body is missing, contains NUL or exceeds the bounds is stored without payload as `PAYLOAD_UNAVAILABLE`;
one without a valid origin is `ORIGIN_UNAVAILABLE`. Both are `UNREDRIVABLE`. Re-capture of the same dead-letter
offset is a no-op (`UNIQUE (dlt_partition, dlt_offset)`).

## Redrive

`DeadLetterRedrive.redriveHeld(pageSize)` passes each `HELD` row's original key and body to the same `EventIntake`,
so the event keeps its identity:

- applied → `REDRIVEN` (with `redriven_at`), in a transaction after the intake committed. A crash in between leaves
  the row `HELD`, and the next pass finds a duplicate;
- an event applied meanwhile (by a bootstrap replay, for example) is a duplicate and adds nothing;
- still rejected → stays `HELD`, `redrive_attempts` + 1, `last_redrive_code` = the new reason and code; the original
  `reason` is kept;
- a transient failure (an outage, classified by `Failures`) stops the pass and leaves the remaining rows `HELD`;
- any other failure is recorded on the row as `REDRIVE_FAILED` and the pass goes on.

The pass pages by `(received_at, id)` and visits every row once.

**Finding R1 (fixed).** The first version of the pass, written by the delegated implementation, stopped after a page
in which nothing was redriven. A page full of still-rejected rows therefore hid every row after it for good. Its own
unit test asserted that behaviour (`Report(0, 3)` for one rejected row followed by two valid ones). The pass now uses
keyset paging; the regression test `aFullyRejectedPageDoesNotHideTheRowsAfterIt` expects `Report(2, 1)`. Counting
the rows still held also reloaded every body in doubling pages; it is now one `count(*)`.

**Finding R2 (review, fixed).** After R1, any failure that was not a rejection still ended the pass. A row that fails
deterministically for another reason (a defect, a check constraint) would therefore have blocked every row after it
on every pass, without leaving a trace. Only transient failures stop the pass now; the regression test
`aDeterministicNonTransientFailureIsRecordedAndDoesNotStopThePass` expects `Report(2, 1)` and `REDRIVE_FAILED`.

The trigger is `ANALYTICS_DEAD_LETTERS_REDRIVE_ON_STARTUP=true`: one pass on its own thread after startup (it does
not hold back readiness), logging two counts only. The
intended use is a deployment that can decode what an older build parked (for example a consumer that accepts
`schemaVersion` 2, RA01.1 "Versioning"): restart once with the flag, then remove it.

## Verification

| Suite | Kind | Tests | What it proves |
| --- | --- | --- | --- |
| `FailuresTest`, `ConsumerConfigurationTest` | unit | 3 + 5 | classification by SQLState and cause chain; back-off per class; the recoverer's headers and reason; a failed or timed-out send throws |
| `DeadLetterListenerTest` | unit | 6 | mapping of dead-letter records, bounds, origin and reason precedence |
| `DeadLetterRedriveTest` | unit | 6 | redrive outcomes, R1 and R2 regressions, transient failure stops the pass |
| `KafkaIngestionIntegrationTest` | integration, PostgreSQL + Kafka | 6 | offsets committed after rows; schema-invalid record parked with origin while the next record is applied; sequence conflict parked as `EVENT_CONFLICT`; failed dead-letter publication blocks the partition until the topic accepts it; `RETRY_EXHAUSTED` record redriven once, repeat is a duplicate; PostgreSQL paused for 8 s: retried, applied once, nothing parked |
| `IngestionIntegrationTest` | integration, PostgreSQL | 2 of 21 | capture keeps one row per source position; rows without origin are not merged |

The broker outage (Kafka paused) is in RA02.5's fault-injection suite.

The shared gate of RA02.1–RA02.5 is in [RA02.5, Gate](analytics-ingestion-hardening-ra02-5.md#gate).

## Limits

- There is no operator API or UI for dead letters: Analytics has no operator identity (the same gap Media had). The
  rows are visible in the Analytics database; redrive is the startup flag. RA09.1 adds the metrics and alerting.
- `dead_letters` may hold payloads (identifiers, no names) and has no retention yet (RA09.2). A deleted profile's
  parked records are not erased by its tombstone.
- When the database is down and the transaction's rollback also fails, Spring's `TransactionTemplate` logs the
  original exception. The log line contains the SQL statement with `?` placeholders and the driver's I/O message,
  never bound values or payloads (observed in the outage test).
- `analytics.dead-letters.v1` relies on the broker's automatic topic creation (Compose's broker has it on), like
  Core's `media.assets.v1.DLT`. Without it every poison record blocks its partition. A `NewTopic` bean was tried
  and removed: with Kafka unreachable, Spring's admin client delays every startup by about 30 s (the startup suite
  went from 46 s to 404 s). Creating the topic belongs to deployment (RA09.4).
- A source record larger than the dead-letter topic accepts can never be parked and blocks its partition (the
  mechanism `KafkaIngestionIntegrationTest` uses on purpose). Core's events are far below the limits (256 KiB
  decoder bound, 1 MiB producer default); only a foreign producer on `core.*` topics could cause it.
- The dead-letter capture records `PAYLOAD_UNAVAILABLE` when the key is missing or too long, even if the body was
  valid and the original reason was different.
- A transient failure that never ends (for example a permanently full disk reported as class `53`) blocks its
  partition indefinitely. This is visible as lag, not as dead letters.
- Next per plan: RA02.4, bootstrap from Core.
