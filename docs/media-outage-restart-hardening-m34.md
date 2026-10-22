# Media: Outages and restarts of the whole flow (Milestone 34)

Verification date: 2026-10-22.
Status: VERIFIED_LOCAL (the disposable Compose stack of the real `infra/compose.yaml` and `infra/nginx/nginx.conf`,
packaged Core and Media JARs, PostgreSQL, Kafka, Mailpit and SeaweedFS; Docker Desktop on one Windows workstation).

## Scope

M34 repeats the whole slice while Core, Media, Kafka, storage and PostgreSQL are interrupted one at a time at chosen
points and then restored. Each row of the matrix records its precondition, the injected fault, the persistent state it
expects, the recovery action and what was observed. The faults are Docker operations on the disposable project's own
containers: `stop`, `kill` (SIGKILL), `pause`, `restart`, a second instance started with `compose run`, and a
PostgreSQL role switched to `NOLOGIN`. ffmpeg is the player, as in M29.

Three rows failed on the M33 code, and a fourth on the build that fixed them. Each is reproduced before its repair and fixed below:

| ID | Finding | Repair |
| --- | --- | --- |
| H1 | Core's projection of Media events dead-lettered a valid READY event when Core's own database was unavailable for longer than three attempts (about 2 s of back-off plus three connection timeouts). The listener rethrew every failure as one sanitized exception, so the error handler could not tell an outage from poison input. After the database returned, the candidate stayed unready until an operator redrove the dead letter. | The listener walks the failure's causes. A failure is transient when a cause is a Spring transient, recoverable or resource-failure data access exception, a transaction that could not start, a JDBC transient or recoverable exception, or a SQL exception whose state is a connection failure (`08`), insufficient resources (`53`), a server shutdown (`57P0`), a serialization failure or deadlock (`40001`, `40P01`) or an unavailable lock (`55P03`). That includes a connection lost during commit. A program-limit state (`54`) is never transient: it depends on the data, although Spring translates it as a resource failure. Transient failures leave as `MediaEventRetryableException`, with no message, cause or stack. The error handler retries them in place with exponential back-off from 1 s to 30 s and no attempt limit. Everything else keeps the bounded `FixedBackOff(1000, 2)` and the sanitized DLT record. A counter `libra.integration.media.retries` with one tag (`kind` = `transient` or `poison`) counts retried deliveries. |
| H2 | `core` and `media` in `infra/compose.yaml` had no restart policy. Media refuses to start while it cannot reach its buckets (M6). Restarted during a storage outage, it exited and stayed down after storage returned. | `restart: unless-stopped` on every application service (`core`, `media`, `recommendation-analytics`, `web`, `nginx`), as the infrastructure services already had. |
| H4 | Found by F17 on the build with H1-H3 fixed. Media now restarted on its own once storage returned (its log shows a completed startup), but the ingress never reached it again, not even after an explicit start. Nginx had resolved `media` once at startup; Media and the storage gateway, restarted together, came back on each other's former addresses. | The four upstreams resolve through Docker's DNS (`resolver 127.0.0.11 valid=5s`) with `server ... resolve` in a shared-memory zone (Nginx 1.27.3+, the image is 1.29.3). Nginx also starts while an upstream is down. |
| H3 | With Core stopped, a Core API request through the ingress waited Nginx's default 60 s connect timeout and then received Nginx's HTML gateway page. The stream location already answered such failures with a problem (M29/M30). | The `/api/core/` location connects within 3 s and answers Nginx's own 502/504 as a no-store 503 problem `CORE_UNAVAILABLE` (a code the contract already listed). Core's own responses pass through unchanged (no `proxy_intercept_errors`). |

Deliverables:

| Path | Role |
| --- | --- |
| `services/core/.../integration/media/MediaAssetListener.java`, `MediaConsumerConfiguration.java`, `MediaEventRetryableException.java`, `MediaFailures.java` | H1. |
| `services/core/src/test/.../integration/media/MediaOutageRetryIntegrationTest.java`, `MediaAssetListenerClassificationTest.java` | H1's integration and unit tests. |
| `infra/compose.yaml` | H2. |
| `infra/nginx/nginx.conf`, `infra/ci/test_media_stream_contract.py` | H3 and H4 and their static checks. |
| `infra/smoke/media_m34_faults.py`, `infra/smoke/check-media-m34.ps1`, `infra/smoke/check-media-m29.ps1 -Milestone 34` | The fault matrix: M29's runner (disposable project, generated keys, packaged JARs, cleanup) without M29's phase 1. |

No contract, migration or Media code changes.

## Gate

The end-to-end failure matrix converges without a duplicate logical job, a second READY event, a corrupted selected
output, an unauthorized admission or publication, or media served without a ticket. Every row matches the local
boundary tests that already cover it, and each finding is reproduced on the previous code before its repair.

## Fault matrix

Every row starts from the running stack and a published baseline clip (20 s, 1280x720, five 4 s segments).

| Row | Injection | Expected persistent state | Recovery | Observed (fixed build) | Boundary tests |
| --- | --- | --- | --- | --- | --- |
| F1 | `stop media` before `POST .../uploads`, again before `POST .../complete` | Core intent committed; no Media upload or job; 503 `MEDIA_UNAVAILABLE` problem | Start Media; repeat the same `requestId` and the completion | 503 twice; the retry answered 200 with one Media upload; one job; READY projected | `UploadProvisioningIntegrationTest`, `MediaControlClientTest` |
| F2 | `stop s3-gateway` before `POST .../complete` | No job without a verified staged source; Media readiness DOWN | Start the gateway; repeat the completion | 503 with no job and readiness DOWN while down; READY after restore | `UploadControlIntegrationTest`, `MediaStorageIntegrationTest` |
| F3 | `kill media` (SIGKILL) while the job is TRANSCODING | Job leased, no selected output | Start Media; the lease expires and the job is reclaimed | Job SUCCEEDED at attempt 2; one READY event; published HLS decodes | `JobLeaseIntegrationTest`, `FfmpegTranscodeIntegrationTest` |
| F4 | `kill media` right after 202 QUEUED | Staged source retained | Start Media | READY once with one selected source; Core candidate READY | `WorkerProcessRecoveryIntegrationTest` |
| F5 | `pause media` while TRANSCODING; a second instance (`compose run`) reclaims; then unpause | The successor's generation selected once | Unpause the stale instance | Its encoder was frozen while paused and gone at once after unpause; selection unchanged; one READY event; job unchanged | `FinalizationConsistencyIntegrationTest`, `BoundedMediaWorkerTest` |
| F6 | `stop kafka` before completion | READY committed in Media; the asset's oldest event retried with `BROKER_UNAVAILABLE`; nothing sent; Core candidate not READY | Start Kafka | One READY event SENT; Core candidate READY | `OutboxPublicationIntegrationTest` |
| F7 | `stop core` after 202 QUEUED | Media READY and SENT; Core's offset uncommitted | Start Core | Candidate READY; two receipts, one per event of the asset | `CatalogProfilesIntegrationTest` |
| F8 | `stop core` with an admitted session | The issued ticket keeps its bounded lifetime; no renewal | Start Core; renew | A segment answered 200 byte-identical while Core was down; renewal 503 `CORE_UNAVAILABLE` in 3.0 s; 200 after; HLS decodes | `PlaybackTicketContractTest`; new `test_core_unreachable_...` |
| F9 | `stop s3-gateway` with an admitted session | No bytes served | Start the gateway | 503 `MEDIA_UNAVAILABLE` problem while down; the same bytes after | `ProtectedStreamServerIntegrationTest` |
| F10 | `stop media` with an admitted session | No bytes served | Start Media | Nginx's 503 `MEDIA_UNAVAILABLE` problem; HLS decodes after | M29 phase 1 |
| F11 | `restart postgres` | Pooled connections fail; services keep running | None | Admission, playback and administration work afterwards | none before M34 |
| F12 | 4 concurrent completions of one upload; 6 concurrent admissions | One job; one session per admission | None | Completions 202 x4 with one job; admissions 201 x6 with six distinct sessions | `UploadControlIntegrationTest.simultaneousCompletions...` |
| F13 | `logout-all`, admin suspension, admin logout; access tokens replayed | Core refuses revoked access; issued tickets expire within 300 s | None | Renewal and admission 401 with the replayed token; the issued ticket still 200 within its lifetime; the suspended viewer's renewal 401; the revoked administrator's command 401 | `IdentitySecurityIntegrationTest`, `PlaybackIntegrationTest` |
| F14 | Upload created and deadline moved three hours back in both databases (synthetic) | No grant, no completion; lifecycle EXPIRED with a FAILED event | None | Grant and completion 410 `UPLOAD_EXPIRED`; upload EXPIRED; Core candidate FAILED | `UploadControlIntegrationTest.expired...` |
| F15 | One byte of the source flipped under the declared SHA-256 | Never READY from those bytes | Upload the declared bytes | Storage refused the PUT (400); completion 409 `SOURCE_MISSING`; READY after the correct PUT | `SourceFreezerTest`, `SourceFreezingIntegrationTest` |
| F16 | `libra_core` role NOLOGIN and its sessions ended for longer than three connection timeouts while READY is consumed (H1) | Event retried in place, not dead-lettered | Role LOGIN again | READY projected without an operator; no dead letter | new `MediaOutageRetryIntegrationTest` |
| F17 | `stop s3-gateway`; `restart media`; start the gateway (H2, H4) | Startup refused while storage is down | Start the gateway only | Media restarted on its own; its address changed; reachable through the ingress again; playback decodes | new `test_ingress_follows_...` |
| F18 | `stop core` and `media`; start Media, then Core | The ingress follows current addresses | None | Addresses unchanged in this run; both reachable; playback decodes | new `test_ingress_follows_...` |

The convergence check after the last row found no upload with more than one job, no asset with a second READY event,
an empty unsent outbox and an unpublished entry refused admission.

## Reproduction on the M33 code

The same driver ran against a worktree of `e079b97` (the M33 commit) with its own Core JAR, the unchanged Media JAR and
the M33 `compose.yaml` and `nginx.conf` (`tmp/m34-smoke-run7-head.log`). Every row except three passed, and the
convergence check passed. The three open findings were:

- H3 (F8): with Core stopped, the renewal request got no answer within the client's 30 s.
- H1 (F16): after Core's database returned, the candidate was not READY and one dead letter had been recorded.
- H2 (F17): Media exited during the storage outage and was still down after storage returned.

The new tests also fail on the old code:

- `MediaOutageRetryIntegrationTest` failed against the unchanged listener: the candidate stayed `PENDING`. With the
  classifier forced to treat every failure as poison (a mutant), it fails again: the record is dead-lettered after
  three attempts, so the outage never reaches the six failed attempts the test waits for.
- `test_core_unreachable_through_the_ingress_is_a_prompt_problem` and
  `test_ingress_follows_the_current_address_of_every_upstream` fail against the M33 `nginx.conf`.
- H4 was reproduced by the run on the H1-H3 build (`tmp/m34-smoke-run9.log`, services log
  `tmp/m34-smoke-run9-services.log`): F17 recorded `Media running during the outage; Media still down after it`, and
  the explicit restart that followed did not make Media reachable through the ingress either.

## Observations

- **Ordered outbox.** With Kafka down, the event Media retries is the asset's oldest unsent one; READY waits behind
  it with no attempt of its own (the outbox delivers an asset's events in version order, M25). The first version of
  the driver expected READY itself to be retried.
- **Paused Media.** While the Media instance behind the `media` alias is paused, Core's upload status (which reads
  Media) answers 503 `MEDIA_UNAVAILABLE`, as specified. F5 therefore watches the job in Media's database.
- **`docker kill` is a manual stop.** Docker never restarts a container stopped with `docker kill`, whatever its
  policy, so H2 is shown with a startup Media refuses on its own. Killing the process from the VM's PID namespace
  would need a privileged container and was not done.
- **Tampering.** SeaweedFS 4.46 checks the signed `x-amz-checksum-sha256` header: a PUT of altered bytes with the
  granted headers is refused with 400 and nothing is staged.

## Verification

The final fault-matrix run, `infra/smoke/check-media-m34.ps1` (`tmp/m34-smoke.log`), used the JARs from the clean
verify below and passed all 18 rows and the convergence check (`MEDIA_M34_FAULTS_PASS`). It wrote
`target/verification/media-m34.json`: the 18 rows with injection, expected state, recovery and observation, plus 21
PASS lines. Neither file contains a JWT, an S3 signature, a ticket cookie or a password.

Earlier runs, kept in `tmp/`:

- Runs 1 to 6 (`m34-smoke-run1.log` to `run6-head.log`) failed on the driver itself: a wrong job stage name, wrong
  column names, Core's status read through a paused Media, and the outbox order expectation above.
- Run 7 (`m34-smoke-run7-head.log`) reproduced H1 to H3 on the M33 code.
- Run 8 was cut short by the workstation's session ending after F9, all passing; its project was removed by hand.
- Run 9 (`m34-smoke-run9.log`) passed every row on the H1-H3 build except F17, which found H4.

Gates on the final tree:

- Java 21 offline root `./mvnw -o clean verify` passed all three backends (BUILD SUCCESS, 17m40s,
  `tmp/m34-verify.log`).
- `infra/ci/check_test_reports.py`: 93 suites and 697 tests with zero failures, errors or skips:
  - Core 237 (93 Surefire, 144 Failsafe), M33's 188 plus the 48 classification tests and the outage IT;
  - Media 458 (244 Surefire, 214 Failsafe), unchanged;
  - recommendation and analytics 2.
- The H1 tests were also run alone on the host with their neighbours (`tmp/m34-h1-tests.log`):
  `MediaAssetListenerClassificationTest` 48, `MediaOutageRetryIntegrationTest` 1, `CatalogProfilesIntegrationTest` 14,
  `IntegrationOperationsIntegrationTest` 11, all passing.
- The 18 Python gate tests passed, and `check_contracts.py` passed 22 checks.
- `docker compose config --quiet` passed; `nginx -t` on the repository `nginx.conf` passed in `nginx:1.29.3-alpine`.
- `git diff --check` is clean.
- The packaged Core smoke (`infra/smoke/check-core.ps1`, the CI stage) printed 11 PASS lines (`tmp/m34-core-smoke.log`).

The M29 smoke, which runs the ingress limit checks against a stand-in and the whole slice through the changed
`nginx.conf`, was run once more after the final change (`tmp/m34-m29-regression.log`): 17 PASS lines and
`MEDIA_M29_SMOKE_PASS`. The M30 and M33 paths were not run again. No disposable project left a container, volume or
`libra-*` image behind.

Work was divided among agents. A doc-finder agent mapped what the boundary tests already establish for each fault. The
coding agent handed H1 to Codex (gpt-6.1-sol), which wrote the fix and its tests; Codex's sandbox could not reach
Docker, so the agent and the main session ran the Testcontainers tests on the host. A code-reviewer agent reviewed the
full draft. Its findings and their outcome:

- Nginx resolving upstreams once at startup: not reproduced by F18, then found by F17 as H4 and fixed.
- A program-limit SQL state translated as a resource failure could retry poison forever: class `54` is now poison.
- Lock unavailability (`55P03`) and a connection lost at commit were poison: both are transient now.
- No signal for a partition held by retries: the retry counter.
- A duplicated cause walk: one helper.
- Driver races: the concurrent refresh is locked and forced first; F5 waits for the stale encoder instead of a fixed
  sleep.
- The ingress 503 after a request was sent: documented under Limits.
- Not taken: polling the job with a held connection (F3 and F5 reached TRANSCODING every run) and `Retry-After` on
  `CORE_UNAVAILABLE` (a Core command is not always safe to repeat blindly).

## Limits

- **One machine, one instance each.** Every service ran once on one Docker Desktop host, except F5's second Media
  instance. No network partition, packet loss, clock skew, disk-full or slow-disk fault was injected. Kafka has one
  broker, so no broker failover was tested.
- **Crash points are coarse.** Media was killed while TRANSCODING and right after completion. Kills inside source
  freezing and finalization are covered by the boundary tests named above, not end to end.
- **Synthetic expiry.** F14 moves the upload's creation and deadline into the past in both databases; no hour was
  waited.
- **Blocking retries.** A transient failure now holds its partition until Core's database returns. Every Media event
  is ordered behind it, which is the intent. A failure misclassified as transient would block the partition until
  someone intervenes; the projection writes only fixed-length values today, so no data-dependent resource failure is
  known. The retry counter shows a stuck partition; consumer lag itself is not exported.
- **Ingress 503 after the request was sent.** Nginx answers a read timeout or a connection Core dropped mid-request
  with the same 503 `CORE_UNAVAILABLE` as a refused connection. Such a command may have been applied. Core's commands
  carry request identities, idempotency keys or expected versions, so a retry is answered consistently; the problem
  itself does not say which case happened.
- **Issued tickets survive revocation.** A ticket keeps working until its expiry (at most 300 s) after logout,
  suspension or a Core outage. This is the documented design (`docs/core-playback.md`), now observed end to end.
- **Restart policy only.** `unless-stopped` restarts a process that exits. It does not restart a hung one; there is no
  liveness-driven restart outside an orchestrator. A misconfigured service now restarts in a loop instead of exiting once,
  and the full profile comes back when Docker Desktop starts.
- **Ingress DNS.** Nginx re-resolves every 5 s through Docker's embedded DNS. Names from `extra_hosts` (used by the
  smoke overlays for services they do not run) are not served by that DNS; those upstreams stay unresolved, which the
  smokes never request.
- **Storage gateway.** `infra/nginx/s3-gateway.conf` still resolves SeaweedFS once at startup. No row restarted
  SeaweedFS itself.
- **Unchanged from M33.** One browser for the browser slice; no retention policy for failed assets; no metrics scrape
  endpoint; Core and Media are deployed together.
- **Next.** Per the plan, M35 packages repeatable verification (a Media CI lane and the handoff).
