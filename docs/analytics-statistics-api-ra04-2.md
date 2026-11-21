# Analytics: Statistics API and Freshness (Milestone RA04.2)

Verification date: 2026-11-17.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA04.2 serves `POST /internal/v1/statistics/query` of `analytics-internal.v1` from the rollup of
[RA04.1](analytics-statistics-ra04-1.md), and confirms input checkpoints from consumer progress so that
`updatedAt` means what [RA01.1, Freshness](analytics-contracts-ra01-1.md#freshness) says.

Done condition (plan): a lost connection never becomes a zero; a request never refreshes `updatedAt`; a quiet topic
is not treated as a fault. Core's own classification (AVAILABLE, STALE, UNAVAILABLE) is unchanged.

Contract and migration changes:

- No contract changed. The endpoint now answers 200, 400, 413 and 415, which `analytics-internal.v1` 1.1.0 already
  lists; the `Problem` codes `INVALID_REQUEST`, `PAYLOAD_TOO_LARGE` and `UNSUPPORTED_MEDIA_TYPE` are now produced.
- No migration. `input_checkpoints` (V1) is written for the first time.
- New optional settings (defaults in `application.yml`, nothing added to Compose):

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANALYTICS_FRESHNESS_CHECKPOINTS_ENABLED` | the value of `libra.analytics.consumers.enabled` | run the checkpoint scheduler |
| `ANALYTICS_FRESHNESS_CHECKPOINT_INTERVAL` | `5s` (1 s – 1 min) | pause between two ticks |
| `ANALYTICS_FRESHNESS_ADMIN_TIMEOUT` | `5s` (500 ms – 30 s) | budget of each Kafka admin request |

## Freshness from consumer progress

`CheckpointAdvancer.tick()` runs on one background thread (`CheckpointScheduler`, first tick when the application
is ready). For each input topic it holds at most one pending **watermark**:

1. Without a pending watermark, it reads the clock (`at`) and **then** the read-committed end offset of every
   partition of the topic. A record that was on the topic at `at` lies below those offsets. The other order could
   confirm an instant past a record that was never read.
2. It reads the consumer group's committed offsets (`InputTopic.consumerGroup()`, the group of the listener that
   applies the topic); a partition without a committed offset counts from its log start offset.
3. When every committed position reaches the watermark's end offset, `at − 5 s` is stored as the checkpoint
   (`ProjectionFreshness.confirm`, monotonic, never in the future) and the watermark is dropped. The next tick takes
   a new one. If everything available at `at` was applied, the same holds for any earlier instant. The 5-second
   skew margin of RA01.1 is therefore applied once, at confirmation. The stored value is exactly what Core
   receives, and the time of a request never changes it. The first version capped the value at request time
   instead (`min(checkpoint, now − 5 s)`). With a checkpoint younger than 5 s, that made `updatedAt` move with the
   request clock. The integration suite caught this, and the done condition forbids it.

The listeners commit an offset only after the intake transaction committed (AckMode.RECORD, RA02.1). A reached
watermark therefore proves that every record available at `at` was applied or parked, whatever the reason
(rejected as poison, or `RETRY_EXHAUSTED` after an unclassified failure such as a rollup with no view to move).
A topic that does not exist yet has no record to wait for: its end offsets are empty and the watermark is reached
at once (a missing topic is quiet, not a fault).

The rule assumes Core's producer is not transactional (true today: no `transactional.id` in Core). With
transactional producers, the commit marker at the end of a partition would keep the committed position one below
the read-committed end offset, and a quiet topic would wait forever. Moving to transactions requires
`fixTxOffsets` on the listener containers first.

**Why a watermark and not "lag is zero".** RA01.1 phrased the rule as "advances while consumer lag is zero". Under
steady traffic a new record is almost always in flight, so a lag-zero check would rarely confirm anything while the
consumer keeps up. The watermark waits only for the records that existed at `at`. A quiet topic is confirmed on the
tick that takes its watermark, a busy topic about one tick later. The meaning of `updatedAt` is unchanged.

| Outcome | When | Effect |
| --- | --- | --- |
| `CONFIRMED` | committed positions reached the watermark | checkpoint moves to the watermark's instant |
| `WAITING` | the group is still behind the watermark | nothing; the watermark is kept |
| `BACKFILLING` | the RA02.4 bootstrap is enabled and its source for this topic has not completed in the configured generation | nothing; history not loaded yet |
| `FAILED` | broker, database or clock failure | nothing; the watermark is kept |

Each outcome is counted (`libra.analytics.freshness.ticks{topic,outcome}`). A change to `FAILED` logs one WARN line
with the topic name only, and a recovery logs one INFO line, so a long outage does not flood the log.

A stalled consumer or an unreachable broker stops the checkpoint. `updatedAt` then ages, and Core shows the answer
as STALE after 15 minutes. It never turns into zeros or a 503. A quiet topic keeps confirming. With the bootstrap
disabled, the Kafka log is taken as the whole history (RA02.4).

## The statistics endpoint

After the service JWT check of RA01.3 (exact scope `statistics:read`):

| Step | Failure |
| --- | --- |
| `Content-Type` must be `application/json`, with no charset or UTF-8 | 415 `UNSUPPORTED_MEDIA_TYPE` |
| Body at most 1,024 bytes (Content-Length checked before reading) | 413 `PAYLOAD_TOO_LARGE` |
| Closed object with exactly `from` and `to`, each an RFC 3339 full-date string; no duplicate keys, no trailing content | 400 `INVALID_REQUEST` |
| `from <= to`, at most 31 days, `to` not after the UTC date of now + 5 s | 400 `INVALID_REQUEST` |
| A confirmed `core.playback.v1` checkpoint exists | 503 `ANALYTICS_NOT_READY` |
| Database failure | 503 `ANALYTICS_UNAVAILABLE` (RA01.4 handler) |

The 200 body is exactly `{from, to, updatedAt, qualifiedViews, uniqueViewers, acceptedWatchedMs}` with
`Content-Type: application/json` and `Cache-Control: no-store`. `from` and `to` echo the request. `updatedAt` is the
stored playback checkpoint (statistics depend on that topic only), truncated to whole seconds, so it is never later
than the confirmed instant. `Freshness.updatedAt` still caps it at now − 5 s. That cap only matters when the
clock moved backwards since confirmation. The checkpoint is read before the totals: a fact committed before the
checkpoint was confirmed is visible to the totals statement that follows. No handler writes; problem bodies never
quote the request.

The recommendations operation is unchanged: still 503 (RA05.2).

## Verification

Unit tests:

| Suite | Tests | What it shows |
| --- | --- | --- |
| `WatermarkTest` | 7 | an empty partition needs no position; one below the end offset is not reached; the map is copied |
| `CheckpointAdvancerTest` | 8 | the clock is read before the end offsets; a busy topic confirms the old watermark, not the current time; later records do not delay it; BACKFILLING; failures isolated per topic with the watermark kept; a future value is refused; the store never moves back |
| `KafkaConsumerProgressTest` | 4 | a missing topic has empty end offsets; other broker failures and timeouts fail the tick with fixed text; a group that never committed has no positions |
| `CheckpointSchedulerTest` | 4 | counters per topic and outcome; one WARN per outage and one INFO on recovery; an exception or an `Error` never cancels the task and its message is not logged |
| `CheckpointPropertiesTest` | 8 | interval and admin timeout bounds |
| `BootstrapBackfillTest` | 4 | disabled bootstrap is complete; enabled waits for the configured generation's matching source |
| `StatisticsRequestsTest` | 32 | media types, 413 before the stream is read, strict JSON and dates |
| `InternalQueryControllerTest` | 1 | an aborted upload is 400, not 500, and nothing is queried |

Integration tests:

- `StatisticsApiIntegrationTest` (18, PostgreSQL): no checkpoint is 503 `ANALYTICS_NOT_READY`; a checkpoint with
  no facts is 200 with real zeros; facts give exact totals; only the playback checkpoint counts; a 901-second-old
  checkpoint is still served unchanged; repeated requests write nothing. Every `statistics-query-*` fixture of
  the RA01.1 corpus gets 200/503 when valid and 400 otherwise, and no request text appears in the problem. 415, 413
  and 400 are closed problems with the correlation ID. A wrong scope is 403 before the body is read.
- `StatisticsHttpCompatibilityIntegrationTest` (2, real HTTP server): Core's exact request headers and body;
  status 200, media type `application/json`, no encoding, at most 65,536 bytes, under 500 ms after warm-up,
  `updatedAt` not in the future, `uniqueViewers <= qualifiedViews`; a 503 is `application/problem+json`.
- `CheckpointFreshnessIntegrationTest` (1 scenario, PostgreSQL + Kafka 4.3.1, 1-second ticks):
  - empty topics are confirmed and the API answers zeros;
  - a produced event passes the checkpoint and is counted;
  - with only the playback listener stopped, the playback checkpoint stays behind the new record for 6 s while
    catalog and profiles keep advancing, and the API answers the old count with an unchanged `updatedAt`;
  - after restart the event is counted;
  - a paused broker freezes every checkpoint and the API keeps the last answer, with failed ticks counted, then
    the checkpoints recover;
  - with the scheduler stopped, 20 requests leave `input_checkpoints` unchanged.

The integration suite found the first version's request-time cap (described above) and a test-side Kafka admin
misconfiguration. Review findings fixed in this milestone: a missing topic made every tick fail; nanosecond
`occurredAt` could split one fact over two days (RA04.1); an aborted upload was a 500; an `Error` could stop the
scheduler silently; a fixture test could fail in the last seconds of a UTC day.

Mutants killed: end offsets read before the clock; no skew margin at confirmation; watermark reached one
record early; a missing topic treated as a failure (unit suites); `updatedAt` from request time
(`StatisticsApiIntegrationTest`). With RA04.1's four, 9/9 were killed (`tmp/ra04-mutants.log`).

## Gate

Shared by RA04.1 and RA04.2. Run on 2026-11-18 on commit `c4f6610` with the uncommitted RA02 and RA04 work in the
tree, by the `ci-gate` agent in the order of `.github/workflows/core-ci.yml`. Logs are in `tmp/ci-gate/`; module
runs and mutants are in `tmp/ra04-analytics-verify-*.log` and `tmp/ra04-mutant-*.log`.

| Step | Result |
| --- | --- |
| `infra/ci` failure-path tests | 122 tests OK |
| `./mvnw -o -B -ntp clean verify` (JDK 21.0.10, offline) | BUILD SUCCESS |
| `infra/ci/check_test_reports.py` | PASS: 137 suites, 1,152 tests, 0 failures, errors or skips; the 14 required Analytics suites present |
| `infra/ci/check_contracts.py` | PASS, `analytics-v1` manifest 79 fixtures |
| Compose template (`--env-file .env.example`, throwaway keys) | exit 0 |
| Core packaged smoke / Media packaged smoke | 11 / 17 checks PASS |
| `check_smoke_evidence.py` | PASS |

| Module | Unit suites / tests | Integration suites / tests |
| --- | --- | --- |
| core | 15 / 140 | 16 / 155 |
| media | 37 / 244 | 27 / 214 |
| recommendation-analytics | 28 / 294 | 14 / 105 |

The CI gate now requires four more Analytics suites: `StatisticsRollupIntegrationTest`,
`StatisticsApiIntegrationTest`, `StatisticsHttpCompatibilityIntegrationTest` and
`CheckpointFreshnessIntegrationTest` (`REQUIRED_ANALYTICS_SUITES`, `infra/ci/test_report_gate.py` updated).
Local deviations from CI: Windows 11 instead of ubuntu-26.04; FFmpeg 9.0.2 instead of the pinned 8 (the version
assertion step was not run); Maven offline; the workflow was not run remotely.

## Limits

- A parked record is behind the checkpoint but not in the data. The checkpoint means "applied or parked", and
  `dead_letters` shows what is missing (RA02.3).
- Raising `ANALYTICS_BOOTSTRAP_GENERATION` to rerun the bootstrap stops every checkpoint until the new generation
  completes its sources, so Core shows the statistics as STALE after 15 minutes even if the old data was complete.
  This is intended: the rebuild is not known to be complete until it has run.
- With the bootstrap disabled, a Kafka log trimmed by retention before Analytics first read it loses that history
  without delaying the checkpoint: the group has no committed offset and counts from the log start.
- There is no gauge of the checkpoint age in Analytics yet; Core's STALE status is the signal. Metrics and alerts
  are RA09.1.
- Analytics and Core were not run together: the HTTP compatibility suite reproduces Core's request and checks the
  response rules Core enforces, but Core's real adapter against a real Analytics runs in a later end-to-end smoke
  (RA09).
- Local evidence only; the CI workflow was not run remotely and the packaged smokes were not run.
- Next per plan ("Thứ tự triển khai"): RA05.1–RA05.2, preferences and ranking backend.
