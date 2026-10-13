# Media: Protected delivery through the ingress (Milestone 29)

Verification date: 2026-10-13.
Status: VERIFIED_LOCAL (the whole slice ran in the real Compose stack with the repository Nginx configuration, the
packaged Core and Media JARs, Core's own playback signer, Media's worker and outbox, Kafka, PostgreSQL and SeaweedFS,
and ffmpeg played it through Nginx; no browser or web player, no TLS, and Docker Desktop on one Windows workstation).

## Scope

M29 closes the limits [M27](media-protected-delivery-m27.md) and [M28](media-protected-delivery-hardening-m28.md) left
at the ingress: Nginx serving the stream routes, limits for the stream location, and the Compose stack running with
Core's real signer. It changes [`infra/nginx/nginx.conf`](../infra/nginx/nginx.conf) (an exact stream location,
two connection limits, refusals and problems written by Nginx), two Compose defaults and `.env.example`, and moves
[`media-streams.v1.yaml`](../contracts/http/media-streams.v1.yaml) to 1.2.0. It adds the smoke
[`check-media-m29.ps1`](../infra/smoke/check-media-m29.ps1). There is no Java, migration or event change.

## Gate

Through the public ingress, an administrator's upload becomes READY by Media's own worker, reaches Core over Kafka,
is published, and a Premium viewer admitted by Core can retrieve and decode the complete HLS with nothing but the
ticket cookie Core set; requests without that ticket, with another session's ticket, or for any path outside the exact
route are refused; Nginx neither caches nor spools protected bytes to disk; and concurrent stream requests are bounded
at the ingress per playback session and per peer address with the retryable 503 the contract already defines, which
no unauthenticated request can hold.

## What changed

### Stream location (`infra/nginx/nginx.conf`)

| Rule | Behaviour |
| --- | --- |
| Exact route | `^/api/media/v1/streams/{lowercase UUID}/(master.m3u8\|rendition.m3u8\|segment-NNNNN.ts)$` is rewritten to `/v1/streams/...` and proxied to Media. The name alternation is the contract's vocabulary (a contract test compiles the Nginx regex and compares). |
| Anything else beneath `/api/media/v1/streams/` | A `404 NOT_FOUND` problem written by Nginx; Media is never called. Matching runs on Nginx's normalized URI, so `%2e%2e`, an extra segment, an upper-case session, `source.mp4` or `index.html` all land here. |
| A method other than GET | `403 ACCESS_DENIED` problem from Nginx (Media refuses them with the same code). |
| A request announcing a body (`Content-Length` or `Transfer-Encoding`) | `400 INVALID_REQUEST` problem from Nginx. Both refusals are `if`/`return` in the rewrite phase, which runs before `limit_conn` counts the request. |
| Per playback session | `limit_conn` 4, keyed by the session captured in the location match (set before the rewrite changes `$uri`). It holds across every Media instance behind this Nginx, closing the per-instance gap in M28's Limits. |
| Per peer address | `limit_conn` 16 on `$binary_remote_addr`, the address Nginx sees: every client behind one proxy or NAT shares it. No `real_ip` is configured because nothing sits in front of this Nginx yet. |
| Nginx's own limit refusal | `503 MEDIA_UNAVAILABLE` with `Retry-After: 1`, the same shape as Media's limit refusal (hls.js retries a 5xx, not a 4xx). |
| Media unreachable or too slow (Nginx's 502/504) | `503 MEDIA_UNAVAILABLE` without `Retry-After`, as for a storage failure, never Nginx's HTML page. |
| Media's own answers | Pass through unchanged, including its 401/403/404 problems and its 503 with `Retry-After`: `proxy_intercept_errors` is off, so `error_page` only catches what Nginx itself generates. |
| Problems written by Nginx | `application/problem+json` regardless of the path's extension (`types { }`), `no-store`, the foundation Problem fields and a UUID `correlationId` derived from `$request_id`. Nginx keeps no access or error log, so that ID is not recorded anywhere. |
| Buffering | In memory only: `proxy_buffers 8 64k`, `proxy_max_temp_file_size 0`. Nginx never writes a protected segment to its disk; past ~0.5 MiB it reads Media at the player's pace, so Media's transfer deadline still bounds a slow player. There is no `proxy_cache` anywhere in the file. |
| Credentials | Cookies and `Authorization` are forwarded untouched, so Media's refusals (a bearer header, a repeated cookie) behave as at Media. Nginx authorizes nothing. |

### Compose and environment

| Change | Why |
| --- | --- |
| `MEDIA_WORKER_ENABLED: ${MEDIA_WORKER_ENABLED:-true}` in `infra/compose.yaml` | **Found while wiring the stack:** Compose never passed `MEDIA_WORKER_ENABLED`, and the application default is `false` (kept from M15, when no job handler existed). The full profile could accept and queue an upload but never probe, transcode or finalize it, so nothing could become READY or play. Rendering HEAD's Compose file for Media shows no worker variable; with the change, the smoke's upload becomes READY. |
| Nginx host port `127.0.0.1:8080:80` (was `8080:80`) | Every other published port is loopback-only; the ingress alone was reachable from the LAN. |
| `.env.example`: `MEDIA_WORKER_ENABLED=true`, `MEDIA_PLAYBACK_ENABLED=false` with a note | Compose only forwards listed variables; both switches were undocumented for local runs. |

Playback stays off by default (`MEDIA_PLAYBACK_ENABLED=false`): enabling it requires Core's playback key pair, which a
workstation `.env` must provide.

## Findings of the review

A code-reviewer agent reviewed the first full draft. Each behavioural finding was reproduced against that draft before
the repair, using a copy of its `nginx.conf` behind the phase-1 stand-in (`tmp/m29-repro-ingress*.log`).

| Finding | Repair | Evidence |
| --- | --- | --- |
| **R1. An unauthenticated request could hold a session's streams.** The session key comes from the path, `limit_conn` counts in the preaccess phase, and Nginx reads a request body only afterwards. Reproduced: four POSTs to a session's master announcing a 10 MB body and sending nothing got no answer, and the session's real GET was refused with 503 `Retry-After: 1` for as long as they stayed open. Anyone who knows a session ID (it is in every stream URL) could keep the player in a retry loop. | Non-GET methods (403) and any request announcing a body (400) are refused in the rewrite phase, before the limits count them. | Phase 1: four such POSTs, a GET with `Content-Length` and a chunked GET are each answered at once with 403 or 400, and the session's GET is served while they are open; POST, PUT, DELETE and HEAD are 403 problems. Mutants without either rule fail. |
| **R2. Nginx's own 502/504 on the stream route were HTML pages** without `no-store`, outside the contract's problems. | `error_page 502 504` answers a `503 MEDIA_UNAVAILABLE` problem without `Retry-After`. | Phase 1 with the stand-in stopped. A mutant without it fails. |
| **R3. A name with a known extension got Nginx's type.** `.../index.html` was a 404 with `Content-Type: text/html` (body static, not reflected). Reproduced by the first draft failing the new check. | `types { }` in every location that writes a problem. | Phase 1 (`index.html`, `x.jpg`); the contract test counts the four. A mutant fails. |
| R4. Phase 1 wrote its evidence into `target/verification/` without creating it; after a root `clean` that directory does not exist, so the smoke would fail. | The check creates the directory; the runner creates it first. | The check and the runner create it; the final run used the directory left by the gate's `clean verify`, so this is covered by reasoning, not by a run without it. |
| R5. Exported variables or a workstation `.env` could leak into the run, and `CORE_COOKIE_SECURE` was saved but not set. | Compose reads an empty `--env-file`; every variable the two Compose files interpolate (46) is cleared before the smoke sets its own and restored afterwards. | The run's settings are the generated ones plus Compose defaults. |
| R6. Stale evidence, missing `tmp/`, an unchecked phase-1 cleanup, and a claim that service logs exclude credentials. | Both evidence files are removed on start and failure, `tmp/` is created, phase-1 `down` is checked, and the claim is gone (Spring can echo a rejected property value). | — |

Kept as limits: the per-address key without `real_ip`, the ticket in ffmpeg's argv during the smoke, the TOCTOU on the
pre-chosen storage port, and `media.Dockerfile` mirroring the production runtime stage by hand.

## Evidence

`check-media-m29.ps1` generates every key, password and storage credential for the run (three RSA pairs: playback,
Core→Media control, Media→Core binding), keeps them in its own process environment and restores the previous values
afterwards. It runs two disposable Compose projects and removes their containers, volumes, networks and built images
(`down --volumes --rmi local`) whether it passes or fails.

**Phase 1, ingress rules** (`compose.ingress-m29.yaml`, `ingress_m29_limits.py`): the repository `nginx.conf` in
front of a slow Nginx stand-in for Media (32 bytes/s, so concurrent requests overlap deterministically; one name
answers with Media-style `503` + `Retry-After`).

- The location forwards `/v1/streams/{sessionId}/{name}` and the cookie unchanged, and adds no `Set-Cookie`.
- Media's `503` with `Retry-After` reaches the client as Media wrote it.
- Six malformed paths, including `%2e%2e/master.m3u8`, `index.html` and `x.jpg`: a 404 problem from Nginx.
- Unsent bodies and other methods are refused at once and cannot hold the session (R1).
- Five concurrent requests of one session: four served, one refused within 2 s with the 503 problem and
  `Retry-After: 1`; the share returns once they end. Seventeen over five sessions from one address: sixteen served,
  one refused with the same problem.
- With the stand-in stopped: a `503` problem without `Retry-After` (R2).

**Phase 2, the slice** (`compose.media-m29.yaml` over the real `infra/compose.yaml --profile full`,
`media_m29_http.py`). The overlay only swaps the source builds for images of the verified JARs
([`core.Dockerfile`](../infra/smoke/core.Dockerfile), [`media.Dockerfile`](../infra/smoke/media.Dockerfile): the
production runtime stage with the distribution's ffmpeg), moves host ports to random loopback ports, and leaves out the
web app and recommendation service. Everything the smoke does not set keeps its Compose default, including
`CORE_COOKIE_SECURE=true`, so the `__Secure-` cookie is what is tested; it sets `CORE_LOCAL_DEVELOPMENT=true` because
Core refuses plain-HTTP Media control otherwise.

1. Core and Media report healthy through Nginx (`/api/core/actuator/health`, `/api/media/actuator/health/readiness`).
2. The bootstrap administrator creates a PREMIUM movie and an upload; Core asks Media for a signed URL; the 12 s 720p
   H.264/AAC clip (generated by ffmpeg) is PUT to private SeaweedFS; completion queues one job.
3. Media's worker probes, transcodes and finalizes it to READY; the outbox marks exactly one READY event sent to real
   Kafka; Core projects the candidate binding as READY; the administrator publishes it.
4. A viewer registers, verifies the e-mail through Mailpit, buys simulated Premium and is admitted: Core's signer sets
   exactly one `__Secure-LIBRA_PLAYBACK` cookie with `Path=/api/media/v1/streams/{sessionId}/`, `Secure`, `HttpOnly`,
   `SameSite=Lax`, and the session's `manifestPath` is that path's `master.m3u8`.
5. Through Nginx: the master (naming only `rendition.m3u8`), the rendition and all 3 segments (4,118,328 bytes) return
   200 with their exact `Content-Length`, the contract content types, `no-store`, `nosniff` and no `Set-Cookie`.
6. ffmpeg decodes the downloaded presentation (`-xerror`, no output), and plays it **directly through Nginx** with only
   `Cookie: __Secure-LIBRA_PLAYBACK=…` (it fetches the master, rendition and segments itself); ffprobe reads H.264 +
   AAC, 12 s ± 0.5 s, at most 720p.
7. Refused through Nginx: no cookie, a bearer header carrying the valid ticket, the ticket under the non-secure name
   (each on the master, rendition and a segment, 401); identity cookies alone (401); the ticket on another session's
   path (403); a non-Premium verified viewer's admission (`403 PREMIUM_REQUIRED`, no ticket set). Every refusal is a
   `no-store` problem with a UUID correlation ID and no bucket, prefix, token or exception text.
8. Six malformed stream paths are Nginx's 404 (no `instance`, so Media never saw them); `/api/media/internal/...`,
   `/libra-hls/...`, `/libra-source/...` and `/s3/...` are 404.
9. Renewal reissues the cookie with a different ticket on the same path, and the renewed ticket plays.

Mutants of `nginx.conf`, each run against phase 1 and each killed (`tmp/m29-mutation-ingress*.log`): an empty session
key (`Per-session ingress limit is not 4`), no per-client limit (`… not 16`), no `@stream_busy` (the refusal is
Nginx's HTML page), no catch-all location (malformed paths reach the stand-in), `proxy_intercept_errors on` (Media's
503 is replaced), no method rule, no body rule, no `types { }` in the catch-all, and no `error_page 502 504` (with the
stand-in stopped). Attempts not counted: a first session mutant that deleted the `set` line, which makes Nginx refuse
the configuration. The per-session limit was first written with a `map` over `$uri`; the first phase-1 probe showed
five concurrent requests of one session all served, because the location's `rewrite` changes `$uri` before the limit
phase, so the key now comes from the location's own capture.

## Verification

| Evidence | Dependency | Checks |
| --- | --- | --- |
| `check-media-m29.ps1` phase 1 (`ingress_m29_limits.py`) | Repository `nginx.conf` in Nginx 1.29.3, slow Nginx stand-in for Media | 7 (6 + Media down) |
| `check-media-m29.ps1` phase 2 (`media_m29_http.py`) | **Real Compose stack**: packaged Core and Media, PostgreSQL 18.6, Kafka 4.3.1, Mailpit, SeaweedFS 4.46 behind `s3-gateway`, Nginx, ffmpeg in the Media image and on the host as the player | 10 |
| `test_media_stream_contract.py` | Contracts and `nginx.conf` only | 6 (1 new) |

The final smoke ran on the final tree with the JARs of the gate's `clean verify` (`tmp/m29-smoke-2.log`: 7 + 10 PASS,
`MEDIA_M29_SMOKE_PASS`); the first full run passed on the first draft before the review (`tmp/m29-smoke-1.log`, 5 + 10).
Afterwards no container, volume, network or image of either project remained.

A ci-gate agent ran every `.github/workflows/core-ci.yml` stage on this workstation and passed (logs in
`tmp/ci-gate/m29-*.log`): Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 16m18s); the
report gate confirmed 84 suites and 599 tests with zero failures, errors or skips: Core 187 (44 Surefire, 143
Failsafe), Media 410 (223 Surefire, 187 Failsafe), recommendation and analytics 2. M29 adds no Java test (there is no
Java change). The review repairs touched only `nginx.conf`, the smoke, the contract and its Python test, so those
stages were run again on the final tree: the Python tests passed (14, 1 new; `tmp/m29-python-gate-2.log`),
`check_contracts.py` passed 19 contracts and 3 wire examples (`tmp/m29-contracts-gate-2.log`), `docker compose config
--quiet` with `.env.example`, with and without `--profile full`, passed (`tmp/m29-compose-config-2.log`), the packaged
Core smoke, which runs the same `nginx.conf`, passed its 11 checks (`tmp/m29-core-smoke-2.log`), and `git diff --check`
was clean. All of it ran on the uncommitted tree with offline Maven and Docker Desktop on Windows, not on CI's runner;
CI does not run `check-media-m29.ps1`.

Work was divided among agents: a doc-finder agent mapped Core's admission, cookie, signer and upload wiring and the
Compose switches, a code-reviewer agent reviewed the first full draft (R1-R6 above, all applied), and a ci-gate agent
ran the gate.

## Limits

- No browser or web player: ffmpeg is the player. hls.js's retry of the 503, Chrome's handling of the `__Secure-`
  cookie on `http://localhost`, and the web app's playback page are the browser slice (M33 in the original plan).
- No TLS and no proxy in front of Nginx. A deployment needs TLS; the `__Secure-` prefix and `Secure` flag mean
  something only there, and a TLS terminator in front would make every viewer one peer address unless `real_ip` is
  configured for that terminator only.
- Abuse of the public paths beyond those tested (other encodings such as `;` or `%255c`, which only Spring's default
  firewall refuses at Media; ticket expiry in the middle of a presentation; repeated GETs; cache isolation between
  viewers; alternate routes to Media) is the next hardening milestone. Media cutting a response mid-body was not
  observed through Nginx.
- The ingress limits count connections at one Nginx; several Nginx instances each apply their own.
- A single blocked client write is bounded by Nginx's `send_timeout` (60 s default); Media's transfer deadline applies
  once Nginx's ~0.5 MiB of buffers are full.
- In the smoke the live ticket is in ffmpeg's and ffprobe's command line (visible in the local process list) for the
  seconds they run; it expires within five minutes and the key is discarded with the run.
- `media.Dockerfile` mirrors `services/media/Dockerfile`'s runtime stage by hand. Range, multiple renditions, key
  rotation, M31 cleanup and upload-expiry FAILED events remain open.
