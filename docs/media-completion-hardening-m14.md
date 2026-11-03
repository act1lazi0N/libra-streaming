# Media: Completion idempotency and expiry hardening (Milestone 14)

Verification date: 2026-09-29.
Status: VERIFIED_LOCAL.

## Admission behavior

The upload row lock serializes first completion. Media samples the clock after
acquiring that lock and rejects an OPEN session at or beyond its original expiry.
It commits the upload's SUBMITTED state, the asset's QUEUED state and one durable
job in the same transaction. Duplicate completion returns the committed job,
including when the original expiry has passed, without allocating an attempt.

A caller can initially observe OPEN and then encounter SUBMITTED during its
admission checks. On an admission or candidate-check failure, the workflow reads
the durable state again: it returns a submitted job if another caller committed
one, otherwise propagates the rejection. It never converts a failed check into
a new job. Database access or transaction failures produce sanitized
`503 MEDIA_UNAVAILABLE`, without SQL or exception details. A lost response leaves
the caller uncertain; retry or status read recovers the same durable identity.

URL issuance rechecks OPEN and expiry after the Core lookup and signing. A
completion committed before that last check prevents returning the new grant.
Completion after the check can overlap the response and cannot revoke the grant.
Previously issued PUT credentials remain usable until their storage admission
deadline. In-flight writes may also finish after that deadline.

## Verification boundaries

| Case | Evidence lane |
| --- | --- |
| Duplicate completion after initial OPEN read | Deterministic workflow unit regression |
| Concurrent complete requests return one job, attempt zero | Media API/security and real PostgreSQL; storage inspection stubbed |
| Expiry while waiting for upload row lock | Real PostgreSQL lock contention with controlled clock at exact expiry |
| URL signing overlaps a committed completion | Media API and real PostgreSQL with paused signer |
| Job INSERT or deferred COMMIT failure | PostgreSQL trigger faults; sanitized 503, full rollback, successful retry |
| Missing/truncated object or wrong MIME | Actual SeaweedFS HEAD through the production inspector |
| Storage timeout | Actual SDK request against an unavailable loopback endpoint; STORAGE_UNAVAILABLE |
| Lost response after Media commits | Packaged Core/Media with a loopback fault proxy, PostgreSQL and SeaweedFS |
| Existing signed PUT after completion | Real S3 replay accepted for the original checksum-bound payload |

The storage MIME fixture uses explicit `text/plain`. SeaweedFS 4.46 inferred
`video/mp4` for an `application/octet-stream` upload at an `.mp4` key, so the
fixture asserts actual HEAD metadata before checking rejection. Matching HEAD
metadata alone does not prove the declared hash or valid MP4 bytes.

## Reproduction

Use Java 21, Docker Desktop and the repository Maven wrapper:

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
pwsh -NoProfile -File infra/smoke/check-media-m11.ps1 -Scenario M14
```

The smoke runner uses disposable, isolated infrastructure and packaged service
jars. It checks absent staging, transaction rollback, one deliberately dropped
202 response, recovery through Core, one job with attempt zero, denied URL
reissue and successful replay of the prior PUT. It writes only sanitized
evidence to `target/verification/media-m14.json` and removes its processes,
containers and volumes. This M14 lane uses HTTP directly; it does not run a
browser. M11-M13 retain their separate browser smoke entry points.

## Verified local result

On branch `features/media`, HEAD `d8a459bc5072ab3884202eb7cf759780ac1b177a`
with the uncommitted M14 changes, Java 21 offline Maven `clean verify` passed
for all three backend modules (`-pl services/core,services/media,services/recommendation-analytics`).
The report checker confirmed 36 suites and 241 tests: Core 186, Media 53,
and Recommendation and analytics 2, with zero failures, errors or skips.
All 18 contracts, Python compilation and `git diff --check` passed.

The packaged M14 smoke exited successfully and recorded the failure/recovery
matrix in `target/verification/media-m14.json`. Exactly one 202 response was
dropped after Media committed; retries through Core recovered the original job
with attempt count zero. The disposable processes, containers, networks and
volumes were removed. Test and contract summaries are in
`target/verification/tests.json` and `target/verification/contracts.json`.

## Scope

No schema migration or new runtime configuration is required. Core remains the
admin/owner and catalog authority. Media remains the sole writer of Media state.
M14 does not start a worker, freeze source bytes, run FFmpeg, publish READY,
activate content, or enable playback. Byte/hash verification and immutable source
selection remain M17/M18 work; M15 starts the worker/lease slice. Local evidence
does not establish remote CI or production deployment.

GitNexus 1.6.12 was refreshed with `analyze --index-only --pdg` at the tested
HEAD and worktree. Its schema-4 runner receipt and CLI status confirmed
`up-to-date`, current content for 300 files, and no incomplete-index reasons.
Context, impact, change analysis and PDG queries covered completion, URL
issuance and queue admission. Some MCP responses retained the old eight-commit
staleness warning despite the refreshed context and CLI receipt. The analyzer
also reported bounded flow enumeration and an unresolved receiver for a queue
call, so graph results remain supplementary to direct source/call-site review
and executable tests. No Git commit was created by indexing or verification.
