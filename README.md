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
Copy-Item .env.example .env
docker compose -f infra/compose.yaml up -d
.\mvnw.cmd clean verify
.\mvnw.cmd -pl services/core spring-boot:run
```

Run the other services from separate terminals by changing `-pl`, then start the frontend:

```powershell
Set-Location web
pnpm.cmd dev
```

Open `http://localhost:3000`. Infrastructure-only Compose starts PostgreSQL on `5432`, Kafka on `9092`, and SeaweedFS S3 on `8333`. Use `docker compose -f infra/compose.yaml --profile full up --build` when application images are needed.

## Verification

```powershell
.\mvnw.cmd clean verify
pnpm.cmd --dir web lint
pnpm.cmd --dir web build
docker compose -f infra/compose.yaml config
```

The first Maven Wrapper run downloads Maven. Backend tests use H2 only for context wiring; PostgreSQL/Kafka/SeaweedFS integration tests will be added with their first features.
