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

Database credentials in `.env.example` and `infra/compose.yaml` are disposable local-development values. Storage credentials must be generated explicitly. Use secret injection and separately provisioned database roles outside this Compose environment.

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
Populate `SEAWEEDFS_ACCESS_KEY` and `SEAWEEDFS_SECRET_KEY` in the ignored `.env`
with independent random values before starting Compose; see
[Media storage security](docs/media-storage-m06.md) for local setup and verification.

[Media Core service authentication](docs/media-service-auth-m07.md) adds the
dedicated Core-to-Media identity and bounded internal client. Admin upload,
signed staging PUT, and [durable completion](docs/media-completion-m13.md) are
implemented, with [completion race and failure hardening](docs/media-completion-hardening-m14.md).
[Bounded worker and lease lifecycle](docs/media-worker-m15.md) are implemented
with PostgreSQL ownership, renewal, restart recovery and three-attempt retries;
[lease recovery hardening](docs/media-worker-m16.md) adds stale-worker, database-loss
and killed-process evidence. [Verified source freezing](docs/media-source-m17.md) provides the stage
that proves staging bytes and selects a private immutable source under the lease;
[snapshot recovery hardening](docs/media-snapshot-m18.md) adds crash-point, staging-mutation, storage-fault and
scratch-reclaim evidence. [ffprobe validation](docs/media-probe-m19.md) judges the frozen MP4 against the
first-slice H.264 policy and records only the validated metadata;
[probe hardening](docs/media-probe-hardening-m20.md) adds the hostile-input matrix, a forced MP4/MOV demuxer and
resource bounds measured in the packaged image. The [FFmpeg pipeline](docs/media-transcode-m21.md) encodes the frozen
source into one decodable H.264/AAC HLS rendition inside a local attempt workspace, with a checked inventory and a service-written master
playlist; it uploads nothing and makes nothing ready. The worker is disabled by
default and requires a real handler before enabling; output storage, READY and protected HLS delivery remain subsequent milestones.

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

Milestone 8 adds [Core CI and packaged smoke](docs/core-delivery.md), a complete
[configuration reference](docs/core-configuration.md), and an
[API/operations index](docs/core-delivery.md#api-and-contract-map). Use the documented
gate sequence for Core acceptance; it checks all tests, contracts and a disposable
Core/PostgreSQL/Kafka/Mailpit/Nginx stack without a workstation `.env`.

```powershell
.\mvnw.cmd clean verify
pnpm.cmd --dir web lint
pnpm.cmd --dir web build
docker compose --env-file .env -f infra/compose.yaml config --quiet
```

The first Maven Wrapper run downloads Maven. `clean verify` runs unit/wiring tests through Surefire and `*IntegrationTest` through Failsafe. Core integration tests require Docker and use real PostgreSQL, Kafka, and Mailpit containers; Docker absence fails the build. H2 context tests cover only wiring. Media has real PostgreSQL and SeaweedFS storage tests plus real ffprobe fixture tests, which need an `ffprobe` on `PATH` or in `LIBRA_FFPROBE_PATH` and an `ffmpeg` beside it or in `LIBRA_FFMPEG_PATH` to generate their fixtures (a missing tool fails the build); Real ffmpeg transcode tests decode every output; protected HLS integration remains later work.

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
