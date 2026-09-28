# Media: Upload recovery and provisioning races (Milestone 10)

M10 hardens the M09 upload intent and Media ensure boundary. Core persists the
intent and candidate in one local transaction before it contacts Media. A Media
timeout or lost reply returns `MEDIA_UNAVAILABLE` while the Core intent remains
available to its creator. Repeating the same create request or reading the
upload status can resume that exact identity. Changed request fingerprints
return `IDEMPOTENCY_CONFLICT`.

## Candidate and authorization checks

When status reads find no Media upload, Core checks that the saved binding is
still the content's current candidate before it retries Media. A retired or
replaced candidate returns `UPLOAD_STATE_CONFLICT` without creating a new
Media asset. Media also checks the current candidate through authenticated
Core service HTTP before every ensure, including a repeated ensure. Live ADMIN
session and operation ownership checks run before Core exposes status or
starts recovery. The existing constraints reject nonplayable content and an
already active asset.

## Local fault checkpoints

The PostgreSQL integration tests race identical and conflicting Core creates,
verify one Core intent and binding, and assert that the Media client is called
outside the Core transaction. A deterministic blocked Media callback allows
the Core caller to be interrupted after its intent commits; the same request
then recovers the saved upload ID. Media integration tests race identical and
changed-fingerprint ensures through the production security filter and an
authenticated Core binding fixture. They check one Media upload and asset,
zero jobs, and candidate retirement denial.

The disposable HTTP smoke runs packaged Core and Media against separate
PostgreSQL databases. A loopback fault proxy first forwards a private ensure
to Media, reads Media's committed success, then closes the response to Core.
It separately returns 503 before forwarding another ensure. For both cases,
the smoke verifies the committed Core intent, repeats the operation (or reads
status), checks the exact recovered ID and matching cross-database identity
tuple, and confirms two total intents, bindings, uploads and assets with zero
jobs or Media outbox events. The proxy only injects transport failures; the
service, authentication and database operations are real.

Run after building both jars:

    pwsh -NoProfile -File infra/smoke/check-media-m09.ps1 -Scenario M10

The sanitized result is `target/verification/media-m10.json`. The disposable
database, service processes and generated keys are removed by the runner.
The local gate covers metadata provisioning and retry. It does not claim a
distributed transaction or eliminate the brief window between checking the
Core candidate and committing the Media row. Signed object upload, FFmpeg,
HLS, browser behavior, production TLS, remote CI and deployment remain
outside M10.
