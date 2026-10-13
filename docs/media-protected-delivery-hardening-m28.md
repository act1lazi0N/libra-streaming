# Media: Protected delivery hardening (Milestone 28)

Verification date: 2026-10-13.
Status: VERIFIED_LOCAL (the limits and the transfer bound proved with in-memory ports and a controlled clock, the
ticket rule through the full chain against real PostgreSQL, SeaweedFS and ffmpeg; Nginx, Core's own signer, a real
slow socket and a real HLS player were not run).

## Scope

M28 hardens the M27 stream routes ([Protected HLS delivery](media-protected-delivery-m27.md)) against the three
weaknesses its Limits named: a slow player holding a response without bound, no limit on concurrent streams, and
ticket timestamps that are not checked to be integers on the wire. It adds `StreamAdmission`, a deadline on every
opened stream, the `libra.media.delivery.*` settings and three Compose variables, changes `PlaybackGrant` to carry the
ticket's expiry, and moves [`media-streams.v1.yaml`](../contracts/http/media-streams.v1.yaml) to 1.1.0. It adds no
migration, event or Core change. Binding a ticket to a client (the fourth M27 limit) stays open: Core's contract makes
the ticket a bearer credential bounded by its expiry, and Media has nothing to bind it to that Core signs.

## Gate

A stream request still passes every M27 check in the same order, and in addition: no playback session and no Media
instance holds more streams than configured, a refusal for either is retryable and holds nothing, every admitted stream
gives its share back on every outcome (served, refused after admission, storage failure, gone player, timeout, an
`Error`), no response is written after its transfer timeout or after its ticket's `exp`, and a ticket whose `iat` or
`exp` is not a JSON integer is refused.

## Findings and repairs

Each finding was reproduced against the M27 code before repair: F1 and F2 by a temporary
`DeliveryHardeningReproTest` written against the M27 API (`tmp/m28-repro-delivery.log`, 2 tests, 2 failures; the test
was removed after the repair), F3 by the new verifier test run on the M27 verifier (`tmp/m28-repro-ticket.log`, 1
failure listing the 7 accepted tickets).

| Finding | Repair | Regression evidence |
| --- | --- | --- |
| **F1. A slow player held a response, its request thread and its storage connection without bound.** The storage socket timeout bounded each read, but a player that kept reading slowly was never cut. Reproduced by a body that trickled one byte every 10 ms: the M27 route served all of it with 200 after 4.7 s, and nothing in the route could stop it. | Every opened stream gets a deadline at admission: the earlier of now + `transfer-timeout` (60 s by default) and the ticket's `exp`. `Stream.transferTo` refuses to write once the clock reaches it (equality is expired) with `StreamDelivery.Expired`, which the route turns into an aborted connection, as for a storage failure after the headers. Time spent on the lookup and storage open counts against the same budget. | `StreamDeliveryTest`: a player taking 25 s or 30 s per write is cut at the 60 s deadline with the bytes before it delivered; time before the copy counts; a ticket expiring in 2 s cuts a transfer at 2 s although the timeout is 60 s. `StreamControllerTest`: a transfer past its deadline aborts the response and frees the session's share. Mutants that skip the deadline check or ignore the ticket's `exp` fail them. |
| **F2. No limit on concurrent streams.** Reproduced by eight concurrent requests of one session whose bodies blocked: all eight were in flight at once on the M27 route. One session (one copied cookie) could hold every request thread and storage connection. | `StreamAdmission` keeps a non-blocking instance semaphore (`max-streams`, 64) and per-session counts (`max-streams-per-session`, 4). The name is checked before admission; the permit is released in a `finally` unless the opened stream took it over, and `Stream.close()` releases the body, then the permit, once. Either refusal is `503 MEDIA_UNAVAILABLE` with `Retry-After: 1` and no detail. | `StreamAdmissionTest` (4): each limit, reuse after release, idempotent close, a refused request holding nothing, and 24 threads × 2,000 admissions over three sessions never exceeding either limit and returning every permit. `StreamDeliveryTest`: refusals after admission (missing object, foreign binding, storage down, a `StackOverflowError`) release the share. `StreamControllerTest`: both refusals are 503 with `Retry-After: 1`, a storage 503 has none, and after served, refused, interrupted, expired and gone-player requests the session can again hold both of its streams. Mutants without the session check, without the release on refusal, with the M27-style `catch (RuntimeException)` only, or with `close()` not releasing the permit each fail them. |
| **F3. `iat` and `exp` were not checked to be integers on the wire.** Core writes integer seconds (`PlaybackTickets.sign` truncates both), but Nimbus parses `1791878699.9`, `1791878700.0` and `1.7918787E9` as doubles and truncates them to a date before any validator runs. Reproduced with seven Core-signed tickets the M27 verifier accepted: a fractional, zero-fraction or exponent `exp`; the same for `iat`, including an `iat` half a second after now; and a claimed lifetime of 300.05 s that truncates to 300 s. | After the signature is verified, the claims validator re-reads the verified payload and requires `iat` and `exp` to be JSON integers (Nimbus parses those, and only those, as `Long`). Anything else is the ordinary `invalid_token` failure, a 401 with no detail. | `PlaybackTicketVerifierTest.issuedAtAndExpiryMustBeIntegerSecondsOnTheWireNotMerelyTruncateToThem`: an integer control ticket decodes; the seven cases and numeric text are refused without echoing the token. `ProtectedStreamIntegrationTest`: through the whole chain, the same expiry as an integer plays on every route and with `.5` is 401. A mutant whose check always passes fails the test (`tmp/m28-mutation-integral.log`). |

### Changes to earlier behaviour and tests

- A storage failure before the first byte is still `503` without `Retry-After`; only a stream limit invites a retry.
  The per-session refusal is a 5xx rather than `429 RATE_LIMITED` because the planned player (hls.js) retries 5xx
  responses by default and treats a 4xx as final; the review of the first draft, which used 429, raised this.
- `StreamDelivery` takes `Limits` and a `Clock`; `PlaybackGrant` takes the ticket's `expiresAt`; `Stream` exposes
  `length()` and a bounded `transferTo` instead of the raw body. `StreamDeliveryTest` and `StreamControllerTest` were
  adapted to the constructors (their M27 assertions are unchanged), and the controller test's ticket now takes its
  times from the test clock.
- `CoreTicketFixture` signs the claim set as an exact JSON payload so a test can put a literal number on the wire;
  every existing ticket is unchanged.
- The contract, 1.0.0 → 1.1.0: the integer-timestamp rule, the per-instance limits (stated as resource bounds, not a
  quota on sessions), the `RetryLater` 503 with `Retry-After: 1`, and the abort after the transfer timeout or `exp`.

### Configuration

| Setting (variable) | Default | Meaning |
| --- | --- | --- |
| `libra.media.delivery.max-streams` (`MEDIA_DELIVERY_MAX_STREAMS`) | 64 (Compose: 6) | Streams this instance serves at once. |
| `libra.media.delivery.max-streams-per-session` (`MEDIA_DELIVERY_MAX_STREAMS_PER_SESSION`) | 4 | Streams one playback session may hold at once; at most `max-streams`. |
| `libra.media.delivery.transfer-timeout` (`MEDIA_DELIVERY_TRANSFER_TIMEOUT`) | 60s | Longest one playlist or segment response may take, 1 s to 10 min; never past the ticket's `exp`. |

Inconsistent or missing values stop startup (`StreamDeliveryConfigurationTest`). Compose sets `max-streams` to 6
because Media reaches storage through `s3-gateway`, which allows 8 connections per client: more admitted streams
would turn into gateway refusals (503 without `Retry-After`) and starve the worker's own transfers.

The limits protect one instance; they are not the "simultaneous-stream quota" that
[Core playback](core-playback.md) rules out. A viewer can open as many playback sessions as Core admits; each is
limited only in how many requests it has in flight at one Media instance. A player normally has one or two.

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `StreamAdmissionTest` | In-memory, 24 threads | 4 (new) |
| `StreamDeliveryTest` | In-memory ports, controlled clock | 9 (5 new) |
| `StreamControllerTest` | Standalone MockMvc, in-memory ports | 7 (3 new) |
| `StreamDeliveryConfigurationTest` | `ApplicationContextRunner` | 1 (new) |
| `PlaybackTicketVerifierTest` | Nimbus signer, fixed clock | 8 (1 new) |
| `ProtectedStreamIntegrationTest` | **Real PostgreSQL 18.6, SeaweedFS 4.46, ffmpeg** | 4 (one extended) |
| `test_media_stream_contract.py` | Contracts only | 5 (1 new) |

The focused integration run passed before the review changes (`tmp/m28-focused-it-1.log`, 4 tests); the final run is
part of the reactor verify below.

A ci-gate run of every `.github/workflows/core-ci.yml` stage on this workstation passed. Java 21 offline root `clean
verify` passed all three backends (BUILD SUCCESS, 14m02s). The report gate confirmed 84 suites and 599 tests with zero
failures, errors or skips: Core 187 (44 Surefire, 143 Failsafe), Media 410 (223 Surefire, 187 Failsafe),
recommendation and analytics 2. M28 added 14 Java tests (all Surefire; the Failsafe suite gained assertions, not
tests). `check_contracts.py` passed 19 contracts and 3 wire examples, the Python tests passed (13, 1 new), `docker
compose config --quiet` (with and without `--profile full`) and the packaged Core smoke (11 checks) passed, and `git
diff --check` was clean. Raw logs: `tmp/m28-repro-*.log`, `tmp/m28-unit-*.log`, `tmp/m28-focused-it-1.log`,
`tmp/m28-mutation-*.log`, `tmp/m28-reactor-verify-1.log`, `tmp/m28-report-gate.log`, `tmp/m28-contracts-gate.log`,
`tmp/m28-python-gate.log`, `tmp/m28-compose-config.log`, `tmp/m28-core-smoke.log` (ignored). The gate ran on the
uncommitted tree with offline Maven on Windows, not on CI's runner.

Mutants, each run against the delivery unit suites and each killed (`tmp/m28-mutation-*.log`): `session` (no
per-session check, 4 failures), `deadline` (no deadline check, 3), `ticket-expiry` (deadline ignores `exp`, 1),
`release` (no release on refusal, 5), `error` (the M27 `catch (RuntimeException)` instead of `finally`, 1), `close`
(`Stream.close()` keeps the permit, 5), and `integral` (the F3 check always passes, 1 test listing the seven cases).

Runs that failed and are not counted: the first attempt of the post-review unit run (its log, `tmp/m28-unit-3.log`,
was overwritten by the passing rerun), where the controller test checked the session's free shares while its fake
storage still refused every open; and a first
`error` mutant that added a `catch` but kept the `finally` and so changed nothing (it survived, correctly). Work was
divided among agents: a doc-finder agent read Core's signer and contracts, a general-purpose agent reproduced and
repaired F3, a code-reviewer agent reviewed the first full draft (its findings on the ticket-expiry bound, the 429,
the `Error` leak, the controller-level release proof, the contract and the gateway limit were all applied), and a
ci-gate agent ran the final gate.

## Limits

- A single blocked write is bounded only by the servlet container's write timeout (Tomcat's connection timeout,
  left at its default); the deadline is checked between writes. No test used a real slow socket.
- Behind Nginx, `proxy_buffering` (on by default for `/api/media/`) absorbs a slow player, so Media's bound applies
  to Nginx's reading rather than the browser's; direct access to Media is bounded as described. Nginx limits for the
  stream location, hls.js retry behaviour on the 503, and the Compose stack with Core's real signer are M29.
- Limits are per instance and in memory: with several Media instances a session may hold its share on each. There are
  no metrics yet for refusals, cut transfers or free capacity.
- The deadline uses the wall clock (`Clock`): a large forward step cuts running streams early, a backward step lengthens
  them by at most the step.
- The ticket is still a bearer credential until `exp` (at most five minutes); a copied cookie works from another client
  and now shares the session's four streams with it. One key without overlapping rotation, Range requests, multiple
  renditions, replacement of a READY asset, M31 cleanup and upload-expiry FAILED events remain open.
