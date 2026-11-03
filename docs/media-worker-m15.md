# Media: Bounded worker and lease lifecycle (Milestone 15)

Verification date: 2026-09-30.
Status: VERIFIED_LOCAL.

## Ownership and scheduling

Media owns the job queue in PostgreSQL. Each enabled worker instance has one
handler slot and one separate heartbeat thread. It claims one due job at a time
using `FOR UPDATE SKIP LOCKED`; there is no in-memory job backlog. Processing
runs after the claim transaction commits. All lease mutations have their own
short transactions. PostgreSQL time sampled after the row lock decides expiry;
different worker clocks do not grant authority.

Initial queue admission also records its due time using PostgreSQL time. The
upload expiry decision retains its existing application clock and lock ordering.
An API clock ahead of the database cannot delay the initial worker admission.

Each claim allocates a fresh random token and increments the durable attempt
number. Updates require the current job, upload, attempt and token, an active
stage and a lease strictly later than database time. Expired owners cannot renew
or record a result. A new instance can reclaim an expired active job with the
same logical job ID and a new token. Retry due times remain in the database.

## Failure and cancellation

Transient failures requeue the job after the configured delay. At most three
total claims are admitted, including interrupted claims. A third transient
failure, or an expired third claim, ends in EXHAUSTED / RETRY_EXHAUSTED. Permanent
input rejection ends in FAILED_PERMANENT with a stable allowlisted reason.
Terminal job and asset FAILED updates commit together. Retry leaves the asset
PROCESSING. No M15 handler result can mark an asset READY or successful.

Shutdown stops admission, interrupts the handler and waits for a bounded period.
A cooperative handler releases its current lease without resetting its attempt
budget. A handler that ignores cancellation loses renewal and is recoverable
after lease expiry. Loss of renewal authority cancels the handler; it cannot
record a failure against its successor. Database uncertainty is reported using
fixed codes, without exception text or payloads.

## Configuration and migration

V3 adds an allowlisted `failure_code` to jobs and an index for expired claims.
Existing migrations remain intact.

| Property under `libra.media.worker` | Default | Bound |
| --- | --- | --- |
| enabled | false | A handler bean is required when true |
| poll-interval | 1s | 10ms to 1 minute |
| lease-duration | 30s | 100ms to 5 minutes |
| renew-interval | 5s | 10ms to one third of lease duration |
| retry-delay | 10s | 10ms to 1 hour |
| shutdown-timeout | 10s | 10ms to 30 seconds |

There is deliberately no production job handler in M15. Enabling the worker
without one fails startup. The shipped runtime leaves admitted jobs QUEUED;
controlled handlers exist only in tests. Immutable source selection, probing,
FFmpeg, HLS, readiness events, publication and playback belong to later milestones.

The properties map to `MEDIA_WORKER_ENABLED`, `MEDIA_WORKER_POLL_INTERVAL`,
`MEDIA_WORKER_LEASE_DURATION`, `MEDIA_WORKER_RENEW_INTERVAL`,
`MEDIA_WORKER_RETRY_DELAY` and `MEDIA_WORKER_SHUTDOWN_TIMEOUT` in application.yml.
The existing upload status API returns the durable attempt count and a sanitized
terminal failure code. Retry diagnostics remain internal while the asset is
PROCESSING. Duplicate completion continues to return the original job, including
after terminal failure.

## Verification

The PostgreSQL lane exercises independent claim transactions, renewal,
replacement adapters, persisted retry timing, stale/expired rejection, permanent
failure, three-attempt exhaustion, cancellation and transaction rollback. Worker
tests exercise bounded admission, concurrent heartbeat, interruption, failure
classification, shutdown and configuration. Replacement adapters model restart;
they are not a killed/restarted packaged-process experiment. M16 owns that
additional hardening matrix.

A regression with the API clock five minutes ahead failed against the original
enqueue SQL, then was retained for the database-time repair. This distinguishes
the scheduling regression from an arbitrary delay in the test fixture.

The focused Java 21 verification passed 31 tests across worker, architecture,
configuration, workflow, real PostgreSQL scheduling/lease and secured status API
suites, with zero failures, errors or skips. All 18 HTTP/event contracts passed.
Final reactor reports are exported to `target/verification/tests.json`;
the milestone handoff is recorded in the ignored `.codex/logs` roadmap.

GitNexus 1.6.12 was consulted using an index-only PDG refresh and schema-4 runner
receipts. MCP became unavailable (`Transport closed`), so review continued via
the same cached CLI. Class impact reports identify the worker wiring and upload
persistence dependencies. Some method/record helpers have missing or incorrect
UIDs and broad name collisions; flow enumeration also has caps. These graph
results are supplementary to direct source/call-site review and executable tests.
No complete graph or taint-coverage claim is made.

Run with Java 21 and Docker Desktop:

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
```

Local tests do not establish remote CI, production deployment, actual media
processing or live storage integration for this milestone.
