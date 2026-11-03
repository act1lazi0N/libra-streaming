# Media: Completion and queue admission (Milestone 13)

Verification date: 2026-09-29.

Core exposes `POST /v1/admin/uploads/{uploadId}/complete` through its existing
ADMIN session, creator-ownership and CSRF boundary. Core uses the durable intent
and the Media status to verify the exact upload, binding, asset and version. An
OPEN upload still requires the current Core candidate. Media's private command
requires the Core service write scope; before its first acceptance it checks the
same candidate through Core's authenticated binding read.

Media reads the staging object's private S3 metadata before opening its queue
transaction. A missing object or a nonmatching length or Content-Type receives
`409 SOURCE_MISSING`, with no job. Storage failure receives a sanitized
`503 STORAGE_UNAVAILABLE`. The transaction locks the upload, rechecks its state,
asset tuple and expiry, then commits `SUBMITTED`, `QUEUED`, and one job together.
An accepted call returns 202 with the stable job ID and `Cache-Control: no-store`.
A duplicate submitted call returns that same ID. Core status exposes QUEUED and
attempt count zero.

The metadata check is an admission precondition. It does not authenticate the
object's actual bytes or freeze them. An already admitted signed PUT can still
change staging after this check. No worker reads staging in M13. Later source
verification and immutable selection must read the bytes, enforce the expected
SHA-256 and size, and process only the selected private copy. Completion emits no
READY event, does not publish content, and grants no playback access.

## Local verification

The focused Core client, Media workflow, and PostgreSQL control suites pass.
The disposable `check-media-m11.ps1 -Scenario M13` runner uses packaged Core and
Media, separate PostgreSQL databases, the actual S3 gateways, SeaweedFS 4.46,
and headless Chrome. It verifies anonymous and missing-source denial, signed
browser PUT, one queued job, duplicate identity, and status. It restarts Media
and verifies the same job through Core and PostgreSQL. The runner removes its
containers and Chrome profile. Sanitized evidence is in
`target/verification/media-m13.json`; it contains no URL, cookie, or storage
credential. The gate establishes local queue admission and restart persistence,
not FFmpeg processing, source immutability, remote CI, or deployment.
