# Analytics: Ingestion Hardening and Convergence (Milestone RA02.5)

Verification date: 2026-11-16.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA02.5 is the hardening milestone of RA02 and a product stop point of the plan: the data pipeline tolerates
duplicates, restarts and late events. It injects faults at the points that matter for an at-least-once consumer and
checks that the stored result equals the result of the canonical event set applied once, in order.

Done condition (plan): the result converges to the canonical event set; a late event still adds a fact that is
missing; only the newest *state* ignores an older version.

Contract and migration changes: none in this milestone. The fault seam `ingestion.application.IntakeProbe` was added
with RA02.1 (production uses `IntakeProbe.NONE`).

## Method

**Canonical set** (`convergence/CanonicalEvents`, deterministic seed): three profiles of two accounts (one updated,
one deleted); a series with a season and two episodes; a movie through publish (v2), failed availability (v3),
unpublish (v4), republish (v5) and ready availability (v6); playback sessions on the episodes, the movie and one
content ID unknown to the catalog, with 4–8 heartbeats each, including a seek that earns nothing, the qualifying
heartbeat and heartbeats after it, sessions of the deleted profile before and after its deletion. Every event passes
the real decoder. Expected watched time and qualifying sequence per session are computed in Java, independently of
the stores.

**Comparison** (`convergence/ProjectionDump`): every column of `catalog_nodes`, `profiles`, `playback_facts` and
`playback_sessions`, and the set of event IDs in `processed_events` (only `processed_at` is excluded), in a fixed
order, with a row diff on mismatch. The reference is the canonical set applied once, in order, through `EventIntake`.

**Order independence** (`OrderIndependenceIntegrationTest`, PostgreSQL, no Kafka): 40 seeded runs, each on an emptied
schema, deliver the set in a random permutation, including inversions within one aggregate (catalog v6 before v2,
playback sequence 5 before 1, `ProfileCreated` after `ProfileDeleted`), and each event 1–3 times. Every run's dump
equals the reference; a failure names its seed. Named cases: a playback fact delivered last still adds its increment;
the late `CatalogPublished` v2 delivered last leaves version and state at v6 but supplies the publication time;
schema- and semantic-invalid records interleaved in a run change nothing.

**Fault injection** (`FaultInjectionIntegrationTest`, PostgreSQL + Kafka 4.3.1, three partitions per topic): the
reference is built in a second application context on its own database. The main run produces the set with
duplicates and within-key reordering, interleaved with three schema-invalid records, two semantic-invalid records and
one record that reuses an event ID with another version. During the stream:

| Fault | Where | Expected effect |
| --- | --- | --- |
| exception in `beforeCommit` (once per chosen event, ~25 %) | inside the intake transaction, after the projection changed | rollback, redelivery, applied once |
| exception in `afterCommit` (once per chosen event, ~25 %) | after commit, before the listener returns, so before the offset is acknowledged | redelivery finds the receipt: `DUPLICATE` |
| listener containers stopped and started, twice | consumer restart | resume from the committed offsets |
| a third application context joins the same groups, then leaves | rebalance, twice | partitions move and come back |
| Kafka container paused for 8 s | broker outage | consumers wait; nothing is parked |

Assertions after every committed offset reached the end offset: the dump equals the reference; `dead_letters` holds
exactly the six poison records (SCHEMA_INVALID ×3, SEMANTIC_INVALID ×2, EVENT_CONFLICT ×1, all HELD) and no
`RETRY_EXHAUSTED` row; both fault kinds fired at least once and every after-commit fault was followed by a duplicate
redelivery; per-session watched time equals the independent sums; and the captured log of the run contains no payload
UUID (account, profile, session, content) and no event ID.

## Findings

Each finding is a defect in code written during RA02.1–RA02.4, before this milestone's suites existed.

- **F1 — late publication lost the publication time.** With strict "latest version wins", a
  `CatalogAvailabilityChanged` (v3) that arrived before the `CatalogPublished` (v2) it follows left the node published
  with no publication time forever, because v2 was stale on arrival. Found while designing the canonical set; fixed by
  `published_at_version` (see [RA02.1](analytics-ingestion-ra02-1.md#catalog-latest-version-wins)). Mutant M1 below
  shows the suites fail without the fix.
- **R1 — redrive stopped behind a rejected page** ([RA02.3](analytics-dead-letters-ra02-3.md#redrive)); the
  delegated implementation's own unit test asserted the defect.

A read-only review of the whole change (`code-reviewer`) found no blocker in the at-least-once path, the version
and tombstone rules, the advisory locks or the identity mapping. It found:

- **R2 (major, fixed)** — redrive still stopped the whole pass on any failure that was not a rejection, so one row
  failing deterministically hid every row after it ([RA02.3](analytics-dead-letters-ra02-3.md#redrive)).
- **D1 (minor, fixed)** — the recoverer waited 10 s for the dead-letter acknowledgement while the producer may keep
  trying for 30 s, so a slow broker could park one record twice. The wait is now 35 s and the capture keeps one row
  per source position.
- **K1 (minor, fixed)** — Core accepted its own Media control key as Analytics' bootstrap key; it now refuses it.
- **P1 (minor, fixed)** — the Core paging queries used `? IS NULL OR id > ?`, which a generic plan cannot turn into an
  index range; each now has a first-page and a next-page form.
- Missing tests, now added: the tombstone/playback race in both orders, bootstrap pages and replay concurrent with
  live delivery, dead-letter dedup by source, the event-ID log assertion the document claimed, and a required-suite
  list for Core in the report gate (`AnalyticsBootstrapIntegrationTest`, `InternalServiceSecurityIntegrationTest`,
  `AnalyticsEventContractIntegrationTest`, `AnalyticsReadIntegrationTest`).
- Accepted as limits: a dead-letter-oversized record blocks its partition; the dead-letter topic relies on broker
  auto-creation; a session's profile identity is not checked once its profile is deleted
  ([RA02.2](analytics-playback-facts-ra02-2.md#limits)).

Two problems were in test code only: the Kafka test's admin client set `default.api.timeout.ms` below
`request.timeout.ms`, and the RA01.4 broken-migration fixture was numbered `V2`, which became a duplicate of the new
`V2` migration, so the "failed migration" test passed for the wrong reason. It is now `V1000`.

## Mutation check

Each mutant was applied alone, the named suite was run on real PostgreSQL/Kafka, and the source was restored
(driver in the scratchpad, logs `tmp/ra02-5-mutant-*.log`). KILLED means a test failed; compile errors do not count.

| Mutant | Change | Suite | Result |
| --- | --- | --- | --- |
| M1 | late `CatalogPublished` no longer fills the publication time | `IngestionIntegrationTest` | KILLED |
| M2 | live profile event applies over a tombstone | `IngestionIntegrationTest` | KILLED |
| M3 | failed dead-letter publication swallowed (recoverer returns) | `KafkaIngestionIntegrationTest` | KILLED |
| M4 | a duplicate event is applied again | `OrderIndependenceIntegrationTest` | KILLED |
| M5 | the highest qualifying sequence wins | `OrderIndependenceIntegrationTest` | KILLED |
| M6 | a reused event ID is treated as a duplicate | `IngestionIntegrationTest` | KILLED |

6 of 6 killed; every source file was restored and checked afterwards. The mutation pass ran before the review fixes
below; those fixes come with their own regression tests.

## Gate

Shared gate of RA02.1–RA02.5: offline root `./mvnw -o clean verify` on 2026-11-16 (JDK 21.0.10, Docker Desktop
29.9.3, real PostgreSQL/Kafka/SeaweedFS, FFmpeg 9.0.2) passed in 18 min 18 s on a dirty tree at `c4f6610`, run by
the `ci-gate` agent (logs `tmp/ci-gate/`):

| Module | Unit | Integration |
| --- | --- | --- |
| Core | 140 (15 suites) | 155 (16 suites) |
| Media | 244 (37 suites) | 214 (27 suites) |
| Recommendation/Analytics | 198 (16 suites) | 67 (10 suites) |

- `infra/ci/check_test_reports.py`: PASS, 121 Maven suites and 1018 tests, with zero failures, errors or skips. The
  RA01.4 baseline was 102 suites and 790 tests. Media is unchanged. Core gained 4 unit tests
  (`InternalServiceSecurityAnalyticsTest`) and 8 integration tests (`AnalyticsBootstrapIntegrationTest` and new
  cases in `InternalServiceSecurityIntegrationTest`); `FoundationIntegrationTest` now expects Flyway version 9.
  Analytics went from 21 unit and 28 integration tests to 198 and 67. The gate now also requires four named Core
  suites and nine named Analytics suites.
- `infra/ci/record_toolchain.py`: PASS. `infra/ci/check_contracts.py`: PASS (all contracts including
  `core-internal-analytics.v1.json`, 79 analytics fixtures).
- `python -m unittest discover -s infra/ci -p "test_*.py"`: PASS, 122 tests.
- `docker compose -f infra/compose.yaml config` and `--profile full`: valid with the storage secrets set;
  `.env.example` leaves `SEAWEEDFS_*` empty on purpose, so it fails without them, as before.
- Not run: the packaged Core and Media smokes and `check_smoke_evidence.py`, so the `ci-gate` verdict is BLOCKED,
  not PASS. Local FFmpeg is 9.0.2 while the workflow pins 8; the Media real-tool suites pass with 9.0.2.

## Limits

- Convergence is shown for one canonical set (3 profiles, 6 catalog nodes, sessions of 4–8 heartbeats), 40
  permutations and one seeded fault run with two restarts, a rebalance and an 8-second broker pause. It is evidence
  for these schedules, not a proof for every interleaving. The fault run keeps its seed so a failure can be replayed.
- The faults are injected in the application (`IntakeProbe`) and by pausing containers. A JVM crash (`kill -9`)
  between commit and offset commit is represented by the after-commit exception, not by killing a process.
- Kafka runs as one broker; partition leadership changes, ISR shrink and transactional producers are not exercised.
- Nothing here changes the statistics or recommendations served: freshness checkpoints are still not advanced, so
  authorized queries still answer `ANALYTICS_NOT_READY` (RA04.2 next).
- Local evidence only; the CI workflow was not run remotely, and the packaged smokes were not run.
- Next per plan ("Thứ tự triển khai"): RA04.1–RA04.2, statistics on these facts and freshness from consumer
  progress.
