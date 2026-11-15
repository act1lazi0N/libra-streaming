# Analytics: Persistence foundation (Milestone RA01.2)

Verification date: 2026-11-15.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA01.2 turns the Recommendation/Analytics scaffold into a service that starts on a real PostgreSQL database, owns a
versioned schema and has a layered structure that later milestones extend. Its baseline is `5524c8e` (RA01.1).
RA01.2, RA01.3 and RA01.4 were implemented in one pass and share one gate; each has its own document.

It adds no consumer, no projection and no business table beyond the one this milestone needs.

Contract and migration changes:

- No HTTP or event contract changed.
- New Flyway migration `V1__create_input_checkpoints.sql`: table `input_checkpoints(topic, confirmed_at)`, with one
  row per consumed Core topic (`core.catalog.v1`, `core.profiles.v1`, `core.playback.v1`), a check constraint that
  closes the topic set, and `confirmed_at` NULL until a checkpoint is confirmed.
- Dependencies: `spring-boot-starter-data-jpa` → `spring-boot-starter-jdbc`; `flyway-core` →
  `spring-boot-starter-flyway`; H2 removed; Testcontainers (JUnit, PostgreSQL) added for tests.

## Structure

Packages are organized by feature, then by layer:

```text
com.libra.streaming.recommendation
├── freshness
│   ├── domain          InputTopic, Projection, Freshness
│   ├── application     CheckpointStore (port), ProjectionFreshness
│   └── infrastructure  JdbcCheckpointStore, FreshnessConfiguration
├── internalapi         (RA01.3)
└── infrastructure.security.SecurityConfiguration   default deny-all chain
```

`BackendArchitectureTest` enforces the structure on the compiled production classes:

- `application` and `domain` do not depend on Spring, Jakarta, Jackson, JDBC, Kafka, Flyway, `infrastructure` or
  `api`. The test first asserts that such classes exist, so the rule cannot pass on an empty selection.
- `domain` does not depend on `application`; `api` does not depend on `infrastructure`.
- Every class below a feature package sits in one of the four layers.
- No production class depends on JPA, Hibernate or H2.

Only the `freshness` feature exists, because it is the one piece of RA01.1's specification that every later
projection needs. No empty business package was created.

## Freshness model

The rules are those of [RA01.1, Freshness](analytics-contracts-ra01-1.md#freshness); this milestone implements the
storage and the arithmetic, not the background process that advances a checkpoint (RA02).

- `Projection.STATISTICS` depends on `core.playback.v1`. `Projection.RECOMMENDATIONS` depends on all three topics.
- `Freshness.updatedAt` returns empty when any input has no confirmed checkpoint. Otherwise it returns the oldest
  checkpoint, capped at the local clock minus 5 seconds, because Core rejects a future `updatedAt` with no tolerance.
- `ProjectionFreshness.confirm` refuses an instant after the local clock. `JdbcCheckpointStore.advance` moves a
  checkpoint forward only, in one conditional `UPDATE`, and truncates to microseconds so that the stored and the
  compared value are identical.
- Reading never writes. Nothing in a request path advances a checkpoint.

The store uses `JdbcTemplate` and joins the caller's transaction, so RA02 consumers can commit facts and the
checkpoint together.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `RECOMMENDATION_DB_URL`, `RECOMMENDATION_DB_USERNAME`, `RECOMMENDATION_DB_PASSWORD` | none | Required. A missing value stops startup; the previous localhost defaults were removed |
| `RECOMMENDATION_DB_SOCKET_TIMEOUT_SECONDS` | `10` | PostgreSQL driver socket read timeout (see RA01.4, H1) |
| `RECOMMENDATION_PORT` | `8083` | HTTP port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Not used yet; no consumer exists |

Other settings in `application.yml`:

- Hikari connection timeout 5 s, validation timeout 2 s, driver connect timeout 5 s.
- Flyway: `clean-disabled`, `validate-migration-naming`, and an empty `ignore-migration-patterns`, so a schema that
  a newer build migrated is refused instead of ignored. Baselining is not enabled.
- Health: `/actuator/health`, `/liveness` and `/readiness` are public and show no details. Readiness includes the
  database. `info` is exposed; nothing else is.
- `spring.web.resources.add-mappings: false`: the service has no static content.

## Gate

The Analytics integration-test gate was moved here from RA01.4 (plan revision of 2026-11-11), so that no RA01
milestone can pass without PostgreSQL evidence. `infra/ci/check_test_reports.py` now:

- requires at least one Failsafe suite for `core`, `media` **and** `recommendation-analytics`;
- requires four Analytics integration suites by fully qualified name (source and passing report), listed in
  `REQUIRED_ANALYTICS_SUITES`;
- therefore fails when the module is missing, when its integration tests are deleted or skipped, or when only
  unit-lane tests exist.

`infra/ci/test_report_gate.py` covers these failure paths (19 tests, 3 of them new).

Shared gate of RA01.2-RA01.4: offline root `./mvnw -o clean verify` on 2026-11-15 (JDK 21, Docker Desktop, real
PostgreSQL/Kafka/SeaweedFS, FFmpeg) passed in about 19 minutes on a dirty tree at `5524c8e`:

| Module | Unit | Integration |
| --- | --- | --- |
| Core | 136 | 147 |
| Media | 244 | 214 |
| Recommendation/Analytics | 21 | 28 |

- `python infra/ci/check_test_reports.py`: PASS, 102 Maven suites and 790 tests, with zero failures, errors or
  skips. The RA01.1 baseline was 95 suites and 743 tests. Core and Media counts are unchanged. Analytics went from
  2 suites and 2 tests to 9 suites and 49 tests; the H2 context test was removed.
- `python infra/ci/check_contracts.py`: PASS (all contracts, 79 analytics fixtures).
- `python -m unittest discover -s infra/ci -p "test_*.py"`: PASS, 121 tests, 3 of them new.
- `docker compose --env-file .env.example -f infra/compose.yaml --profile full config`: valid; the Analytics
  service receives `ANALYTICS_CORE_SERVICE_*`.

A read-only `code-reviewer` pass over the whole change found no blocker and no accepted credential that should
have been refused. Its findings and their repairs are listed in
[RA01.4](analytics-foundation-hardening-ra01-4.md#findings). Raw logs: `tmp/ra01-*.log`. The packaged smokes and
`verify-local.ps1` were not run.

## Verification

RA01.2 is covered by:

| Suite | Lane | Tests | What it proves |
| --- | --- | --- | --- |
| `BackendArchitectureTest` | unit | 6 | Layer and dependency rules above |
| `FreshnessTest` | unit | 5 | Not-ready rule, oldest checkpoint, skew cap at the nanosecond boundaries, topic names |
| `ProjectionFreshnessTest` | unit, in-memory store | 3 | Inputs per projection, reads never write, future checkpoint refused |
| `AnalyticsPersistenceIntegrationTest` | integration, PostgreSQL 18.6 | 7 | Empty database migrates to exactly the versioned schema; forward-only checkpoints and microsecond round trip; closed topic set; rollback and commit through the caller's transaction; 16 concurrent writers converge on the latest instant; public health without details and deny-all elsewhere |

`AnalyticsStartupIntegrationTest` (RA01.4) also starts the application class against an empty database twice and
checks that the second start migrates nothing.

Mocked versus real: the two freshness unit suites use the system under test with a fixed clock and, for
`ProjectionFreshnessTest`, an in-memory store. Everything that claims database behavior runs on PostgreSQL through
Testcontainers.

## Limits

- No background checkpoint exists, so `confirmed_at` stays NULL in a deployed service and every projection is
  "not ready". RA02 adds the consumers and the process that advances checkpoints while lag is zero.
- The schema holds checkpoints only. Catalog, profile and playback tables arrive with their consumers (RA02.1,
  RA02.2).
- Kafka is on the classpath and configured but unused; no broker test exists for this module yet.
- The Compose `full` profile was validated with `docker compose config` only. The service was not started from its
  container image in this milestone.
- Local evidence only; the CI workflow was not run remotely.
- Next: [RA01.3](analytics-service-auth-ra01-3.md) and [RA01.4](analytics-foundation-hardening-ra01-4.md) were
  delivered in the same pass. After them the plan continues with RA02.1 (catalog and profile consumers).
