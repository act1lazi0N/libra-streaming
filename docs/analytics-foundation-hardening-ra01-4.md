# Analytics: Authentication and startup hardening (Milestone RA01.4)

Verification date: 2026-11-15.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA01.4 is the hardening milestone of RA01. It attacks what [RA01.2](analytics-foundation-ra01-2.md) and
[RA01.3](analytics-service-auth-ra01-3.md) built: forged, expired and wrongly scoped credentials, missing or
unusable key configuration, startup failures, a failing migration, a database outage, and credential leaks in
responses and logs. It also completes the Analytics integration-test gate that RA01.2 switched on.

Contract and migration changes: none. `V2__broken_migration.sql` exists under `src/test/resources/db/broken` only
and is never on the production classpath location `db/migration`.

### Findings

- **H1 — a database that stops answering blocked readiness and requests.** With PostgreSQL paused (socket open, no
  reply), `GET /actuator/health/readiness` did not answer within 30 seconds: the PostgreSQL driver has no read
  timeout by default, and the Hikari timeouts cover acquiring and validating a connection, not a query in flight.
  Reproduced by `AnalyticsStartupIntegrationTest#aDatabaseOutageTurnsReadinessAndQueriesIntoTruthful503sAndRecovers`
  before the repair (`tmp/ra01-4-h1-before.log`: `HttpTimeout request timed out`). The data source now sets the
  driver's `socketTimeout` (default 10 s, `RECOMMENDATION_DB_SOCKET_TIMEOUT_SECONDS`) and `connectTimeout` (5 s).
  Readiness then answers 503, liveness stays 200, an authorized query answers 503 `ANALYTICS_UNAVAILABLE`, and the
  service recovers without a restart when the database returns.
- **H2 — the service API was reachable from the public ingress.** Nginx proxied all of `/api/recommendations/`,
  including `/internal/**`, protected by the service JWT only. It now returns 404 for
  `/api/recommendations/internal/`, like the Core and Media rules. Found by reading the configuration; verified with
  `nginx -t` only (see Limits).
- **H3 — a 500 or 503 could not be traced.** The log line had no correlation ID, and a generated ID existed only in
  the response. The ID is now fixed once per request and logged with the exception type and, for unexpected
  failures, the throwing frame. Exception messages are still never logged. Found by the review.
- **H4 — a future internal handler would have been open to both scopes.** The chain lets an authenticated service
  reach unknown paths so that they answer 404. A handler added later without its own scope rule would therefore
  accept either scope. `everyInternalHandlerIsBoundToExactlyOneScope` now walks every registered `/internal/`
  mapping and fails unless exactly one scope is allowed. Found by the review; no such handler exists today.

Two test defects were found by the review and repaired: a startup test read `RECOMMENDATION_DB_*` from the
developer's shell, and the refusal suite had no positive control.

## What is refused

`CoreServiceRejectionIntegrationTest` sends each credential below through the real chain on PostgreSQL. Every one
receives the same fixed 401 problem. After each test the suite asserts that no handler ran, that
`input_checkpoints` is unchanged, and that neither the presented tokens, the configured public key nor any `canary`
value appears in the captured log. Before each test an unmodified token is accepted, so a refusal is caused by the
mutation.

| Group | Cases |
| --- | --- |
| Identity claims | Wrong, padded or absent `iss`; wrong or absent `sub`; wrong, absent or multiple `aud`, including Media's audience; wrong or absent `purpose`; absent, blank or 129-character `jti` |
| Scope | Absent, empty, unknown, upper-case, padded, wildcard, both scopes in one string, array, object, Media's scope, `scp` instead of `scope` |
| Time | Expired, expired by one second, issued in the future, lifetime 120 s and 3,600 s, future `nbf`, absent `iat` or `exp`, non-numeric `iat`/`exp`/`nbf` |
| Claim types | Numeric `iss`, array `sub`, numeric `aud` and `jti`, array `purpose` |
| Signature | Foreign key, RS384, RS512, PS256, other or absent `kid`, tampered payload (also tried on the operation it asked for), empty, missing and altered signature, unsigned token |
| Transport | No credential; cookies with forged role and user headers; query and form parameter; wrong scheme or case; empty bearer; 8,193-character token; two tokens in one header; two `Authorization` headers in either order |

`CoreServiceSecurityIntegrationTest` (RA01.3) adds browser access tokens, playback tickets and wrong-scope 403s.
A refused call stays refused whatever its body (100,000 characters tried) or correlation header says.

## Startup and failure behavior

`AnalyticsStartupIntegrationTest` starts the application class as `main` does, each case on its own database:

| Case | Result |
| --- | --- |
| Empty database, started twice | Starts; the second start migrates nothing |
| Database settings empty, password empty or wrong | Startup stops; no table is created; the password is not in the log |
| Authentication enabled with no key, no key ID, garbage or a 1,024-bit key | Startup stops; the key text is not in the log. With a real key it starts. Disabled mode starts with garbage in the key variable |
| Migration that fails after its first statement | Startup stops; the half-applied table is gone and V1 with its three rows is intact; the next normal start succeeds |
| Edited checksum of an applied migration | Startup stops |
| Schema migrated by a newer build (version 99 recorded) | Startup stops |
| Tables present without migration history | Startup stops; the foreign table is untouched (no baselining) |
| PostgreSQL paused while running | H1 above |

`InternalApiErrorsTest` (unit, mocked store) checks that database and unexpected failures become six-field problems
with no exception text, and that the log names the correlation ID and no connection string.

## Gate

`infra/ci/check_test_reports.py` requires these suites by fully qualified name; each needs its source, a Failsafe
report, at least one test, and zero failures, errors and skips:

- `…freshness.infrastructure.AnalyticsPersistenceIntegrationTest`
- `…internalapi.infrastructure.CoreServiceSecurityIntegrationTest`
- `…internalapi.infrastructure.CoreServiceRejectionIntegrationTest`
- `…AnalyticsStartupIntegrationTest`

The gate checks names and results. It cannot see which database a suite used; that these four use PostgreSQL through
Testcontainers is a property of their source, and H2 is no longer on the module's classpath
(`BackendArchitectureTest#persistenceIsJdbcOnly`, `pom.xml`). With Docker stopped the suites error and the gate
fails.

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
[RA01.4](#findings). Raw logs: `tmp/ra01-*.log`. The packaged smokes and
`verify-local.ps1` were not run.

## Limits

- **Clock skew.** `iat <= now` has zero leeway, as the contract and Media require, and Core truncates `iat` to the
  second. If the Analytics clock runs behind Core's by a fraction of a second, a matching fraction of calls is
  refused with 401 and Core shows `UNAVAILABLE`. On one host (Compose) the clocks are the same. A deployment on
  separate hosts needs either tightly synchronized clocks or a contract decision to allow a small `iat` leeway; this
  milestone changes neither.
- **Socket timeout and migrations.** Flyway uses the same data source, so a migration statement that runs longer
  than `RECOMMENDATION_DB_SOCKET_TIMEOUT_SECONDS` is cut off and startup fails again on every restart. V1 is far
  below the limit. A later long migration or batch query must raise the value first.
- The Nginx rule (H2) was syntax-checked only; no request went through a running ingress.
- Startup refusals are asserted as "startup throws" plus the state left behind, not by exception type, so a refusal
  for an unrelated reason would also satisfy them. Each case has a positive start on the same database beside it.
- The 16-writer checkpoint test shows convergence on the latest value; it does not force a specific interleaving.
- A request that Spring Security's firewall rejects (for example an encoded slash) is answered before the chain and
  does not get a problem body. Core never sends such a path.
- `OPTIONS` on the internal operations was not tested.
- Request-body validation, size limits and rate limiting do not exist yet; a malformed authorized request gets 503,
  not 400, until the projections arrive (RA04.2, RA05.2).
- The container image was not built or started; evidence is from the application class in the test JVM.
- Local evidence only; the CI workflow was not run remotely. The workflow job is still named "Core and Media
  verification" although it now runs and gates the Analytics suites as well.
- Next per plan: RA02.1, consuming catalog and profile lifecycle events with versioned state and profile tombstones.
