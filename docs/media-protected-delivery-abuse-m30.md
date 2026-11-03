# Media: Protected delivery abuse hardening (Milestone 30)

Verification date: 2026-10-16.
Status: VERIFIED_LOCAL (Media's embedded Tomcat over real sockets with real PostgreSQL, SeaweedFS and ffmpeg; the
repository Nginx configuration in front of a stand-in; and the whole Compose slice with Core's own signer and its
real five-minute ticket, played through Nginx by raw sockets and ffmpeg; no browser, no TLS, Docker Desktop on one
Windows workstation).

## Scope

M30 hardens what [M29](media-protected-delivery-ingress-m29.md) left open in its Limits: abuse of the public stream
paths beyond those tested, a ticket expiring in the middle of a presentation, repeated requests, cache isolation,
alternate routes to Media, and failures before the first byte. It changes
[`infra/nginx/nginx.conf`](../infra/nginx/nginx.conf) (Media's public surface) and
[`StreamController`](../services/media/src/main/java/com/libra/streaming/media/delivery/api/StreamController.java)
(failures before the first byte), moves [`media-streams.v1.yaml`](../contracts/http/media-streams.v1.yaml) to 1.3.0,
and adds [`check-media-m30.ps1`](../infra/smoke/check-media-m30.ps1). There is no migration, event or Core change.

## Gate

Every retrieval path enforces the same ticket and resource boundary: beneath `/api/media/` only Media's health and the
exact stream route reach Media, and every spelling that does reaches it as the canonical route under the ingress
limits; a ticket that expires mid-presentation stops every further request until Core renews it; no cache, validator
or range lets an authorized response answer another request or come back in part; missing bytes are a controlled
contract problem, never another object, an error page or a redirect; and players that hang up give their streams back.

## Findings

Each was reproduced before its repair: H1 and H2 on HEAD (59566cf), H3 on the tree with H2's repair.

| Finding | Repair | Evidence |
| --- | --- | --- |
| **H1. The generic `/api/media/` route forwarded every other target to Media.** A spelling Nginx does not normalise into the exact stream location reached Media around that location's limits and refusals: `/api/media/v1/streams;x=1/{session}/master.m3u8` arrived as `/v1/streams;x=1/{session}/master.m3u8`, a path Tomcat strips back to the stream route. So did `/api/media/actuator/info`, `/actuator/env`, `/actuator/health/db`, `/V1/...`, `/v1/Streams/...`, `/v2/...`, `/error` and `/api/media/` itself, and `/api/media` was a 301 redirect. Media refused all of them (Spring's firewall and its deny-all chain), so no bytes leaked, but that refusal was the only barrier, and every endpoint Media might add was public. | `/api/media/` and `/api/media` are Nginx's 404 problem; only `^/api/media/actuator/health(/liveness\|/readiness)?$` and the exact stream location forward to Media. | `tmp/m30-repro-ingress-matrix.log` (HEAD config behind the stand-in, which now reports the URI it received). |
| **H2. A failure before the first byte became an empty 403.** When storage failed at the first read, or the deadline (transfer timeout or ticket `exp`) passed while storage was opening the object, the controller threw its abort exception while the response was not yet committed. Tomcat then dispatched to `/error`, which Media's default chain denies: the player received `403` with an empty body, `Content-Length: 0` and the stream's headers. hls.js never retries a 4xx, so a transient storage error or a ticket a few hundred milliseconds from expiry ended playback, and the status claimed a denial. | Before the commit the controller resets the prepared response and answers the contract's `503 MEDIA_UNAVAILABLE` problem without `Retry-After`; after it, it still aborts the connection. | `tmp/m30-repro-media-1.log`: the new real-socket suite on HEAD, 2 of 7 failing with `expected 503 but was 403`. |
| **H3. Any other failure before the first byte was the same empty 403** (found by the review of H2's repair). H2 mapped the two failures the controller expected; an exception nothing maps (a defect, an unmapped library failure) still went to the denied `/error` dispatch. | `StreamProblems` answers any other runtime exception raised before the commit with the same 503 problem, logging only its type (messages may carry keys or SQL); after the commit, and for the deliberate abort, it rethrows so the connection is still cut. | `tmp/m30-repro-media-2-unexpected.log`: the new suite's unexpected-failure test on the H2-repaired tree, `expected 503 but was 403`. |

## What changed

### Ingress (`infra/nginx/nginx.conf`)

| Target beneath `/api/media/` | Behaviour |
| --- | --- |
| `actuator/health`, `actuator/health/liveness`, `actuator/health/readiness` | Forwarded to Media (the smokes' readiness probes use them), as a GET without a body only: another method is Nginx's `403 ACCESS_DENIED` problem and an announced body its `400 INVALID_REQUEST`, as on the stream route. |
| The exact stream route (M29) | Unchanged: GET only, no body, per-session and per-address limits, no cache, no disk. |
| Anything else, and `/api/media` | `404 NOT_FOUND` problem written by Nginx with `no-store`; Media is never called. |

Spellings Nginx itself normalises before matching (percent-encoded unreserved characters such as `%6D` or `%2E`, dot
segments, repeated slashes, `%2F`, an absolute-form request target) reach Media only as the canonical route, so the
stream location's limits and refusals apply to them. The M07 smoke's control probe moved to `actuator/health`.

### Media (`StreamController`)

`Unavailable` or `Expired` raised during the copy is answered by `response.isCommitted()`: false (nothing, or only bytes
still in the container's buffer, has been sent) resets the response, dropping the prepared `200`, its
`Content-Length` and any buffered byte, and the existing problem handler writes `503 MEDIA_UNAVAILABLE`; true keeps
M28's abort. Any other runtime exception before the commit reaches a new fallback in `StreamProblems` with the same
result (H3). The stream and its admission permit are released on every path. A deadline that is the ticket's `exp` is
also the 503, not a 401: the contract already names it, the player's retry then receives the 401 for the expired
ticket, and the response does not have to tell which deadline passed.

### Contract (`media-streams.v1.yaml` 1.3.0)

States the forwarded surface, that conditional and `Range` headers are ignored and no `ETag`, `Last-Modified` or
`Accept-Ranges` is sent, and that a failure or deadline before the first byte is the 503 without `Retry-After`.

## Checks without a finding

| Check | Where | Result |
| --- | --- | --- |
| 24 spellings sent byte for byte to Media's own Tomcat (path parameters, `//`, dot segments, `%2e`, `%2F`, `%5C`, `%255c`, `%00`, `%20`, a trailing slash or dot, other cases, absolute form) | `ProtectedStreamServerIntegrationTest` | Only `%6Daster.m3u8` and the absolute form answer, with that session's own master; the rest are 400 (`%2F`, `%5C`, `%00`), 403 (Spring Security) or 404, none with a byte of HLS. |
| A ticket expiring between the playlists and the segments | Suite (a ticket with 8 s left); **smoke with the five-minute ticket Core's signer issued** | Every request after `exp` is `401 AUTHENTICATION_REQUIRED`, through Nginx too and for a normalised spelling. Core's playback session expires with its ticket, so renewal after `exp` is `409 PLAYBACK_EXPIRED` as Core's playback contract states, and a new admission continues every object with the same bytes; the expired ticket opens neither session and the new ticket only its own. |
| Caching and validators | Suite and smoke | `Cache-Control: no-store`; no `ETag`, `Last-Modified`, `Accept-Ranges`, `Content-Range`, `Age`, `Expires` or `Vary`. `If-None-Match`, `If-Modified-Since`, `Range`, `If-Range` and `Cache-Control: only-if-cached` return the whole object with the ticket and `401` without it; an authorized response never answers the next anonymous request. |
| A missing object of a READY generation | Suite (segment deleted from SeaweedFS) | `404 NOT_FOUND` problem; the neighbouring segments are unaffected. |
| Repeated GETs and HEAD | Smoke | Twenty GETs of the largest segment are byte-identical; HEAD is Nginx's `403`. |
| Players that hang up mid-body | Suite (four streams held, the fifth refused with `503` and `Retry-After: 1`, then reset); smoke (eight connections reset after the status line) | Four concurrent streams of the session are admitted again, at Media and at Nginx. |

## Findings of the review

A code-reviewer agent reviewed the first full draft (H1 and H2 repaired). Its behavioural finding was reproduced
before the repair (H3 above); the others were applied as follows.

| Finding | Disposition |
| --- | --- |
| R1. Root cause of H2: the denied error dispatch turns any unmapped failure before the commit into an empty 403. | Repaired as H3. Filter-level failures keep it (Limits). |
| R2. The newly allow-listed public health took any method and a body, without a rate limit, and each readiness call checks PostgreSQL and storage. | Health is a bodiless GET only (mutant N4); the rate limit is a limit. |
| R3. The smoke slept a fixed second past the cookie's expiry, which `http.cookiejar` rounds on the host clock while Core computes it on the container's: clock skew could wake it before `exp`. | The smoke polls Media for the 401, bounded at 5 s after `exp`. |
| R4. The suite's expiry windows were 2-3 s of wall clock, tight for a first request on a busy Windows host. | Eight seconds. |
| R5. The hang-up test re-polled sequentially, proving only one free stream. | It holds four streams at once again. |
| R6. The contract omitted the encoded slash Nginx normalises; `core-playback.md` still described the generic `/api/media/` mapping; `-Milestone 29` deleted the M30 path evidence; one smoke byte comparison was always true. | Fixed. |
| Kept | The expected 503s are not logged (only H3's unexpected type is); the suite repeats `SourceStorageFixture`'s storage properties because its `@SpringBootTest` replaces the fixture's; a 401 instead of the 503 for an `exp` deadline (see "Media"). |

The first full smoke (`tmp/m30-smoke-1.log`) failed at renewal after expiry with `409`: the smoke, not the product,
was wrong. Core's playback session expires with its ticket and the contract requires renewal before expiry; the check
now expects `PLAYBACK_EXPIRED` and continues with a new admission.

## Evidence

**Media's embedded Tomcat** (`ProtectedStreamServerIntegrationTest`, 8 tests): requests written byte for byte over a
socket to a `RANDOM_PORT` context with real PostgreSQL, SeaweedFS and ffmpeg-produced READY generations. Storage
faults (failure at the first read, after one buffered byte, a slow open, a held stream, an unexpected exception) wrap
the real `S3StreamObjects` as a `@Primary` bean; tickets are signed as Core signs them. Every problem is checked as
framed by its own length, `application/problem+json`, `no-store`, without `Set-Cookie`, key, playlist or exception
text. `StreamControllerTest` adds three tests (failure with nothing or one buffered byte sent, a deadline passing during
the open, an unexpected failure before and after the commit); two existing tests now flush before failing, since their
point is a failure after the commit.

**Ingress** (`check-media-m30.ps1` phase 1): the M29 limit checks and `ingress_m30_paths.py` against the repository
`nginx.conf` and the stand-in, which now reports the URI it received (`X-Upstream-Uri`) and answers at full speed when
asked (`X-Stub-Fast`). Thirteen targets reach the stand-in only as health or the canonical route; twenty-nine others
beneath `/api/media/` are Nginx's 404 problem; health refuses POST, DELETE and HEAD (403) and an announced body (400).

**The slice** (phase 2): the whole M29 flow, then `media_m30_http.py` with the viewer's real ticket: six spellings
Nginx normalises serve the canonical bytes and nineteen other targets are Nginx's 404 problem; caching and validator
checks; twenty repeated GETs and HEAD; eight players reset mid-segment, after which four concurrent streams are
served; then the wait for Core's ticket to expire, the 401s, `PLAYBACK_EXPIRED` and the new admission.

Mutants, each run against the new checks and each killed: HEAD's controller (J1, two unit tests), no `reset()` (J2, the
buffered byte reaches the body), no fallback handler (J3); in `nginx.conf` a health location for all of `actuator/`
(N1), no `= /api/media` (N2), the generic `/api/media/` proxy restored (N3, = HEAD) and no method or body rule on health
(N4) (`tmp/m30-mutation-*.log`; N1-N3 also fail the contract test). J4, the fallback without its rethrow guard,
survives as an equivalent mutant: `reset()` itself refuses a committed response, so the original exception still
propagates and the connection is cut; the guard is kept to state the intent.

## Verification

| Evidence | Dependency | Checks |
| --- | --- | --- |
| `ProtectedStreamServerIntegrationTest` | Media's Tomcat over sockets, PostgreSQL 18.6, SeaweedFS 4.46, ffmpeg | 8 (new) |
| `StreamControllerTest` | In-memory ports | 10 (3 new) |
| `check-media-m30.ps1` phase 1 | Repository `nginx.conf` in Nginx 1.29.3, stand-in for Media | 7 (M29) + 3 (M30) |
| `check-media-m30.ps1` phase 2 | **Real Compose stack**: packaged Core and Media, PostgreSQL, Kafka, Mailpit, SeaweedFS, Nginx, ffmpeg | 10 (M29) + 5 (M30) |
| `test_media_stream_contract.py` | Contracts and `nginx.conf` only | 7 (1 new) |

A ci-gate agent ran every `.github/workflows/core-ci.yml` stage on the final tree (`tmp/ci-gate/m30-*.log`): Java 21
offline root `clean verify` passed all three backends (BUILD SUCCESS, 14m19s); the report gate confirmed 85 suites
and 610 tests with zero failures, errors or skips: Core 187 (44 Surefire, 143 Failsafe), Media 421 (226 Surefire, 195
Failsafe), recommendation and analytics 2. The Python gate tests passed (15), `check_contracts.py` passed 22 checks,
`docker compose config --quiet` with `.env.example` and generated storage keys passed with and without `--profile
full`, the packaged Core smoke (which runs the same `nginx.conf`) passed, and `git diff --check` was clean.
The final smoke ran on the final tree with the gate's JARs (`tmp/m30-smoke-3.log`: phase 1 7 + 3 PASS, phase 2 10 + 5
PASS, `INGRESS_M30_PATHS_PASS`, `MEDIA_M30_SMOKE_PASS`); afterwards no container, volume, network or image of its
projects remained.
Before the review, the second full smoke had passed on the draft (`tmp/m30-smoke-2.log`). All of it ran on the
uncommitted tree with offline Maven and Docker Desktop on Windows, not on CI's runner; CI does not run
`check-media-m30.ps1`.

Work was divided among agents: a code-reviewer agent reviewed the first full draft (R1-R6 above), and a ci-gate agent
ran the gate.

## Limits

- Media's own answers to spellings it refuses, and to anything that fails in a filter before Spring MVC (empty 403 or
  400 from Spring Security and Tomcat, because the error dispatch is denied), are not contract problems; after H1 they
  are reachable only from inside the Compose network.
- A body already in flight when the ticket expires is cut only when Media next writes: bytes already in socket and
  Nginx buffers (about 0.5 MiB at Nginx) still reach the player. Downloaded bytes cannot be recalled.
- A 404 for a missing object of a READY generation makes a player give up; Media does not distinguish a deleted
  object from a name the generation never had.
- `/api/core/` still forwards every path to Core, which owns its own surface; Core is outside M30.
- Media's public health is not rate-limited: each readiness request checks PostgreSQL and storage. Nginx admits only a
  bodiless GET; a `limit_req` or a health cache belongs with the deployment's TLS ingress.
- No browser, no TLS, one Nginx; the ingress limits are per Nginx instance. Range, multiple renditions, key rotation,
  M31 cleanup and upload-expiry FAILED events remain open.
