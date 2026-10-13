# Media: Outbox delivery hardening (Milestone 26)

Verification date: 2026-10-12.
Status: VERIFIED_LOCAL (the packaged relay killed mid-backlog and a hung broker, against real PostgreSQL and
Kafka; the full Compose stack was not run).

## Scope

M26 hardens the M25 relay. It adds one Flyway migration (`V7__guard_media_outbox_contract.sql`), a failure
classification (`DeliveryFailure`), delivery gauges and an outcome counter. It adds no API, event schema, topic or
setting, and Core is unchanged: its receipts and stale-version rule are what make Media's at-least-once delivery
safe.

## Gate

A broker or producer failure never stops delivery for longer than the retry delay once the cause is gone; the
database refuses any event that is not the contract event of the transition it announces; a stuck delivery is
visible with its reason; and a relay process killed at any point loses no event and never reorders an asset.

## Findings and repairs

Each finding was reproduced by a test on the M25 code before the repair (`tmp/m26-baseline.log`,
`tmp/m26-baseline-producer.log`; the temporary baseline tests were then removed).

| Finding | Repair | Regression evidence |
| --- | --- | --- |
| **A producer in a fatal state stopped delivery until the process restarted.** An idempotent `KafkaProducer` that hits an authorization error, a sequence error or a closed state refuses every later send. M25 kept the one producer for the life of the process, so after the cause was fixed every event still failed and retried forever. Reproduced by closing the publisher's producer: the next publish failed again. | `KafkaEventPublisher` takes a producer factory. A failure the client calls retriable (a timeout, `NotEnoughReplicas`, a metadata timeout thrown by `send`) keeps the producer and its idempotence session. Any other failure closes it; the next publish builds a new one (a failed build is retried on the publish after). | `KafkaEventPublisherTest` (5, `MockProducer`): a refusing producer is replaced and the next record goes through unchanged; an asynchronous `ClusterAuthorizationException` closes the producer; timeouts and retriable errors keep it; a failed rebuild is retried; a closed publisher refuses. A mutant without the replacement fails three of them. |
| **The database accepted an event that contradicts its asset, and the relay would publish it.** V1 only required the payload to be a JSON object. An event naming another binding, a state the asset is not in, a READY without a duration, a version the asset has not reached or another correlation ID could be inserted, delivered and dead-lettered by Core. Reproduced by inserting a READY event without a duration for a PROCESSING asset: accepted and leased. | `V7`: a validated `CHECK` (`ck_media_outbox_payload_contract`) requires exactly the contract keys, the row's own `assetId` and `assetVersion` (same JSON types), a contract state, and a duration of 1..600 for READY only; a `BEFORE INSERT` trigger requires the asset's content, binding, current aggregate version, state and duration, the upload's request ID as correlation ID, and an undelivered row (`media outbox event contradicts its asset`, `media outbox events start pending`). | `onlyTheExactContractEventOfTheTransitionJustWrittenCanBeInserted`: a valid control insert, eleven payload cases and five column cases each refused; the production claim, READY selection and failure paths pass in every other suite. |
| **A failed delivery left no trace of why, and nothing showed a stuck backlog.** M25 logged a fixed code and moved on: the row looked like any pending event. Reproduced: after an unreachable broker there was nothing to read (`last_error_code` did not exist). | `V7` adds `last_error_code` (`BROKER_UNAVAILABLE`, `BROKER_REJECTED`; refused on a SENT row). A retry records it; an acknowledgement clears it; a release leaves it. Gauges read PostgreSQL when sampled and are NaN when it cannot be read: `libra.media.outbox.backlog{state}`, `libra.media.outbox.failing{failure}`, `libra.media.outbox.oldest.seconds`. The relay reports every outcome to the counter `libra.media.outbox.relay{outcome}`; only non-acknowledgements are logged. | The M25 broker-outage case now asserts the recorded and cleared code; `aBrokerThatHangsMidSendLosesNothingRecordsWhyAndRecovers` and `theGaugesReadTheDatabaseAndAreNanWhenItCannotBeRead` check every gauge, including NaN while the table cannot be read. |

The relay's failure outcomes are now `OUTBOX_BROKER_UNAVAILABLE` and `OUTBOX_BROKER_REJECTED`; an unclassified
exception from a publisher counts as unavailable.

### Changes to earlier tests

- The M04/M24 tests that inserted partial payloads by hand (`{"state":"READY","durationSeconds":60}`) now insert the
  complete contract payload. The M24 wrong-event cases are now refused at insert by V7 (four by the trigger, the
  missing-duration case by the check); the no-event case still reaches the V5 commit check.
- The M04 upgrade test's V1-era outbox row now carries a contract payload. A database holding an event that is not
  its asset's contract event fails the V7 migration on purpose: Media has only ever written complete payloads
  (since M23), and such a row would only be dead-lettered by Core.

## Process and broker evidence

`OutboxProcessRecoveryIntegrationTest` starts the **packaged** `media-service` jar (`java -jar`, outbox on, worker
off, 2 s lease, 1 s send timeout) against real PostgreSQL 18.6 and Kafka 4.3.1 with a 300-event backlog (150 assets,
PROCESSING then FAILED, written by the production claim and failure paths). It kills the process with
`destroyForcibly` after 50 deliveries, starts a new one, kills it again after 50 more, and lets a third finish. The
test JVM never edits delivery state; recovery relies on the persisted leases expiring.

Result (`target/m26-process/result.txt`, reactor run): kills at 52/300 and 103/300 sent; all 300 events `SENT` with no lease or
error left; the broker holds every stored event ID (300 records, none duplicated in this run), and for each of the
150 keys the versions are sorted in offset order. The highest attempt count was 2: the event in flight at a kill was
leased again after its lease expired. Duplicates are permitted, and the M25 lost-acknowledgement case still proves
they keep their event ID.

`aBrokerThatHangsMidSendLosesNothingRecordsWhyAndRecovers` pauses the Kafka container (`docker pause`) after the
producer has connected and fetched metadata. An asset's PROCESSING and FAILED events are then committed: the first
times out (`BROKER_UNAVAILABLE` recorded, `failing` = 1, backlog 2, positive oldest age) while the second waits
behind it. After `unpause` both are delivered, the code is cleared and the gauges return to zero. The broker order
observed for that asset was `[1, 1, 2]`: the timed-out send also reached the broker, so version 1 arrived twice
under one event ID, and never after version 2.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `KafkaEventPublisherTest` | kafka-clients `MockProducer` | 5 (new) |
| `OutboxRelayTest` | In-memory ports | 11 (1 new: a rejection is recorded as such) |
| `OutboxPublicationConfigurationTest` | Spring context runner | 3 (now with a meter registry) |
| `OutboxDeliveryHardeningIntegrationTest` | **Real PostgreSQL 18.6 and Kafka 4.3.1**, `docker pause` | 3 (new) |
| `OutboxProcessRecoveryIntegrationTest` | **Packaged jar**, real PostgreSQL and Kafka | 1 (new) |
| `OutboxPublicationIntegrationTest` | Real PostgreSQL and Kafka | 6 (failure code and outcome assertions) |
| `MediaPersistenceIntegrationTest`, `FinalizationConsistencyIntegrationTest` | Real PostgreSQL (and SeaweedFS, ffmpeg) | 7 + 7 (payloads and expected guard messages adjusted) |

`KafkaEventPublisher` without the replacement (a mutant) fails three `KafkaEventPublisherTest` cases
(`tmp/m26-mutation-replace.log`).

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 13m20s). The report gate confirmed 76
suites and 562 tests with zero failures, errors or skips: Core 187 (44 Surefire, 143 Failsafe), Media 373 (190
Surefire, 183 Failsafe), recommendation and analytics 2. M26 added 10 tests. `check_contracts.py` passed 18
contracts and 3 wire examples, the Python contract tests passed (8), and `git diff --check` was clean. Raw logs:
`tmp/m26-unit-1.log`, `tmp/m26-focused-it-*.log`, `tmp/m26-process-it-*.log`, `tmp/m26-reactor-verify-2.log`
(ignored).

Runs that failed and are not counted: the baseline runs above; a focused run where the M04 upgrade test's V1-era
row was refused by V7 (the fixture was then given a contract payload, see above); the first packaged-relay run, where
the child JVM could not open its selector pipe on Windows (fixed with M16's `jdk.net.unixdomain.tmpdir`); and the
first root `clean verify` (`tmp/m26-reactor-verify-1.log`), where the packaged-relay test's own JDBC connection
failed with `SocketException: No buffer space available` after twenty-odd Spring contexts had accumulated in the
shared failsafe JVM. The three outbox integration classes now close their contexts (`@DirtiesContext`) and the
test polls every 20 ms instead of 5 ms; the second root run passed.

## Limits

- Delivery is still at least once. No `PARKED` state and no operator retry, replay or redrive command exist on the
  Media side: they need an operator identity Media does not have (its only inbound authority is Core's service
  JWT). An event the broker keeps refusing retries every `max-retry-delay` and holds only its own asset's later
  events; the gauges and `last_error_code` show it. Re-delivering already SENT events (for example after topic
  retention removed them) is not provided; Core can rewind its consumer group while the topic retains them.
- The gauges and counter are registered, not exposed: Media's actuator exposes `health` and `info` only, and adding
  a metrics endpoint needs the same operator authentication. Alerting is a deployment concern.
- Kills used `destroyForcibly` at delivery-count points, not at chosen instructions; the exact acknowledgement gap
  stays covered by the M25 lost-acknowledgement case. One broker, one host; the broker was paused, not restarted on a
  new address.
- Upload expiry still emits no FAILED event. Protected delivery (M27-M30), M31 cleanup, the full Compose stack with
  the worker on, multi-host brokers, remote CI and deployment were not exercised. GitNexus was not used.
