# Media: Protected HLS delivery (Milestone 27)

Verification date: 2026-10-13.
Status: VERIFIED_LOCAL (a READY generation made by the production pipeline in real SeaweedFS and PostgreSQL, served
through the full Spring Security chain and decoded by ffmpeg; Nginx, Core's own signer and the Compose stack were not
run).

## Scope

M27 delivers the Media half of the playback contract Core has offered since its playback milestone
([Core playback](core-playback.md#media-ticket-and-delivery-contract)): Media verifies Core's playback ticket on every
master playlist, rendition playlist and segment request and streams the READY generation from the private HLS bucket.
It adds the `media.delivery` package, the `PlaybackTicketSecurity` chain, the read-only `S3StreamObjects` adapter, the
`libra.media.playback.*` settings, the contract [`media-streams.v1.yaml`](../contracts/http/media-streams.v1.yaml) and
four Compose variables. It adds no migration, event, job stage or Core change: Core already signs the ticket, sets the
cookie on `/api/media/v1/streams/{sessionId}/` and returns that manifest path, and Nginx already forwards
`/api/media/` to Media.

## Gate

A request is served only when it carries exactly one ticket cookie that Core's pinned key signed for the session in
its path, and only from the READY generation Media itself records for the ticket's asset version and binding. No
browser input selects a key, prefix, bucket or version; no name outside the generation's vocabulary reaches storage;
no failure reflects a key, bucket or playlist; and what is served is byte for byte what finalization stored and
verified, and it plays.

## Route and decision

`GET /v1/streams/{sessionId}/{name}`, where `name` is `master.m3u8`, `rendition.m3u8` or `segment-00000.ts` through
`segment-00299.ts` (the encoder's cap). Each request is decided in this order; storage is touched only at the last
step.

| Step | Check | Refusal |
| --- | --- | --- |
| Credential | Exactly one cookie named `__Secure-LIBRA_PLAYBACK` (`LIBRA_PLAYBACK` when `secure-cookie=false`, matching Core's loopback setting). An `Authorization` header, a repeated cookie, an empty or over-8 KiB value is refused; a query parameter or another cookie name is never read. | 401 `AUTHENTICATION_REQUIRED` |
| Ticket | RS256 with Core's pinned public key (at least 2048 bits) and `kid`; `typ` JWT; `iss` `libra-core-playback`; sole `aud` `libra-media`; `purpose` `media-playback`; canonical lowercase UUID `sub`, `jti`, `contentId`, `bindingId`, `assetId`; integer `assetVersion` > 0; `iat` not after now; `exp` strictly after now and after `iat`; at most 300 s between them. Nothing in the token chooses a key or URL. | 401 |
| Session | The ticket's subject equals the path's `sessionId` (in the chain, and again in the route). Any method but GET, and any other path shape, is denied. | 403 `ACCESS_DENIED` |
| Name | In the closed vocabulary above. | 404 `NOT_FOUND` |
| Selection | Media's row for (`assetId`, `assetVersion`) exists and is READY, and its `content_id` and `binding_id` equal the ticket's. A row for another binding or content is denied; an unknown or not yet READY version is not found. A READY row whose master key is not `output_prefix + "/master.m3u8"` is treated as broken (503), never guessed around. | 403 / 404 / 503 |
| Object | Key = the row's `output_prefix` + `/` + name. A missing object is 404; storage trouble, or an object larger than its kind allows (256 KiB for a playlist), is 503 `MEDIA_UNAVAILABLE`. | 404 / 503 |

A served object is a complete `200` with its exact `Content-Length`, `Content-Type` `application/vnd.apple.mpegurl` or
`video/mp2t`, `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`, copied from storage with a 64 KiB buffer
(never held in memory or scratch). Range is not supported; the playlists name whole segments. If storage fails after
the headers are sent, the route throws so the container cuts the connection instead of ending the body short; a
player that stops reading ends the request quietly and the storage stream is aborted. Problem bodies carry only the
status, code, a correlation ID and the requested path, with `no-store` and no `WWW-Authenticate` challenge.

Playlists are the ones finalization wrote and verified (M23/M24): the master names only `rendition.m3u8`, which names
only `segment-NNNNN.ts`, all relative, so a player resolves every request beneath the same session path and Core's
cookie. The ticket is a bearer credential bounded by its expiry: logout, history clear, unpublication or lost Premium
stop new admission and renewal at Core, and delivery stops when the current ticket expires (at most five minutes).

### Configuration

| Setting (variable) | Meaning |
| --- | --- |
| `libra.media.playback.enabled` (`MEDIA_PLAYBACK_ENABLED`, default `false`) | Off: every stream request is 401. On: the key and key ID are required at startup. |
| `libra.media.playback.public-key` (`MEDIA_PLAYBACK_PUBLIC_KEY`) | Base64 X.509 SubjectPublicKeyInfo of Core's playback key (Core's `CORE_PLAYBACK_PUBLIC_KEY`; never the private key). |
| `libra.media.playback.key-id` (`MEDIA_PLAYBACK_KEY_ID`) | Core's `CORE_PLAYBACK_KEY_ID`. |
| `libra.media.playback.secure-cookie` (`MEDIA_PLAYBACK_SECURE_COOKIE`, default `true`) | Must equal Core's `CORE_COOKIE_SECURE`. |

Compose forwards `CORE_PLAYBACK_PUBLIC_KEY`, `CORE_PLAYBACK_KEY_ID` and `CORE_COOKIE_SECURE` to these and leaves
delivery disabled unless `MEDIA_PLAYBACK_ENABLED=true`.

## Evidence

`ProtectedStreamIntegrationTest` makes generations READY with the production finalizer (real ffmpeg, real SeaweedFS,
real PostgreSQL, the M23 fixture) and requests them through MockMvc with the whole security chain. Tickets are signed
by `CoreTicketFixture`, which mirrors Core's `PlaybackTickets.sign` (same header, claims and types); Core's class is in
another service and not on Media's classpath.

- **Served exactly as stored, and it plays.** For the 20 s 720p clip with AAC, the master, the rendition and every
  segment the rendition names return 200 with the headers above, each body equal to the stored object; the names served
  are exactly the objects under the generation's prefix. The served files, written to a directory, decode as one HLS
  presentation with `ffmpeg -v error -i master.m3u8 -map 0 -f null -` (exit 0, no output).
- **Every route refuses a bad ticket.** For the master, the rendition and a segment: no cookie, the non-secure cookie
  name, a bearer header (alone or with the cookie), a repeated cookie, `?access_token=`, an expired ticket, a ticket
  signed by another key, a Core service-access token and a corrupted signature are 401; another session's ticket, or
  the session's ticket on another session's path, is 403; POST, PUT, DELETE and HEAD with a valid ticket are 403. A
  valid ticket on the same route returns 200, so each refusal is about the ticket.
- **Only Core's selection, only once READY.** With a READY asset A and a claimed, PROCESSING asset B: A's ticket plays;
  B's ticket, A's asset with version + 1 and an unknown asset are 404; A's asset with B's binding or B's content is
  403; B's ticket cannot reach a segment.
- **Names outside the generation reach nothing.** `source.mp4`, `segment-00300.ts`, an in-vocabulary but absent
  `segment-00299.ts`, `master.M3U8`, `rendition.m3u8.bak`, `sources` and `staging` are 404; encoded and dot-segment
  traversal, an extra path segment and an empty name are refused (400-404) with no key or playlist in the body.

Unit evidence: `PlaybackTicketVerifierTest` (fixed clock: the whole lifetime is accepted, equality with `exp` is
expired, 38 single-property faults each refused, tampering, unsigned, HS256 keyed with the public key, disabled and
weak-key configuration, the cookie-only resolver and the session match); `StreamNameTest`
(the vocabulary, and agreement with `HlsPackage`'s names and cap); `StreamDeliveryTest` (check order: a bad name never
looks up the asset, a refused selection never opens storage); `StreamControllerTest` (headers, sanitized problems
including a database error carrying SQL, a mid-body storage failure aborting, a gone player releasing the object);
`DeliveryArchitectureTest` (framework-free application and domain; delivery reads no processing, upload, events or
storage class, and nothing depends on delivery). `test_media_stream_contract.py` checks that Core's `manifestPath`
reaches the master route once Nginx strips `/api/media`, and the cookie scheme and error codes.

Four mutants were each killed by the unit suites (`tmp/m27-mutation-*.log`): a session match that ignores the
subject, an expiry that accepts equality, a delivery that skips the binding and content check, and a resolver that
ignores an `Authorization` header. The session is checked twice (chain and route), so a mutant of either check alone
would still be refused end to end; each check therefore has its own unit test
(`theChainOpensOnlyThePathOfTheTicketsOwnSession`, and the route case in `StreamControllerTest`). The mutants were
run against the unit suites only.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `PlaybackTicketVerifierTest` | Nimbus signer, fixed clock | 7 (new) |
| `StreamNameTest`, `StreamDeliveryTest`, `StreamControllerTest` | In-memory ports, standalone MockMvc | 2 + 4 + 4 (new) |
| `DeliveryArchitectureTest` | ArchUnit | 2 (new) |
| `ProtectedStreamIntegrationTest` | **Real PostgreSQL 18.6, SeaweedFS 4.46, ffmpeg** | 4 (new) |
| `test_media_stream_contract.py` | Contracts only | 4 (new) |

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 13m24s). The report gate confirmed 82
suites and 585 tests with zero failures, errors or skips: Core 187 (44 Surefire, 143 Failsafe), Media 396 (209
Surefire, 187 Failsafe), recommendation and analytics 2. M27 added 23 Java tests (19 Surefire, 4 Failsafe).
`check_contracts.py` passed 19 contracts and 3 wire examples, the Python contract tests passed (12, 4 new),
`docker compose --profile full config --quiet` passed with placeholder storage keys, and `git diff --check` was
clean. Raw logs: `tmp/m27-unit-*.log`, `tmp/m27-focused-it-*.log`, `tmp/m27-mutation-*.log`,
`tmp/m27-reactor-verify-1.log`, `tmp/m27-report-gate.log`, `tmp/m27-contracts.log`, `tmp/m27-python-tests.log`
(ignored).

Runs that failed and are not counted: the first unit run, where the verifier test's guard expected more than 40 fault
cases (there are 38, all refused); the first two focused integration runs, where a problem-body assertion rejected
`attempt-` although it came from the requested name echoed as the problem `instance` (the name was replaced and the
assertion kept for storage-specific strings); and the first Python run, which looked up Core's session schema under
the wrong name.

The finalization fixtures (`SourceStorageFixture`, `FinalizationFixture`, `MediaFixtures`) became public, with the
members the delivery suite uses made protected, so it can live in the delivery package; no existing test changed.

## Limits

- Nginx, Core's real signer and an admitted Core session were not exercised together: the ticket is a contract mirror
  and the requests did not cross the ingress. The browser player, CORS-free same-origin serving through Nginx and the
  Compose stack with Core and Media sharing the playback key are M29.
- Hardening is M28: a slow or stalled player holds a storage connection for as long as it reads (the storage socket
  timeout bounds each read, nothing bounds the whole response); there is no per-session or global concurrency limit;
  `iat`/`exp` are not checked to be integral seconds on the wire; a ticket is not bound to anything but its session
  path, so a copied cookie value works from another client until expiry.
- One configured key, no overlapping rotation: rotating Core's playback key fails outstanding tickets for up to five
  minutes. There is no revocation list, by design of the Core contract.
- Range requests, multiple renditions, shared-cache rules beyond `no-store`, replacement of a READY asset with a newer
  version, M31 cleanup and upload-expiry FAILED events remain open. GitNexus was not used.
