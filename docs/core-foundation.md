# Core foundation (Milestone 1)

This milestone establishes runtime, API, database, and event-writing infrastructure.
It does not implement identity, catalog, subscriptions, playback, or Media/Analytics consumers.

This document records the Milestone 1 baseline. The current tree also includes
[Milestone 2 identity](core-identity.md), its required keys, cookie/CSRF workflow,
and encrypted mail queue. Follow that setup before starting the current Core service.
The current tree also includes [Milestone 3 profiles/catalog](core-catalog-profiles.md)
and the versioned Media readiness consumer.

## Runtime and tests

Keep Java 21 and Spring Boot 4.1.1. Maven manages Testcontainers through the existing Boot BOM.
Core uses the Boot Flyway and Kafka starters so the corresponding auto-configuration is present.

```powershell
# Select an installed JDK 21 if the shell defaults to another version.
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd clean verify
docker compose -f infra/compose.yaml up -d postgres kafka mailpit
powershell -NoProfile -File infra/smoke/check-foundation.ps1
```

Surefire runs `*Test`; Failsafe runs `*IntegrationTest` once during `verify`.
`mvn test` is a fast check and does not prove integration. `clean verify` requires
Docker and fails when containers cannot start; no Docker-absent skip is configured.
Reports live in each module's `target/surefire-reports` and `target/failsafe-reports`.
The existing H2 context tests remain wiring checks, not PostgreSQL evidence.

On Windows, the Maven `windows-test-sockets` profile points `jdk.net.unixdomain.tmpdir`
at the module's existing `target` directory. The host's default temporary directory caused
JDK selector initialization to fail before HTTP/Kafka operations with `Invalid argument: connect`.
Using a repository-local socket directory passed a minimal selector probe. The smoke script
uses the same setting. This only configures test/smoke JVMs; it neither skips tests nor changes
production JVM options.

Core integration tests use disposable PostgreSQL 18.6, Kafka 4.3.1, and Mailpit 1.31.1
containers with random host ports and a generated database password. They run production
migrations from an empty database, exercise real Spring transactions and database uniqueness,
send/consume Kafka records, capture a synthetic email, and test HTTP errors through the filter chain.
Test-only HTTP probes and the business fixture table are not included in production artifacts.

| Client | PostgreSQL | Kafka | SMTP | Mailpit UI |
| --- | --- | --- | --- | --- |
| Host | localhost:5432 | localhost:9092 | localhost:1025 | http://localhost:8025 |
| Compose | postgres:5432 | kafka:9092 | mailpit:1025 | http://mailpit:8025 |

Kafka's external listener binds container port 19092, mapped to host loopback 9092.
Its internal listener remains 9092. Do not use the external advertised address from another container.
Mailpit captures local messages without relaying to real inboxes; data is ephemeral.
Current SMTP defaults require STARTTLS: configure `CORE_MAIL_HOST`, `CORE_MAIL_PORT`,
credentials and `CORE_MAIL_AUTH=true` for an authenticated STARTTLS server.
Local Mailpit requires `CORE_MAIL_STARTTLS=false`, already set in Compose.
The durable encrypted email queue and account links are documented in Milestone 2.

## API convention

Core owns internal `/v1` paths; ingress exposes `/api/core/v1`. The current
deny-by-default security chain also permits the explicit public identity routes,
requires authentication for account operations, and requires ADMIN for administrative
routes. CSRF remains enforced. See the [identity contract](../contracts/http/core-identity.v1.json).

Errors handled by MVC or Spring Security use `application/problem+json`, a stable `code`,
and `correlationId`. `X-Correlation-ID` accepts canonical UUID-shaped input or generates
a new UUID; it is a diagnostic identifier, never authentication. Response header and body
share it. Client errors omit rejected values; unexpected errors omit exception/SQL details.
Actuator health uses its own status response. Container-level malformed HTTP before filters
is outside this contract. Do not log request bodies, credentials, or recovery query strings.

Maintain [OpenAPI](../contracts/http/core-foundation.v1.yaml) alongside implemented routes.
New list APIs use page size 20, bounded to 100, with deterministic ordering. Use DTOs,
UTC instants, and explicit optimistic versions where edits can conflict. These pagination
and edit conventions are not yet implemented business endpoints.

## Database and outbox convention

Each service owns its database and migration history. Core starts with
`V1__create_core_outbox.sql`; add monotonically versioned `V<number>__description.sql`
files under `src/main/resources/db/migration`. Do not rewrite applied migrations, enable
Hibernate schema mutation, use Flyway baseline-on-migrate to conceal mismatches, or share tables.
Flyway validates on startup; clean is disabled. Schema changes and constraints need PostgreSQL tests.

`OutboxWriter.append(topic, envelope)` requires an existing Spring transaction. Call it
from the same proxied application operation that updates business rows. Missing transactions
fail immediately. Duplicate event IDs or duplicate `(topic, aggregateId, aggregateVersion,
eventType)` combinations fail the transaction; they are not silently accepted as matching retries.
The future operation's idempotency handler must reconcile retries before appending.

The [event envelope](../contracts/events/core-event-envelope.v1.schema.json) includes event ID,
event type, schema version, aggregate ID/version, occurrence time, correlation ID, and object payload.
The Kafka key is the aggregate UUID string. Core may write `core.catalog.v1`, `core.playback.v1`,
and `core.profiles.v1`; `media.assets.v1` belongs to Media. Event payloads are bounded to 256 KiB
of serialized UTF-8 at the writer and must omit credentials, tokens, email, and unnecessary personal data.
Producers must enforce feature-specific payload schemas when their event types are introduced.
Consumers may ignore additive envelope fields, but must reject unsupported schema versions.

Pending rows have `published_at IS NULL` and a partial index. Milestone 1 implemented durable
transactional append; [Milestone 7](core-integration-operations.md) adds leases,
bounded retry/recovery, acknowledge-before-marking, and metrics. Unknown acknowledgements may
replay an event. Consumer deduplication and projection writes must be atomic; stale aggregate
versions must not replace newer projections. Dead-letter/redrive retains event identity.
Delivery is at least once; database and Kafka commits are not atomic together.

## Verification recorded on 2026-09-14

- `mvnw.cmd -B clean verify`: passed all three services on Microsoft JDK 21.0.12
  and Docker Desktop 29.7.2; 17 tests, zero failures/errors/skips (12 real Core integration
  tests plus five unit/context tests). The Windows socket profile was active.
- `infra/smoke/check-foundation.ps1`: passed database ownership, Kafka internal metadata,
  Kafka host metadata/acknowledged produce-consume, and local Mailpit capture.
- Contract JSON/YAML parsing and local OpenAPI references passed; this was not full
  OpenAPI/JSON Schema semantic validation. `git diff --check` passed.
- This is local foundation evidence. CI, deployment, real inbox delivery, outbox draining,
  Media/Analytics business integration, and FFmpeg/HLS were not exercised.

## Primary references

- [Spring Boot SQL configuration](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring MVC Problem Details](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Testcontainers Kafka](https://java.testcontainers.org/modules/kafka/)
- [Maven Failsafe inclusion rules](https://maven.apache.org/surefire/maven-failsafe-plugin/examples/inclusion-exclusion.html)
- [Mailpit Docker configuration](https://mailpit.axllent.org/docs/install/docker/)
