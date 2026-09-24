# Core: Playback and history (Milestone 05)

Core now admits and renews playback sessions, signs Media tickets, accepts ordered
progress, maintains per-profile resume/history, selects the next eligible episode,
and appends accepted viewing facts to the transactional outbox. This delivery is
Core backend and integration contracts only. Media HLS delivery, private storage,
the browser player and Analytics consumers are separate implementations.

Contracts: [OpenAPI](../contracts/http/core-playback.v1.yaml) and
[viewing event schema](../contracts/events/core-playback-progress.v1.schema.json).
The external Core prefix is `/api/core`; Nginx strips it before forwarding to Core.

## Admission, renewal and lifecycle

`POST /v1/playback/sessions` accepts only `profileId` and `contentId`. The server
checks live account/authentication status, verified email, profile ownership,
published ancestry, exact READY active binding and the published access tier.
Administrators follow the same policy. No simultaneous-stream quota is introduced.

Sessions bind account, profile, authentication session, content, published revision,
binding, asset version and duration. Another login for the same account cannot
renew or send progress for that session. A fresh admission starts PAUSED and returns
a resume position, duration, 15-second heartbeat cadence and a credential-free
manifest path. There is no admission idempotency key: each successful admission
creates a new session and becomes the latest-opened session for resume purposes.

The authentication account row is locked first, consistently with profile deletion,
history clearing, purchases and identity revocation. Admission/renewal/progress lock
the content and immutable ancestors in stable UUID order before evaluating the
central entitlement policy. Publication and Media projection writers use the same
content locks. A denial or signing/outbox failure rolls back the feature transaction.
Session expiry is checked again after acquiring catalog locks, so lock contention
cannot revive an expired session or accept late progress.

`POST /v1/playback/sessions/{id}/renew` must occur before expiry and rechecks rights.
Renewal extends from server time, not the old expiry. Session and ticket expiry are
the same: at most 300 seconds, capped by authentication-session expiry and Premium
expiry where applicable, rounded down to whole seconds. A remaining lifetime shorter
than a representable second returns `PLAYBACK_EXPIRING` instead of an expired JWT.
Expired/ended sessions require new admission. Missing heartbeats never extend expiry.

A processing/failed candidate replacement does not change the active session asset.
After a replacement is explicitly published, renewal/progress for the old binding
return `MEDIA_BINDING_CHANGED`; clients must admit a new session. Active media failure,
hidden ancestors, lost verification or Premium, logout and suspension also stop new
admission/renewal. Expiry is evaluated on requests; no background expiry job is needed
to enforce it. Historical session rows currently have no automatic retention cleanup.

Profile deletion cascades removal of its sessions/history and emits the existing
ProfileDeleted lifecycle event. History clear marks relevant sessions ENDED and
removes resume rows under the same account lock. Already accepted viewing events and
session qualification facts are retained when history is cleared. Clearing resume is
not an Analytics erasure command. A later new session can create new history.

## Media ticket and delivery contract

Core signs compact JWS JWTs with RS256 using a dedicated RSA key. Identity HS256 keys
are never shared with Media. The signature uses the configured `kid`; the public JWK
set is at `/v1/playback/jwks` (external `/api/core/v1/playback/jwks`). It contains no
private material. Use a trusted, configured JWKS endpoint or provisioned public key;
never follow a token-supplied key URL. Production key distribution must use TLS.

| Claim/header | Required meaning |
| --- | --- |
| `alg`, `kid`, `typ` | RS256, known configured key ID, JWT |
| `iss` | `libra-core-playback` |
| `aud` | Exactly `libra-media` |
| `purpose` | `media-playback`; never accept an identity access token |
| `sub` | Playback session UUID matching the requested stream path |
| `jti` | Fresh random UUID for this issued ticket |
| `iat`, `exp` | Integer UTC seconds; issued no later than now, exp strictly after now and iat, exp minus iat at most 300 seconds |
| `contentId`, `bindingId`, `assetId`, `assetVersion` | Exact authorized immutable media selection; version is positive |

Core sets `__Secure-LIBRA_PLAYBACK` as HttpOnly, Secure, SameSite=Lax, with no Domain,
and Path `/api/media/v1/streams/{sessionId}/`. Explicit loopback development uses
`LIBRA_PLAYBACK` without Secure, following the existing identity cookie setting.
The browser needs Core and Media behind the same ingress origin. The returned path
is `/api/media/v1/streams/{sessionId}/master.m3u8`; Nginx's existing `/api/media/`
mapping forwards it as `/v1/streams/{sessionId}/master.m3u8` to Media.

Media must verify signature/algorithm/key ID, issuer, audience, purpose, required
claims, strict expiry (including equality), maximum lifetime, session path and exact
asset binding on **every** master playlist, rendition playlist and segment request.
Reject missing, duplicated, malformed or invalid ticket cookies. Restrict all
resource resolution to that authorized asset generation; normalize and reject path
traversal. Playlists must use relative, credential-free resource paths beneath the
same session prefix. Private storage keys are resolved by Media, never by browser
input. No public storage fallback or shared cache may bypass ticket checks.

No production Media verifier or HLS routes are delivered here. The test verifier is
an independent contract fixture for signed tokens, not proof of segment delivery.
Tickets are bearer credentials: session end, history clear, deletion and revocation
cannot revoke an already copied ticket immediately. Delivery must stop by ticket
expiry, within five minutes (sooner when capped). End clears the current browser's
session cookie; downloaded bytes cannot be recalled. Media must retain retired public
keys for outstanding tickets during a coordinated rotation; this version exposes
one configured key, without automated overlapping-key rotation.

## Progress, resume and viewing events

Send `sequence`, `positionMs`, `playedMs`, and the resulting state to
`POST /v1/playback/sessions/{id}/progress`. All four fields are required. States are
PLAYING, PAUSED, BUFFERING, SEEKING and ENDED. `/end` uses the same body and accepts
only ENDED. Sequence is a positive safe JavaScript integer; gaps are allowed.
Duplicate/stale sequences return `accepted=false` with current persisted values,
without changing position/time or appending another event. A replayed final request
is safe. New sequences after ENDED or expiry fail.

Position must lie within the session's server-known duration. It can move backward
or jump forward. Watched time is separate: the server accepts the minimum of reported
playedMs and elapsed server time, only when the previous state was PLAYING. A SEEKING
observation earns zero; paused/buffering intervals earn zero. Reports are bounded to
30,000 ms; a server gap over 30 seconds earns zero, even if the client claims playback.
Renewal never resets the progress timer. The policy counts wall-clock playing time
without a playback-rate multiplier. Clients exclude paused/buffering/seek time from
their report. Browser engagement remains best effort, not proof of human attention.

The latest-opened session (serialized by the account lock and recorded with a server
order) owns shared resume for its profile/content. Older sessions may record their
own accepted engagement but cannot overwrite this resume. At admission an existing
incomplete position is clamped to the new duration; completed content resumes at zero.
Completion means the latest position reaches 95% of duration. Seeking to the end may
complete resume but does not qualify a view. Qualification requires at least 30,000 ms
of cumulative accepted playing time **within one session**.

History and continue-watching are paginated (default 20, max 100, max offset 10,000),
owned by the explicit profile, and filtered by current published ancestry. Continue
watching requires a positive position and incomplete status. A listed history item
is not a playback grant. Next episode orders by season then episode number, skipping
hidden, unavailable and unentitled units. It returns `contentId: null` at the end or
for movies and checks eligibility of the current content first. Admission still
rechecks any returned candidate.

Accepted progress updates session, shared history and `PlaybackProgressAccepted`
outbox record in one PostgreSQL transaction. Topic/key are `core.playback.v1` and
session UUID. Envelope aggregate ID is session UUID; aggregate version equals the
accepted sequence. The payload contains internal IDs and accepted increment plus
cumulative total, never email, auth-session IDs, cookies, tickets or storage paths.
Consumers must deduplicate event IDs and order by session sequence; qualification is
counted once per session, not on every `qualified=true` event. Cumulative duration
supports convergence after reordered delivery. ProfileDeleted governs downstream
profile cleanup, including delayed playback events for removed profiles.

The existing outbox append mechanism is used. [Milestone 7](core-integration-operations.md)
adds background publication and lease/retry recovery. Analytics consumption remains
separate service work. No Kafka/Analytics call participates in accepting history;
end-to-end exactly-once processing is not claimed.

## Configuration

Flyway V5 adds `playback_sessions` and `watch_history`; it does not rewrite earlier
migrations. Core now also requires these settings (Compose forwards them):

| Variable | Format |
| --- | --- |
| `CORE_PLAYBACK_PRIVATE_KEY` | Base64 PKCS#8 DER RSA private key, at least 2048 bits |
| `CORE_PLAYBACK_PUBLIC_KEY` | Base64 X.509 SubjectPublicKeyInfo DER for the matching public key |
| `CORE_PLAYBACK_KEY_ID` | 1–64 letters, digits, underscore or hyphen |

Startup fails for absent/malformed/weak/mismatched keys. Keys must remain stable
across restarts and stay outside Git/logs. Tests generate isolated random fixtures.
For local PowerShell 7, generate a pair directly into the current process environment:

```powershell
$playbackRsa = [System.Security.Cryptography.RSA]::Create(3072)
try {
    $env:CORE_PLAYBACK_PRIVATE_KEY = [Convert]::ToBase64String($playbackRsa.ExportPkcs8PrivateKey())
    $env:CORE_PLAYBACK_PUBLIC_KEY = [Convert]::ToBase64String($playbackRsa.ExportSubjectPublicKeyInfo())
    $env:CORE_PLAYBACK_KEY_ID = 'local-playback-1'
} finally {
    $playbackRsa.Dispose()
}
```

This example does not print keys or persist them. For repeatable restarts, provision
the same values through your secret configuration. Never generate replacement keys
on each application startup. Maven does not automatically load `.env`.

## Verification

`PlaybackIntegrationTest` uses real PostgreSQL 18, Flyway from empty/V4 schemas,
production application services, a controlled clock and real HTTP/Spring Security.
It covers ownership, CSRF, email/Premium/auth-session expiry, revocation/suspension,
hidden ancestry, asset replacement/failure, duplicate and concurrent progress,
expiry while waiting for publication locks,
seek/pause/buffer/gaps, latest-session resume, completion/qualification, history
clear races, profile deletion, outbox rollback and unavailable broker behavior.

`PlaybackTicketContractTest` verifies RS256 signatures with only the public key,
tampering, wrong key/issuer/audience/purpose/key ID/session/binding/version,
strict expiry, malformed configuration and cookie scoping. These are local Core and
Media contract checks, not FFmpeg/private-storage/HLS/browser playback evidence.

Local verification on 2026-09-18:

| Check | Result |
| --- | --- |
| Root `mvnw.cmd clean verify`, all three services | 102 tests, zero failures/errors/skips; 17 playback integration and 6 ticket contract tests included |
| Final focused V5 migration check after adding the history-session index | Passed; empty-schema application, V4-to-V5 upgrade and repeat migration |
| OpenAPI 3.1, event JSON Schemas and positive/negative event examples | Passed with local reference resolution |
| `docker compose -f infra/compose.yaml config --quiet` and `git diff --check` | Passed |

The full reactor used disposable PostgreSQL, Kafka and Mailpit containers; the final
index-only migration check used PostgreSQL. Compose runtime smoke, deployment and
full-stack streaming were not executed for this milestone.

Cryptographic integration uses the existing Spring Security Nimbus encoder/decoder
support; see [Spring Security JWT reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
