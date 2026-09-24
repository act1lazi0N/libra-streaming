# Core: Configuration reference

Core requires Java 21, PostgreSQL, Kafka, SMTP and persistent signing/encryption
keys. `services/core/src/main/resources/application.yml` is the runtime source of
truth. The workstation Compose file is a disposable development topology, with
plaintext Kafka and local database passwords; it is not a production manifest.

## Keys and identity

| Environment variable | Requirement/default |
| --- | --- |
| `CORE_JWT_KEY` | Required Base64 random 32 bytes for identity HS256 signing. |
| `CORE_MAIL_ENCRYPTION_KEY` | Required, different Base64 random 32 bytes for queued mail encryption. |
| `CORE_PLAYBACK_PRIVATE_KEY` | Required Base64 PKCS#8 RSA DER, at least 2048 bits. |
| `CORE_PLAYBACK_PUBLIC_KEY` | Required matching Base64 X.509 RSA DER. |
| `CORE_PLAYBACK_KEY_ID` | Required playback key identifier. |
| `CORE_LOCAL_DEVELOPMENT` | `false`; explicitly enable only for loopback development. |
| `CORE_COOKIE_SECURE` | `true`; disabling requires local development mode. |
| `CORE_PUBLIC_BASE_URL` | `https://localhost`; HTTPS origin for verification/reset links. Local mode permits loopback HTTP. |
| `CORE_BOOTSTRAP_ADMIN_EMAIL`, `CORE_BOOTSTRAP_ADMIN_PASSWORD` | Both or neither. One-time bootstrap; never promotes an existing viewer. Bootstrap administrator still needs email verification for playback/Premium. |

Generate and persist keys using [identity setup](core-identity.md#local-startup)
and [playback setup](core-playback.md#configuration). Do not regenerate keys on
restart. Do not put secrets in Docker build arguments, Git, API examples, logs,
or CI artifacts. Playback public keys are available at `/v1/playback/jwks`;
private keys stay in Core. Only the smoke runner generates temporary keys, for
its own throwaway database and containers.

Identity/mail key rotation has no automatic multi-key overlap: changing the JWT
key invalidates access tokens; changing the mail key prevents decryption of
pending mail. Playback and service credentials pin configured key IDs/pairs.
Coordinate deployments and ticket lifetimes with consumers; preserve required
old decryption material in secure backups. This milestone does not implement an
online key-rotation protocol.

## Database, Kafka and mail

| Environment variable | Application default / behavior |
| --- | --- |
| `CORE_PORT` | `8081`. |
| `CORE_DB_URL` | `jdbc:postgresql://localhost:5432/libra_core`. |
| `CORE_DB_USERNAME`, `CORE_DB_PASSWORD` | `libra_core`, `core_local`; replace outside disposable development. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` for host Maven; `kafka:9092` in Compose. |
| `CORE_MAIL_HOST`, `CORE_MAIL_PORT` | `localhost`, `1025`. Compose uses `mailpit:1025`. |
| `CORE_MAIL_USERNAME`, `CORE_MAIL_PASSWORD` | Empty; inject credentials for authenticated SMTP. |
| `CORE_MAIL_AUTH` | `false`; enable for a provider requiring authentication. |
| `CORE_MAIL_STARTTLS` | `true`, required STARTTLS and server identity checking. Set `false` only for local Mailpit. |
| `CORE_MAIL_FROM` | `libra@example.test`; configure a valid sender for deployment. |
| `CORE_MAIL_WORKER_ENABLED` | `true`; pausing retains queued mail. |
| `CORE_MEDIA_LISTENER_ENABLED` | `true`; consumes Media asset state. |
| `CORE_OUTBOX_PUBLISHER_ENABLED` | `true`; publishes outbox and approved Media redrives. |
| `CORE_DLT_LISTENER_ENABLED` | `true`; persists Media dead letters. |

The main Compose file explicitly configures local DB/SMTP endpoints; setting an
unreferenced variable in `.env` does not override those container settings.
Maven does not load `.env`. Set process environment explicitly for host runs;
use secret injection and a separate deployment configuration outside local Compose.
Never use shell evaluation to load an untrusted `.env` file.

Flyway applies V1–V7 on startup and validates checksums. Hibernate validates the
schema; Flyway clean is disabled. Back up and restore each service database
separately. Changing the workstation PostgreSQL password after its data volume
was initialized does not recreate roles or update existing passwords.

Kafka topics: `core.catalog.v1`, `core.profiles.v1`, `core.playback.v1`,
`media.assets.v1`, and `media.assets.v1.DLT`. Provision matching partition counts
for Media and its DLT. Configure Spring Kafka TLS/SASL properties and broker ACLs
for deployment; HTTP service JWTs do not authenticate Kafka. See
[delivery/replay operations](core-integration-operations.md).

## Optional service HTTP contracts

| Environment variable | Requirement/default |
| --- | --- |
| `CORE_ANALYTICS_ENABLED` | `false`; disabled reads return explicit fallback/unavailable. |
| `CORE_ANALYTICS_BASE_URL` | Empty in host defaults; Compose supplies `http://recommendation-analytics:8083`. Enabled production configuration requires HTTPS. |
| `CORE_ANALYTICS_PRIVATE_KEY`, `CORE_ANALYTICS_PUBLIC_KEY`, `CORE_ANALYTICS_KEY_ID` | Required when enabled. Dedicated matching RSA pair, separate from playback. |
| `CORE_MEDIA_SERVICE_AUTH_ENABLED` | `false`; internal authentication rejects credentials while disabled. |
| `CORE_MEDIA_SERVICE_PUBLIC_KEY`, `CORE_MEDIA_SERVICE_KEY_ID` | Required when enabled. Pinned Media-owned RSA public key, separate from playback/Analytics. Never copy Media's private key to Core. |

Enable only after the downstream implementation, dedicated credentials and
private network/TLS are provisioned. Invalid enabled configuration fails startup.
Core has no live Analytics projections or Media processing implementation in this
delivery. See [Analytics semantics](core-analytics-adapter.md) and
[scoped Media identity](core-integration-operations.md#scoped-media-http-identity).

## Ingress and observability

Nginx strips `/api/core` before forwarding to Core: external
`/api/core/v1/auth/csrf` maps to internal `/v1/auth/csrf`. Its public ingress
blocks `/api/core/internal/`. Keep Core's direct listener private in deployment.
Terminate HTTPS and use secure cookies; the provided Nginx listener is local HTTP.
Core deliberately does not trust forwarded client IPs for rate limiting, so apply
appropriate per-client limits at the trusted ingress too.

`/actuator/health` exposes status without details. `/actuator/metrics` requires a
current ADMIN session. Health UP alone does not prove broker delivery, fresh
Analytics, SMTP receipt or playable HLS. Monitor durable queue depth/oldest age,
parked rows and failures using the [operations API and metrics](core-integration-operations.md#metrics-and-operational-checks).
