# Media first-slice contract hardening (M02)

This review applies to the proposed Core and Media upload operations in
`contracts/http/core-media-uploads.v1.yaml` and
`contracts/http/media-internal-uploads.v1.yaml`. M01's state model and examples
remain in [the contract guide](media-first-slice-contracts.md). These routes are
still disabled; the table specifies required behavior for their later
implementation. Case IDs are executable contract fixtures in
`infra/ci/fixtures/media-upload-m02.v1.yaml`.

## Failure matrix

| Case | Operation ID and trigger | HTTP result | Allowed durable effects and retry rule |
| --- | --- | --- | --- |
| C01 | `createMediaUpload`: authenticated VIEWER | 403 ACCESS_DENIED | No intent or binding; no Media call |
| C02 | `createMediaUpload`: revoked/expired ADMIN session | 401 AUTHENTICATION_REQUIRED | No intent or binding; no Media call |
| C03 | `createMediaUpload`: missing/mismatched CSRF | 403 ACCESS_DENIED | No intent or binding; no Media call |
| C04 | `createMediaUpload`: new requestId, stale catalog expectedVersion | 409 VERSION_CONFLICT | No intent or binding; reread current catalog before a new request |
| C05 | `createMediaUpload`: same creator/requestId, changed content, length, digest or expectedVersion | 409 IDEMPOTENCY_CONFLICT | Original intent/binding remains; no new Media identity |
| C06 | `createMediaUpload`: existing active asset or another candidate | 409 UPLOAD_NOT_ALLOWED | No new intent or binding; active version remains selected |
| C07 | `createMediaUpload`: Media unavailable after Core commit or response lost | 503 MEDIA_UNAVAILABLE | Original Core intent/binding remains. Media result may be unknown; identical retry ensures the same uploadId/tuple and returns 200 only after confirmation |
| C08 | `issueMediaUploadUrl`: another ADMIN or changed Core candidate | 404 NOT_FOUND for foreign upload; 409 UPLOAD_STATE_CONFLICT for changed candidate | No URL returned; no session extension or new job |
| C09 | `issueMediaUploadUrl`: OPEN session at/past original expiry | 410 UPLOAD_EXPIRED | No URL; expiry may atomically mark asset FAILED and append FAILED outbox event |
| C10 | `completeMediaUpload`: staging object absent | 409 SOURCE_MISSING | OPEN retained; no job or READY |
| C11 | `completeMediaUpload`: OPEN session at/past expiry | 410 UPLOAD_EXPIRED | No job; expiry FAILED transition/outbox may occur |
| C12 | `completeMediaUpload`: Media response lost after job commit | 503 MEDIA_UNAVAILABLE | Job result is unknown to Core. Retry same uploadId returns 202 with the original job if accepted; otherwise accepts once |
| C13 | `completeMediaUpload`: duplicate after SUBMITTED, even after session expiry | 202 with same jobId | No second job or asset version; current asset state is returned, never fabricated READY |
| C14 | `getMediaUpload`: Media unavailable | 503 MEDIA_UNAVAILABLE | No fabricated READY/provisioning status; retry read after recovery |
| C15 | `getMediaUpload`: another ADMIN's upload | 404 NOT_FOUND | No information about the foreign intent or Media state |
| C16 | `createMediaUpload`: identical retry after the reserved candidate was retired/replaced | 409 UPLOAD_STATE_CONFLICT | Original intent retained for audit; no re-provisioning or new binding |
| M01 | `ensureUpload`: invalid/wrong-scope Core service token, or a browser/playback credential | 401 AUTHENTICATION_REQUIRED or 403 ACCESS_DENIED | No Media session or asset |
| M02 | `ensureUpload`: unknown Core binding, or tuple mismatch | 404 NOT_FOUND or 409 UPLOAD_STATE_CONFLICT | No Media session/asset; never substitute client-provided tuple |
| M03 | `ensureUpload`: same uploadId with changed immutable request | 409 IDEMPOTENCY_CONFLICT | Existing session/asset unchanged |
| M04 | `ensureUpload`: Core binding lookup unavailable | 503 CORE_UNAVAILABLE | No unverified new session/asset |
| M05a | `issueUploadUrl`: Core candidate lookup unavailable | 503 CORE_UNAVAILABLE | No URL or session extension |
| M05b | First `completeUpload`: Core candidate lookup unavailable | 503 CORE_UNAVAILABLE | No new job; duplicate completion can return an already committed job without a new lookup |
| M06 | `completeUpload`: duplicate after SUBMITTED | 202 with same jobId | No second job; return current asset state |
| P01 | Existing `MediaAssetStateChanged`: valid READY for exact tuple | Kafka event, then Core candidate READY | Core projection only; no implicit publication, ticket or entitlement |
| P02 | Existing `MediaAssetStateChanged`: wrong key, asset, binding, content or assetVersion | Rejected by Core decoder/projection | No selected binding/publication change; malformed events go through existing DLT handling |
| P03 | Existing publication/playback: READY candidate but no explicit publish, or no viewer entitlement | 404 hidden or 403 PREMIUM_REQUIRED as applicable | No public playback admission or Media ticket; publication remains an ADMIN versioned command |

The Core HTTP layer first verifies the current account/session, ADMIN role,
creator ownership and CSRF for POST. It must never rely on a role or owner supplied
in the request body. The operation metadata (`x-required-role`,
`x-requires-current-session`, `x-requires-upload-creator`, `x-required-scope`, and
`x-error-codes`) is a contract assertion for later implementation, not a runtime
security filter. Media's service scope is checked by its separate filter chain.
Core signs one operation-specific `scope` string in each short-lived service JWT:
`core.media.uploads:read` for status, or `core.media.uploads:write` for ensure,
URL and completion. A write token does not implicitly grant read scope.

## Credential purposes and asynchronous ownership

| Credential | Valid use | Rejected use |
| --- | --- | --- |
| Core identity cookie and CSRF pair | Current ADMIN call to Core upload routes | Media internal control and HLS segment authorization |
| Core-to-Media service JWT | Media private upload read/write operation with its matching scope | Core admin route, Core binding-read route, playback |
| Media-to-Core service JWT | Existing Core private binding read only | Media upload control, Core admin route, playback |
| Signed staging PUT URL | PUT one generated staging object before URL expiry with required header | GET/LIST, complete, internal control, frozen source, HLS |
| Core playback cookie | Exact authorized Media stream/session, bounded ticket lifetime | Upload control, Core identity or catalog administration |

Media should verify `candidate=true` and the immutable binding tuple at ensure,
URL issuance, and first completion. Core checks its own intent and current
candidate before forwarding state-changing operations. These reads occur across
separate databases, so they are not an atomic cross-service lock. If Core changes
the candidate after Media's check, an in-flight job may become orphaned; Core's
event consumer can only update that old binding and cannot publish it by itself.
Core must stop issuing further URL/complete commands for the retired candidate.
An already SUBMITTED upload still returns its original job on duplicate complete.
Scoped cleanup comes later and must not delete an active asset.

Media validates the Core-created `expiresAt` against the original intent and
one-hour maximum when it first ensures the session; identical retries cannot
move the deadline. A presigned PUT that began just before expiry may still be
in flight when the session becomes EXPIRED. Expiry denies new commands but does
not prove that staging writes have stopped, and cleanup must account for this.

Core's client maps Media timeouts, unavailable storage, an unavailable Core
binding callback, invalid downstream bodies and uncertain responses to a
sanitized `503 MEDIA_UNAVAILABLE` where the outcome is not confirmed. A 503
never means a remote write definitely did not happen. The fixed `requestId` or
`uploadId` is the recovery identity. Neither Core nor Media may turn such a
failure into 201, 200, 202 or READY from an in-memory guess. The Core intent and
Media session use separate local transactions; there is no distributed commit.
After a failed initial create, the browser knows its own `requestId` and retries
the identical create operation. The public status-by-uploadId route is used only
after Core has returned a confirmed uploadId; it is not a substitute for the
uncertain create retry.

Completion only creates a QUEUED job. A valid `MediaAssetStateChanged` READY
event must use the asset ID as Kafka key and aggregate ID, carry the exact
content/binding/asset/version tuple, a positive duration and an increasing
aggregate version. Core's existing decoder/projection checks key, tuple, event
identity and stale versions. The schema alone cannot express cross-field UUID
equality, so the producer and Core consumer must enforce it. A READY projection
does not change publication; the current Core publish command requires a READY
candidate and current catalog version. Playback admission and renewal separately
recheck effective visibility, selected binding and entitlement. A published
Premium title still cannot admit a viewer whose mock Premium has expired.

The public Nginx configuration currently routes `/api/media/` broadly while
Media has no private upload routes. M07 must add the explicit
`/api/media/internal/` deny rule before enabling those routes, and M08 must
verify it through the live ingress. M02's contract evidence does not claim that
future HTTP/filter-chain controls already run.
