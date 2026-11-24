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
playlist; it uploads nothing and makes nothing ready. [Encoding hardening](docs/media-encoding-hardening-m22.md)
makes decode errors fatal, delivers variable-rate sources at their average rate, checks the rendition against the
source duration, writes the variant playlist from the checked inventory and stops blaming the input for signals,
external kills and full disks. [Output finalization](docs/media-finalization-m23.md) stores the checked inventory
under the claim's private prefix, reads every object back and commits READY, the duration, the aggregate version
and its outbox event in one lease-fenced transaction; PROCESSING and FAILED transitions now record their events too.
[Finalization hardening](docs/media-finalization-hardening-m24.md) pins the bytes the seal vouched for (segment
digests and exact playlists, so a tag-borne URI or a later rewrite cannot be stored as READY), adds database guards
that keep a selected generation immutable and refuse READY without its event, and records the crash, overtaking and
lost-acknowledgement matrix. The worker is disabled by default and enabled in Compose (M29); with the transcode stage configured, the finalizer is its job handler.
[Outbox publication](docs/media-outbox-publication-m25.md) delivers those events to `media.assets.v1` at least once,
in version order per asset, behind database guards that keep events immutable; Core projects the published records
(shared wire examples in `contracts/events/examples/`). The relay is off by default and on in Compose.
[Delivery hardening](docs/media-delivery-hardening-m26.md) replaces a producer left in a fatal state, lets the
database accept only the exact contract event of each transition, records why a delivery failed with backlog and
age gauges, and shows a killed packaged relay and a hung broker losing nothing.
[Protected HLS delivery](docs/media-protected-delivery-m27.md) serves the master playlist, rendition playlist and
segments of a READY generation at `/v1/streams/{sessionId}/{name}` (`/api/media/...` through Nginx) only to a request
carrying exactly one Core-signed playback ticket cookie for that session, whose asset version Media records as READY
for the ticket's binding; keys come from Media's record and a closed name vocabulary. It is off until
`MEDIA_PLAYBACK_ENABLED=true` with Core's playback public key (Compose forwards `CORE_PLAYBACK_PUBLIC_KEY`).
[Protected delivery hardening](docs/media-protected-delivery-hardening-m28.md) bounds each instance's concurrent streams
in total and per playback session (`MEDIA_DELIVERY_MAX_STREAMS`, `MEDIA_DELIVERY_MAX_STREAMS_PER_SESSION`; a retryable 503
with `Retry-After`), cuts a response that outlives `MEDIA_DELIVERY_TRANSFER_TIMEOUT` or its ticket instead of letting a
slow player hold it, and refuses a ticket whose `iat` or `exp` is not a JSON integer.
[Ingress playback](docs/media-protected-delivery-ingress-m29.md) serves the slice end to end in the real Compose stack:
an administrator's upload becomes READY through Media's worker and Kafka, Core publishes it, and a Premium viewer's
Core-signed ticket plays the HLS through Nginx, which forwards only GETs of the exact stream route, never caches or spools it to
disk, and bounds concurrent streams per playback session (4) and per client address (16) with the same retryable 503.
`infra/smoke/check-media-m29.ps1` runs it with generated keys and removes everything it created.
[Delivery abuse hardening](docs/media-protected-delivery-abuse-m30.md) narrows what Nginx forwards beneath
`/api/media/` to Media's health (bodiless GET only) and the exact stream route, so no other spelling, such as a `;`
parameter, an encoded backslash or another case, reaches Media around the stream limits; it answers any failure before
the first byte with the 503 problem a player retries instead of the container's empty 403; and it shows through the
stack that an expired Core ticket stops a presentation until a new admission, that no cache, validator or range lets a
response be reused, and that players who hang up give their streams back. `infra/smoke/check-media-m30.ps1` runs it.
[Lifecycle cleanup](docs/media-lifecycle-cleanup-m31.md) expires abandoned uploads through the contract transition
(`EXPIRED`, asset FAILED with its event), deletes the staging object and every unselected attempt's source and output
of uploads that are settled (expired, or with a terminal job) once a write grace has passed, never a selected source or
output and never by object age, and adds the job's stage, last failure and pending retry plus unsent events to the
upload status (contracts 1.1.0; deploy Core and Media together) and low-cardinality job, queue and cleanup gauges. The
sweeper is off by default and on in Compose (`MEDIA_LIFECYCLE_ENABLED`).
[Cleanup race hardening](docs/media-cleanup-race-hardening-m32.md) stops a worker writing once its own measure of the
lease has run out, even when its heartbeat is stuck; records the storage layout once (V9) so an instance configured
with other buckets or prefixes never starts and cleanup never records an upload clean after looking in the wrong place;
bounds every gauge read so a locked or stalled database reads as unknown; and answers any unnamed failure of the upload
API with a sanitized 503 and one fixed log line. It races cleanup with a late writer, a finalizing successor, a browser
PUT across the deadline and other sweepers, and resumes it after each interruption.
[Browser vertical slice](docs/media-browser-vertical-slice-m33.md) runs the slice from a real browser. Headless Google
Chrome, driven by Playwright through a test-only harness page behind the repository ingress, uploads a clip from a file
input straight to private storage (a real CORS preflight against the bucket rule), waits for Media and Core to report
READY, and publishes it. A verified Premium viewer then plays it with the pinned hls.js using its own HttpOnly ticket
cookie, seeks, renews through Core (the next segment's request carries the renewed ticket) and plays to the end, with
no request mocked and no READY injected. `infra/smoke/check-media-m33.ps1` runs it after `pnpm install` in `web/`.
[RA03 browser acceptance](infra/smoke/check-web-ra03.ps1) drives the real web product (the `web` image, Core, Media, Analytics) in Chrome through the ingress: `infra/smoke/web_ra03_seed.py` seeds content with real HLS and three viewers, then `web/e2e/ra03` covers sign-in/refresh, catalog, the player, resume and hardening cases; it needs all three JARs, `pnpm install` in `web/` and ffmpeg.
[Outages and restarts](docs/media-outage-restart-hardening-m34.md) repeats the slice while Core, Media, Kafka, storage
and PostgreSQL are stopped, killed, paused or restarted at chosen points, and checks what each interrupted operation
converges to: one job per upload, READY recorded once, the selected output untouched by a resumed stale worker, no
ticket-less media and Core's current authority for admission and renewal. It fixes four gaps it found: Core now
retries a Media event in place while its own database is unavailable instead of dead-lettering it, every application
service in Compose restarts after it exits (Media refuses to start while storage is down), the ingress re-resolves its
upstreams so a restarted service on a new address is reached again, and it answers an unreachable Core with a prompt
503 `CORE_UNAVAILABLE` problem. `infra/smoke/check-media-m34.ps1` runs the matrix.
[Verification and handoff](docs/media-verification-handoff-m35.md) packages the checks into one repeatable,
fail-closed sequence. `infra/ci/verify-local.ps1` reproduces the CI job locally (Maven, report and contract gates,
the Core smoke and the accepted-flow Media smoke `infra/smoke/check-media.ps1`). The report gate now requires
executed Media integration suites, including the real ffprobe, FFmpeg, socket and storage suites, and a new gate
accepts only smoke evidence from the current run, deleting any file that looks like a secret before the artifact
upload. The milestone doc is the handoff reference for configuration, startup, states, error codes, recovery and
protected delivery.
[Final acceptance](docs/media-final-acceptance-m36.md) closes the first Media slice. The toolchain record now names
the git revision and tree state a run verified, and every test and smoke evidence file carries that run's identity;
the gate fails if the tree changes during the run. Stale PASS files are deleted
before a run, and the acceptance smokes (M30, M33, M34 and a new restart check) are gated by
`verify-local.ps1 -Acceptance`. Smoke cleanup is proven by asking Docker for leftovers. The restart check
(`infra/smoke/check-media-m36.ps1`) restarts a project with its volumes retained and found that Kafka lost every
event and offset on `down`/`up`, because the image kept its log in `/tmp`; Compose now keeps it on a named volume.
A service's database role can no longer connect to another service's database; a `postgres-data` volume
initialized before this change keeps the old grants until it is recreated or the `REVOKE` statements in
`infra/postgres/init/001-create-databases.sql` are run by hand. The doc maps the original acceptance scenarios to their
evidence and separates local, browser, remote CI and deployment evidence.

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

The first Maven Wrapper run downloads Maven. `clean verify` runs unit/wiring tests through Surefire and `*IntegrationTest` through Failsafe. Core integration tests require Docker and use real PostgreSQL, Kafka, and Mailpit containers; Docker absence fails the build. H2 context tests cover only wiring. Media has real PostgreSQL and SeaweedFS storage tests plus real ffprobe fixture tests, which need an `ffprobe` on `PATH` or in `LIBRA_FFPROBE_PATH` and an `ffmpeg` beside it or in `LIBRA_FFMPEG_PATH` to generate their fixtures (a missing tool fails the build); Real ffmpeg transcode tests decode every output, and the protected-delivery suite decodes what the stream routes served.

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
continue watching, next episode and transactional viewing outbox. Media HLS delivery
followed in the Media milestones above; the product player and Analytics consumption
remain separate work.

See [Core watchlist and community](docs/core-community.md) for Milestone 6:
profile watchlists, qualified account reviews, reporting, administrator moderation,
privacy-safe public ratings and transactional moderation audit.

See [Core Analytics read adapters](docs/core-analytics-adapter.md) and
[Core integration operations](docs/core-integration-operations.md) for Milestone 7:
recommendations/statistics with truthful fallback, scoped service JWTs, durable
outbox delivery/recovery, Media DLT inspection/redrive, transactional audit and
administrator metrics. Analytics and inbound Media HTTP authentication are disabled
by default pending dedicated keys. Actual Analytics projections remain
separate service work.

[Analytics contracts and ownership](docs/analytics-contracts-ra01-1.md) starts the
Recommendation & Analytics roadmap (RA01.1). It fixes data ownership between Core and
Analytics, the consumed `core.catalog.v1`/`core.profiles.v1`/`core.playback.v1` rules,
statistics and freshness semantics, error codes (contract 1.1.0) and the V1 versioning
policy. The shared corpus `contracts/fixtures/analytics-v1` (79 fixtures) is checked by
`infra/ci/check_contracts.py`. Core integration tests prove that Core's outbox records
and query bodies equal the fixtures, and that Core's adapter reaches the declared outcome
for every downstream body. They found that Core accepted epoch timestamps, Base64 UUIDs
and array dates the contract forbids; Core now rejects them.

RA01.2–RA01.4 turn the Analytics scaffold into a running foundation. See
[persistence foundation](docs/analytics-foundation-ra01-2.md),
[internal service authentication](docs/analytics-service-auth-ra01-3.md) and
[authentication and startup hardening](docs/analytics-foundation-hardening-ra01-4.md).
The service uses plain JDBC and Flyway on PostgreSQL (no JPA, no H2), keeps one confirmed
checkpoint per consumed topic, and accepts only Core's dedicated RS256 service JWT on
`/internal/**` with the exact scope per operation. Authentication is disabled by default;
Compose passes Core's public key and key ID through `ANALYTICS_CORE_SERVICE_*`. No
projection is served yet: an authorized query gets `503 ANALYTICS_NOT_READY` or
`ANALYTICS_UNAVAILABLE`, never zeros. `infra/ci/check_test_reports.py` now fails unless
four named Analytics integration suites (Testcontainers PostgreSQL) ran and passed.

RA02.1–RA02.5 make Analytics consume Core's three topics. See
[catalog and profile ingestion](docs/analytics-ingestion-ra02-1.md),
[playback facts](docs/analytics-playback-facts-ra02-2.md),
[retry, dead letters and redrive](docs/analytics-dead-letters-ra02-3.md),
[bootstrap from Core](docs/analytics-bootstrap-ra02-4.md) and
[ingestion hardening](docs/analytics-ingestion-hardening-ra02-5.md).
Every record goes through one intake that validates it strictly against the V1 schemas,
stores an event receipt and the projection change in one transaction, and lets Kafka
commit the offset only afterwards. Catalog nodes keep the newest version, deleted
profiles stay deleted, and playback facts add each accepted increment once, whatever the
arrival order. Poison records go to `analytics.dead-letters.v1` and the `dead_letters`
table; database outages are retried, never parked. A new Core endpoint set
(`contracts/http/core-internal-analytics.v1.json`, Analytics' own service key) lets
Analytics rebuild from catalog and profile snapshots and a replay of the playback outbox.
Fault-injection suites show the stored result converges to the canonical event set.

RA04.1–RA04.2 serve platform statistics. See
[daily statistics](docs/analytics-statistics-ra04-1.md) and
[statistics API and freshness](docs/analytics-statistics-api-ra04-2.md). Each playback
fact updates a per-day, per-account rollup in its intake transaction: one qualified
view per session on the day of its lowest qualifying sequence, watched time on the
day of each event, and unique viewers counted as distinct accounts over the whole
window. `POST /internal/v1/statistics/query` now answers with real totals once the
playback checkpoint is confirmed. A background scheduler confirms checkpoints from
the consumer groups' committed offsets, never from request or event time, so a quiet
topic stays fresh and a stalled consumer or broker ages `updatedAt` instead of
returning zeros.

RA05.1–RA05.2 add rule-based recommendations to Analytics. See
[genre preferences](docs/analytics-preferences-ra05-1.md) and
[candidate ranking](docs/analytics-ranking-ra05-2.md). Each linked playback fact adds
its accepted watched time to a per-profile rollup in its intake transaction, and a
profile tombstone erases it. Interests cover the last 30 UTC days, cap every item at
one full watch and every title at 6 h, and split a title equally over its genres.
`POST /internal/v1/recommendations/query` now ranks published, playable movies and
series: genre matches first, then titles watched by the most accounts in 7 days, then
the newest. Completed titles are left out. A profile without history gets the
popular-then-new list, and the answer is 503 until the catalog, profile and playback
checkpoints are all confirmed.

RA03.1–RA03.5 build the viewer web app in `web/`. See
[Core client, session and profiles](docs/web-session-ra03-1.md),
[catalog and title detail](docs/web-catalog-ra03-2.md),
[protected HLS player](docs/web-player-ra03-3.md),
[resume and next episode](docs/web-resume-ra03-4.md) and
[browser hardening](docs/web-hardening-ra03-5.md). The browser talks only to Core through
the ingress: HttpOnly cookies, one cached CSRF token, one refresh at a time across tabs,
and a profile chosen per tab whose responses are never shown for another profile. The
player admits once per action, plays Media's protected HLS with hls.js, renews the ticket
by the server's clock, and reports progress so that only time really spent playing is
credited. Resume, continue watching and the next episode come from Core. `pnpm test` in
`web/` runs the unit tests; `infra/smoke/check-web-ra03.ps1` runs the browser suite on the
real stack.

RA04.3–RA04.4 add the administrator statistics dashboard. See
[statistics report and dashboard](docs/analytics-statistics-report-ra04-3.md) and
[statistics hardening](docs/analytics-statistics-hardening-ra04-4.md). A new internal contract
(`contracts/http/analytics-internal.v2.json`) returns, from one database snapshot, the V1 totals, one entry per UTC
day and the most watched titles; Core serves it to live administrators as `GET /v1/admin/statistics/report`, rejects
any report whose parts do not add up, and names the titles from its own catalog. `/admin/statistics` in `web/` shows
the totals, a daily chart, the ranking and the stale, unavailable and empty states. A reference dataset checks the
30-second threshold, sessions across UTC midnight, late events, several profiles per account and unique viewers over
the range.

RA05.3–RA05.4 bring recommendations to Core and the web as four lists. See
[recommendation lists](docs/analytics-recommendation-lists-ra05-3.md) and
[recommendation hardening](docs/analytics-recommendation-hardening-ra05-4.md). Each item carries the rule that
selected it (genre interest, similar genres, popular or newly published) and the algorithm version `rules-v1`; Core
keeps only titles it can still show, falls back honestly (its own newest titles, or nothing for popular and similar)
and never grants playback from a recommendation. The home page shows personal, popular and new rows; title pages show
similar titles. `infra/smoke/check-web-ra05.ps1` runs the RA03, RA04 and RA05 browser suites in one disposable stack.
