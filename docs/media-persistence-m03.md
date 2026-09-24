# Media persistence checkpoint (M03)

M03 establishes local PostgreSQL ownership for the first upload slice. It does not expose an upload route, authorize a Media control request, inspect object storage, process media, or publish a Kafka event. The wire contracts in `contracts/http/` remain the target for later milestones.

| Owner | Migration | Durable records | Boundary |
| --- | --- | --- | --- |
| Core | `V8__create_media_upload_intents.sql` | `catalog_upload_intents` | A creator-scoped request ID reserves one upload identity linked to the existing catalog candidate binding. The binding, catalog version, and intent are written in one Core transaction. |
| Media | `V1__create_media_upload_pipeline.sql` | `media_assets`, `media_uploads` | An upload and its asset are inserted in one Media transaction. Their content/binding/asset/version tuple is constrained locally; Media has no foreign key into Core. |
| Media | Same migration | `media_jobs` | A job references one upload; `UNIQUE(upload_id)` limits it to one logical job. |
| Media | Same migration | `media_outbox_events` | An event references one asset version and has a unique aggregate version for that asset. Delivery, lease, and retry state are durable. |

Core's `UploadIntentService` performs a local reservation after the current ADMIN session check. It locks the catalog row, applies the version and draft/candidate checks, stores a request fingerprint, and returns the persisted identity. An identical creator retry reads the same record while its binding is still the current candidate. A changed request body fails. Media's `MediaPersistenceService` supplies local ensure/read primitives with exact input fingerprinting; it is deliberately not wired to an HTTP controller. Core-to-Media provisioning and exact Core binding verification are later work, so this checkpoint makes no cross-service atomicity claim.

The schema enforces positive versions, byte limits, SHA-256/fingerprint shape, at-most-one-hour expiry, unique binding/staging identities, asset/upload tuple consistency, one job per upload, READY metadata presence, and outbox version uniqueness. The repository includes a short queue update used by PostgreSQL fixtures to prove state/job rollback. A production completion path still needs staging existence validation, idempotent response behavior, and conflict handling before it may call that write. Lease fencing, source immutability, full state-transition enforcement, and concurrent duplicate-write proof belong to M04 or later milestones.

The M03 PostgreSQL fixtures use disposable Testcontainers databases. `UploadIntentIntegrationTest` covers Core V1–V8 startup, V7-to-V8 upgrade with an existing identity row, creator-scoped reserve/read/retry, rollback, and database constraints. `MediaPersistenceIntegrationTest` covers empty Media V1 startup and Flyway validation, ensure/read/retry, queue rollback and commit, job uniqueness, READY metadata, outbox uniqueness, and invalid/expired input. They do not prove live HTTP authorization, S3, Kafka, FFmpeg, browser upload, or deployment behavior.
