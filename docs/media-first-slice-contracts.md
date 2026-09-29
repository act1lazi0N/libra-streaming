# Media: First-slice contracts and state model (Milestone 01)

This is a contract checkpoint, not a running upload pipeline. The baseline is
`features/media` at `31819e76180cfcaae822e865297993c68f13ff2c` with a clean
working tree before M01. Media still has only its deny-by-default scaffold. Core
already has catalog candidate binding, explicit publication, Media state-event
consumption, the private binding-read route, and playback tickets. The new routes
are specified in [Core admin uploads](../contracts/http/core-media-uploads.v1.yaml)
and [Media private uploads](../contracts/http/media-internal-uploads.v1.yaml).
They remain unavailable until their implementation milestones.

## Capability and trust map

| Capability | Actor and authority | Contract | Owner and durable boundary |
| --- | --- | --- | --- |
| Reserve upload and candidate | Current ADMIN cookie plus CSRF; same creator owns later upload operations | Core `POST /v1/admin/catalog/{contentId}/uploads` | Core inserts intent and binding and increments catalog version in one PostgreSQL transaction, then calls Media after commit |
| Ensure upload session | Core's dedicated signed service JWT, `core.media.uploads:write` | Media `PUT /internal/v1/uploads/{uploadId}` | Media verifies Core's exact candidate via existing `GET /internal/v1/media/bindings/{id}` and inserts session plus asset in one Media transaction |
| Issue direct PUT URL | Current creator ADMIN cookie plus CSRF, then Core service write scope | Core/Media `POST .../upload-url` | Media signs the existing server-generated staging key; no durable state change or session extension |
| Accept completion | Current creator ADMIN cookie plus CSRF, then Core service write scope | Core/Media `POST .../complete` | Media checks staging exists, then atomically marks session SUBMITTED, asset QUEUED and inserts one job; no network I/O in that transaction |
| Read status | Current creator ADMIN cookie, then Core service read scope | Core/Media `GET .../uploads/{uploadId}` | Media owns processing status; Core verifies its intent and does not synthesize Media success on failure |
| Report readiness | Media Kafka producer with asset ID key | Existing `media-asset-state.v1.schema.json` | Media transitions asset and writes outbox in one local transaction; Core independently consumes the event |
| Publish and play | Current ADMIN for publication; admitted viewer for playback | Existing catalog and playback contracts | Core publishes the exact READY candidate explicitly and later enforces entitlement at playback admission |

The Core-to-Media service key is distinct from the existing Media-to-Core
binding-read key and from Core identity/playback keys. Its JWT has RS256, a pinned
`kid` and public key, issuer `libra-core-services`, subject `libra-core`, sole
audience `libra-media-internal`, purpose `service-access`, a bounded `jti`, required
`iat`/`exp`, zero leeway and at most 60 seconds lifetime. Media's internal chain
accepts only this bearer identity with the operation's scope. Nginx must block
`/api/media/internal/` before these routes are enabled. A browser cookie, playback
cookie or signed PUT URL has no internal-control authority.

## Identity, idempotency and timing

Create accepts a client-generated UUID `requestId`, the current catalog
`expectedVersion`, declared byte length (1 through 268435456), and a lowercase
64-character SHA-256 hex digest. It applies only to draft MOVIE or EPISODE content
without an active asset or another candidate reserved by this flow. Core checks a
current ADMIN session and serializes on the catalog row. The first successful
transaction creates stable `uploadId`, `bindingId`, `assetId`, and `assetVersion=1`;
the candidate remains PENDING. It increments catalog version once. The unique
idempotency scope is `(creatorAccountId, requestId)` across Core. An identical retry
by that creator returns the original intent and retries provisioning, even if the
catalog version has since advanced, provided the reserved binding is still the
current candidate. A retired/replaced candidate gives `409 UPLOAD_STATE_CONFLICT`
and cannot be provisioned or receive another URL. A changed body or content with that key gives
`409 IDEMPOTENCY_CONFLICT`; a new key with stale version gives
`409 VERSION_CONFLICT`. Another administrator cannot address the creator's upload
and receives `404 NOT_FOUND`.

`catalogVersionAtReservation` is the version after that one increment. It is a
historical value, not a fresh version token for publication; an administrator
reads current catalog state before a later publish command.

Core commits the intent before calling Media. A Media timeout or outage yields
`503 MEDIA_UNAVAILABLE` rather than a successful create response. The creator
repeats the identical create request to provision the same identities. Core never
opens a PostgreSQL transaction across HTTP or S3. Media ensure is idempotent on
`uploadId` and the entire immutable request; a mismatch is a conflict. Media
cross-checks the exact binding/content/asset/version against Core before inserting.
If that lookup is unavailable, Media returns `503 CORE_UNAVAILABLE`, not an
unverified session. A confirmed Core create returns 201 (200 on identical retry).

The upload session expires one hour after its original Core creation, in UTC.
Media's signed staging PUT URL lasts at most 15 minutes and never later than the
session expiry. Reissuing it leaves the original expiry unchanged. The browser PUT
uses exactly `Content-Type: video/mp4`; local storage CORS permits only the configured
browser origin, method PUT, and required header. The URL grants no GET, LIST,
completion or playback permission. It can overwrite staging until expiry. Neither
the URL nor its signature appears in status, logs or durable intent records.

The accepted source is one MP4 clip of at most 256 MiB and 10 minutes. The
proposed first-slice probe policy accepts one H.264 SDR video stream at most
1080p/30 fps, with optional AAC audio; corrupt, oversized, overlong or unsupported
input fails permanently. Processing produces one H.264 HLS rendition fitting
within 1280 x 720 without upscaling; AAC is retained when present, and silent
input remains silent. Output uses VOD playlists and MPEG-TS segments.
Three total attempts are allowed only for transient processing failures. These
format rules are design constraints for later pipeline milestones, not a claim
that Media currently probes or transcodes files.

Completion checks object existence before the Media transaction; this is only a
precondition, not a byte-integrity guarantee. The worker later verifies the actual
size and SHA-256 and freezes a separate immutable source. A missing staging object
returns `409 SOURCE_MISSING`, retaining OPEN. First accepted completion returns
202 with one job. Duplicate completion after SUBMITTED returns 202 for that same
job, including after the former session expiry. OPEN past expiry becomes EXPIRED
and returns `410 UPLOAD_EXPIRED`; no new job is inserted. Failed provisioning,
source rejection, queued work and retry exhaustion never claim READY.

## Separate state machines

| Owner and state | Legal transition | Durable write and externally visible meaning |
| --- | --- | --- |
| Media upload `OPEN` | `OPEN -> SUBMITTED` after staging existence; `OPEN -> EXPIRED` at original deadline | Completion transaction creates exactly one job; expiry atomically marks asset FAILED with `UPLOAD_EXPIRED` and its outbox event; EXPIRED forbids new URLs and completion |
| Media upload `SUBMITTED` | Terminal for upload acceptance | Duplicate completion returns the original job, regardless of asset progress |
| Media asset `UPLOADING` | `UPLOADING -> QUEUED` with accepted completion, or `UPLOADING -> FAILED` on expiry | Core candidate remains PENDING while OPEN/QUEUED; expiry reports FAILED, never READY |
| Media asset `QUEUED` | `QUEUED -> PROCESSING` on valid worker claim; `QUEUED -> FAILED` on permanent source rejection | PROCESSING event is emitted only on actual processing, never merely on queueing |
| Media asset `PROCESSING` | `PROCESSING -> QUEUED` for bounded transient retry; `PROCESSING -> READY` after complete readable HLS; `PROCESSING -> FAILED` on permanent error or three total attempts | READY/FAILED and corresponding outbox record commit together; no event state outside existing PROCESSING/READY/FAILED enum |
| Media job stage | `QUEUED -> CLAIMED -> SOURCE_SELECTED -> TRANSCODING -> FINALIZING -> SUCCEEDED`; a transient stage may return to QUEUED; terminal failure is FAILED_PERMANENT or EXHAUSTED | Attempt count is 0 before claim, at most 3; a lease/attempt token fences source selection and finalization. Selected source is never reset to staging on retry |
| Media outbox | `PENDING -> LEASED -> SENT`; expired LEASED returns to claimable PENDING | Asset transition and PENDING event insert share one transaction; SENT follows Kafka acknowledgement, so replay may duplicate the same event ID |
| Core candidate projection | `PENDING -> PROCESSING/READY/FAILED` by valid versioned Media event | Existing consumer checks exact tuple and rejects duplicate event IDs and stale aggregate versions |
| Core publication | Draft/unpublished to published only through explicit ADMIN publication with current catalog version and READY candidate | READY cannot publish. Playback admission rechecks publication and entitlement and binds the selected asset version |

Job stage and lease are internal fields, not public upload or asset states. An
expired worker cannot commit a selected source or READY after another attempt has
claimed the job. An object write and a database commit are separate operations;
orphaned attempt objects are retained for scoped cleanup. Source and HLS objects
remain private. A successful FFmpeg exit alone cannot produce READY.

## Wire examples and errors

The examples below are synthetic. UUIDs identify one immutable tuple, and the
digest is deliberately a fixture value. The corresponding executable schema
examples live in `infra/ci/test_media_contracts.py`.

```http
POST /api/core/v1/admin/catalog/11111111-1111-4111-8111-111111111111/uploads
Content-Type: application/json
X-CSRF-TOKEN: <masked-token>
Cookie: <current-admin-and-csrf-cookies>

{"requestId":"22222222-2222-4222-8222-222222222222","expectedVersion":4,"byteLength":1024,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
```

```http
HTTP/1.1 201 Created
Cache-Control: no-store
Content-Type: application/json

{"uploadId":"33333333-3333-4333-8333-333333333333","contentId":"11111111-1111-4111-8111-111111111111","bindingId":"44444444-4444-4444-8444-444444444444","assetId":"55555555-5555-4555-8555-555555555555","assetVersion":1,"catalogVersionAtReservation":5,"uploadState":"OPEN","assetState":"UPLOADING","jobId":null,"attemptCount":0,"failureCode":null,"expiresAt":"2026-09-24T09:00:00Z"}
```

The internal ensure request carries the same requestId, tuple, length, digest and
expiry. A URL response contains `method: PUT`, a 15-minute-or-shorter `expiresAt`,
and `requiredHeaders` containing `Content-Type: video/mp4` and the base64
`x-amz-checksum-sha256` value for the declared digest. After direct PUT, complete
returns `202` with `uploadState: SUBMITTED`, `assetState: QUEUED`, the stable
`jobId`, and `attemptCount: 0`. GET may later return PROCESSING, FAILED or READY.
The only readiness wire event is the existing `MediaAssetStateChanged` event with
the exact tuple and a monotonic aggregate version. Media uses the immutable
`requestId` as that flow's event correlation ID; each transition still has its
own event ID.

| Condition | Result | Protected side effect |
| --- | --- | --- |
| Missing/revoked ADMIN session or CSRF | 401/403 sanitized Problem | No intent, URL or job |
| Stale catalog version or existing active/candidate asset | 409 VERSION_CONFLICT or UPLOAD_NOT_ALLOWED | No new binding or intent |
| Same requestId with changed body/content | 409 IDEMPOTENCY_CONFLICT | Original intent unchanged |
| Media unavailable after Core reservation | 503 MEDIA_UNAVAILABLE | Same intent retained for identical retry; no claimed provisioning |
| Invalid or wrong-scope service JWT | 401/403 sanitized Problem | No Media session or job |
| Unknown/mismatched Core binding | 404 NOT_FOUND or 409 UPLOAD_STATE_CONFLICT | No Media session |
| Missing staging object | 409 SOURCE_MISSING | OPEN retained; no job |
| OPEN upload expired | 410 UPLOAD_EXPIRED | No new URL or job |
| Repeated complete after SUBMITTED | 202 same job identity | No duplicate job |
| Wrong bytes or corrupt/unsupported input | Status FAILED with sanitized failureCode | Never READY; no publication |
| Three transient attempts exhausted | Status FAILED/RETRY_EXHAUSTED | No READY; no automatic manual retry |

Problem bodies follow Core's existing Problem Details shape: `type`, `title`,
`status`, `detail`, `code`, `correlationId`. For example an expired upload has
`{"type":"about:blank","title":"Gone","status":410,"detail":"Gone.","code":"UPLOAD_EXPIRED","correlationId":"66666666-6666-4666-8666-666666666666"}`.
No error includes storage paths, signed URLs, tokens or untrusted input text.
The first slice does not support replacement, multipart upload, manual retry,
product UI or active-asset deletion.
