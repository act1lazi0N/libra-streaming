# Media: Signed upload hardening (Milestone 12)

Verification date: 2026-09-28.

M12 verifies the M11 capability against SeaweedFS 4.46 and adds gateway
concurrency bounds. No completion or processing endpoint is activated.

## Capability and payload checks

The real storage tests exercise the production presigner and S3 implementation:

- A changed staging key, frozen-source prefix, HLS bucket/prefix, method,
  Content-Type, checksum header or declared length is rejected with 403.
- A same-length body with an incorrect SHA-256 is rejected with 400. A failed
  overwrite must preserve the previously stored bytes.
- Missing signed headers and unknown-length chunked requests are rejected.
- An unchanged capability can PUT the same bytes again until expiry. It does
  not grant GET, HEAD, DELETE or anonymous listing; protected objects stay private.
- Issuance rejects nonpositive or over-limit sizes, wrong server-generated keys,
  and expired sessions. TTL is capped at 900 seconds or the remaining session
  lifetime. Submitted sessions cannot obtain another grant.

Browser verification uses real Chrome preflight and PUT from the configured
origin, then checks denial from another origin and with a changed Content-Type.
CORS controls browser behavior; possession of a signed URL still allows a direct
HTTP client to send the authorized request.

## Transport bounds and expiry

The shared S3 gateway enforces 256 MiB per request, eight concurrent requests
per client address and 64 across its listening port, returning 429 when a
connection limit is exceeded. Header reading is capped at 10 seconds. Body and
upstream inactivity timers are 30 seconds; either can end a stalled PUT. With
unbuffered proxying the observed result is a closed connection after 30 seconds;
408 or 504 is also an allowed timeout outcome. The smoke verifies a header
declaring 256 MiB + 1 is rejected with 413 before sending that body, a ninth
concurrent upload is rejected, and an idle body is terminated in the expected
time window. The global 64-request setting is inspected, not load-tested.

Clients behind one NAT share the per-address allowance. Both gateway instances
in the smoke have independent limits. Private S3 must remain unreachable outside
the gateway; these limits cannot protect a separately exposed SeaweedFS port.
The SDK request timeout only governs Media's own requests, not browser uploads.

The body timeout measures gaps between reads, rather than total upload duration
([Nginx documentation](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_body_timeout)).
A client that continually sends data can keep an admitted request alive beyond
its signed expiry, subject to the byte and concurrent-request bounds. There is
no absolute transfer deadline or aggregate storage quota in this milestone.

The real expiry test obtains `100 Continue` before a three-second capability
expires, waits past expiry, observes 403 for a new request, then finishes the
already admitted PUT successfully. This agrees with the request-admission expiry
check in [SeaweedFS 4.46](https://github.com/seaweedfs/seaweedfs/blob/4.46/weed/s3api/auth_signature_v4.go).
URL expiry, session expiry, or a database state change cannot revoke an admitted
write. M13 completion checks staging metadata before durable queue admission;
M17/M18 must validate actual bytes and freeze an immutable source before any
processing. Later cleanup must tolerate late staging writes and reconcile them.
Never process mutable staging or treat a single post-expiry delete as final.

## Reproduce and evidence boundaries

Build Core and Media jars with Java 21, then run:

```powershell
.\mvnw.cmd -pl services/media verify
pwsh -NoProfile -File infra/smoke/check-media-m11.ps1 -Scenario M12
```

The runner reuses the M11 disposable topology and writes only sanitized checks
to `target/verification/media-m12.json`. It removes its containers and Chrome
profile. Signed URLs, storage credentials and cookies are not saved in evidence.

The storage integration tests use real SeaweedFS; H2 only supplies unrelated
Spring wiring. Control tests use PostgreSQL and an authenticated loopback Core
binding fixture. The browser/HTTP smoke uses packaged Core and Media, separate
PostgreSQL databases, the actual gateway configuration and SeaweedFS. Submitted
and expired sessions are seeded directly in disposable databases because M13
completion is not implemented. The submitted fixture includes a QUEUED asset
and an existing job; denial preserves that exact job, then the fixture is removed
before the expiry check. Denied uploads create no jobs or asset events.
The processing admission check itself remains an M13 requirement.

GitNexus was consulted, but its index remained two commits behind `4b7fc69`:
the refresh did not complete, and the graph lacks the M11 signer. Graph taint
and impact results therefore do not establish complete coverage. Source review
and the real denial tests provide the evidence for this milestone. Production
TLS, distributed load, remote CI, deployment, FFmpeg and HLS remain unverified.
