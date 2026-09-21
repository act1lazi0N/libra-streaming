# LIBRA Streaming

LIBRA is a VOD learning project built as a monorepo with three Spring Boot services and a Next.js frontend.

## Modules

| Path | Responsibility | Local port |
| --- | --- | --- |
| `services/core` | Auth, users, catalog, mock subscription, history, playback admission | 8081 |
| `services/media` | Upload, FFmpeg jobs, transcoding, protected HLS | 8082 |
| `services/recommendation-analytics` | View-event projections, recommendations, statistics | 8083 |
| `web` | Next.js App Router frontend | 3000 |

Each backend owns a separate PostgreSQL database. Kafka carries integration events; SeaweedFS stores private source and HLS objects.

The credentials in `.env.example` and `infra/compose.yaml` are disposable local-development values. Use secret injection and separately provisioned database roles outside this Compose environment.

## Prerequisites

- Java 21
- Node.js 24 LTS and pnpm 11.9.0
- Docker Desktop with Compose

## Start locally

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
```

Follow [Core identity setup](docs/core-identity.md#local-startup) and
[playback key setup](docs/core-playback.md#configuration) to populate the required
keys and load `CORE_*` configuration into the terminal before starting Core.
Spring does not automatically read this `.env` when launched with Maven.

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d
.\mvnw.cmd clean verify
.\mvnw.cmd -pl services/core spring-boot:run
```

Run the other services from separate terminals by changing `-pl`, then start the frontend:

```powershell
Set-Location web
pnpm.cmd dev
```

Open `http://localhost:3000`. Infrastructure-only Compose starts PostgreSQL on `5432`, Kafka on `9092`, SeaweedFS S3 on `8333`, and Mailpit SMTP/UI on `1025`/`8025`. Kafka advertises `localhost:9092` to host clients and `kafka:9092` to Compose clients. Use `docker compose --env-file .env -f infra/compose.yaml --profile full up --build` when application images are needed.

## Verification

```powershell
.\mvnw.cmd clean verify
pnpm.cmd --dir web lint
pnpm.cmd --dir web build
docker compose --env-file .env -f infra/compose.yaml config --quiet
```

The first Maven Wrapper run downloads Maven. `clean verify` runs unit/wiring tests through Surefire and `*IntegrationTest` through Failsafe. Core integration tests require Docker and use real PostgreSQL, Kafka, and Mailpit containers; Docker absence fails the build. H2 context tests cover only wiring. SeaweedFS/FFmpeg/HLS integration remains later work.

See [Core foundation](docs/core-foundation.md) for Milestone 1 contracts, Flyway/outbox conventions, and the reusable Compose smoke check. Milestone 7 adds the durable background outbox publisher and recovery operations.

See [Core identity](docs/core-identity.md) for Milestone 2 authentication, CSRF/cookie
flows, password recovery, administrator bootstrap, mail operations, and the versioned
OpenAPI contract. Frontend identity pages remain future work.

See [Core profiles and catalog](docs/core-catalog-profiles.md) for Milestone 3 profile
ownership, catalog revisions/publication, search, and the Kafka Media projection
contract. Downstream implementations remain later milestones.

See [Core simulated Premium](docs/core-subscriptions.md) and the
[entitlement matrix](docs/core-entitlements.md) for Milestone 4: account-wide
simulated activation, purchase history, safe retries and concurrent extensions,
and centralized eligibility checks.

See [Core playback and history](docs/core-playback.md) for Milestone 5: session
admission/renewal, signed Media ticket contracts, sequence-safe progress, resume,
continue watching, next episode and transactional viewing outbox. Actual Media HLS
delivery, the player and Analytics consumption remain separate work.

See [Core watchlist and community](docs/core-community.md) for Milestone 6:
profile watchlists, qualified account reviews, reporting, administrator moderation,
privacy-safe public ratings and transactional moderation audit.

See [Core Analytics read adapters](docs/core-analytics-adapter.md) and
[Core integration operations](docs/core-integration-operations.md) for Milestone 7:
recommendations/statistics with truthful fallback, scoped service JWTs, durable
outbox delivery/recovery, Media DLT inspection/redrive, transactional audit and
administrator metrics. Analytics and inbound Media HTTP authentication are disabled
by default pending dedicated keys. Actual Analytics projections and Media HLS remain
separate service work.
