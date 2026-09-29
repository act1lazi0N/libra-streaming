# Media: Upload provisioning (Milestone 09)

M09 activates Core create/status and Media ensure/status for an administrator's
draft movie or episode. Core commits one upload intent, candidate binding and
catalog version before making a private HTTP call. Media verifies the exact
current candidate using Core's binding-read endpoint, then stores one upload
session and asset in its own PostgreSQL database. Neither service creates a job,
marks an asset READY, or publishes content during this milestone.

## Request and recovery

POST /v1/admin/catalog/{contentId}/uploads requires the existing live ADMIN
cookie and CSRF exchange. The body contains requestId, expectedVersion,
byteLength (1 to 268435456), and lowercase SHA-256. The returned status has
the server-generated upload, binding and asset identities, OPEN/UPLOADING
states and the original one-hour expiry. An identical request ID and fingerprint
reuses the committed identity; a changed fingerprint returns
IDEMPOTENCY_CONFLICT. The response is no-store.

Core calls Media only after the Core transaction returns. A timeout, malformed
response or unavailable Media returns 503 MEDIA_UNAVAILABLE; the committed
intent remains. Repeating the same POST or reading
GET /v1/admin/uploads/{uploadId} resumes provisioning with that identity.
Core checks the returned content, binding, asset, version and expiry before
exposing Media's status. The private Media PUT is idempotent on the upload ID
and exact request fields. Media obtains the candidate from Core using its
separate service identity and rejects a missing or retired candidate before
writing local rows. Internal reads and writes require separate service scopes.

## Configuration

Enable both service directions with separate RSA key pairs. Core-to-Media uses
CORE_MEDIA_CONTROL_* at Core and MEDIA_CORE_SERVICE_* at Media.
Media-to-Core binding reads use MEDIA_CORE_BINDING_* at Media and
CORE_MEDIA_SERVICE_* at Core. Only public keys cross to the receiving
service. Main Compose wires the Media outbound settings but leaves both
directions disabled until operators supply keys and enable them. The Media
binding client requires HTTPS except with the explicit local ALLOW_HTTP
setting. The existing Core client applies the same local-development boundary.
Both clients use a two-second exchange budget, no redirects, bounded bodies
and no automatic replay of writes.

## Local evidence

With Java 21 and Docker Desktop, focused PostgreSQL tests cover committed
Core retry after an uncertain Media result, status recovery, changed request
fingerprints, Media exact-candidate acceptance, denial before insert and
idempotent Media ensure. The M08 authorization/filter tests continue to check
ADMIN, CSRF and service-scope denial. Run the disposable end-to-end smoke
after building both jars with root Maven clean verify:

    pwsh -NoProfile -File infra/smoke/check-media-m09.ps1

The smoke starts real packaged Core and Media HTTP services against separate
databases in an isolated PostgreSQL container. It uses a current ADMIN login,
CSRF and both RS256 service directions. It verifies create, identical retry,
status, changed-fingerprint conflict, exact row tuple and row counts (one Core
intent/binding and one Media upload/asset, zero jobs/outbox events). It checks
that the candidate remains unpublished and anonymous Media control reads are
denied. Its sanitized result is target/verification/media-m09.json; the
isolated container and generated keys are removed at the end.

This milestone does not issue a signed PUT URL, inspect source bytes, start
FFmpeg, deliver HLS, publish Kafka events or prove a browser upload. The
disposable smoke disables Media storage because M09 writes metadata only.
M10 owns exhaustive concurrency and interrupted-call recovery cases; the
focused M09 failure test injects an uncertain response, while the end-to-end
smoke exercises the successful call path.
