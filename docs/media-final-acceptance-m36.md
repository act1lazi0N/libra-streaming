# Media: Final acceptance and evidence hardening (Milestone 36)

Verification date: 2026-11-02.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

M36 is the last Media milestone of the first slice. It reviews the integrated change against the original plan, tests
the verification tools' own failure paths, adds a fresh-start and retained-state restart check, and ties the final
evidence to the exact revision it was produced from: `toolchain.json` records the revision, and every test and smoke
evidence file carries the run identity that links it to that record. It changes no service code, public contract or Flyway
migration. Its runtime changes are confined to local infrastructure (Compose Kafka storage and PostgreSQL connect
grants) and one test's setup.

### Findings

Each finding was reproduced before it was fixed. Raw logs are in the ignored `tmp/`.

- **H1 — evidence did not name a revision.** `toolchain.json` and the smoke evidence carried a run identity but no
  commit, so a PASS could not be tied to the tree that produced it. `infra/ci/record_toolchain.py` now records
  `revision` = `commit`, `dirty`, `changedPaths` and `stateSha256`. `stateSha256` hashes the binary
  `git diff HEAD` (external diff and textconv drivers disabled) plus the path and blob hash of every untracked,
  non-ignored file, so two different dirty trees on the same commit differ. Any git error fails the record.
  `--revision` prints the same record as one JSON line. `verify-local.ps1` captures it before anything runs and
  compares it with `toolchain.json` right after Maven; `check_smoke_evidence.py` recomputes it at the end and fails
  if the tree changed. The final PASS line prints the commit and the dirty flag.
- **H2 — a stale PASS survived an early failure.** `target/verification` was only replaced step by step, so a run that
  failed before the smokes left the previous run's files. On this workstation the M35 `smoke-gate.json` PASS of
  2026-10-26 was still on disk at the start of M36. `verify-local.ps1` now deletes `target/verification` before it
  captures the revision. Its guard only checks that the path is the repository's own `target/verification`; it does
  not resolve junctions or symbolic links.
- **H3 — `tests.json` was not tied to its run.** It could be copied from another run. `check_test_reports.py` now
  writes `result: TESTS_PASS` and, when the run variables are set, `runId` and `startedAt`; it rejects any report not
  newer than `LIBRA_VERIFY_STARTED_AT` (Unix epoch in nanoseconds, set by `verify-local.ps1` and by a new step of the
  CI workflow right after checkout). The evidence gate
  requires `LIBRA_VERIFY_STARTED_AT`, requires both JARs to be newer than it, and requires `tests.json` with
  `TESTS_PASS` and the same `runId` and `startedAt`.
- **H4 — the report gate only read reports of known sources.** It parsed the report of each
  `*Test.java`/`*IntegrationTest.java` source, so a failed or skipped report of any other suite in the same directory
  was never read. It now also parses every `TEST-*.xml` under each module's Surefire and Failsafe report directories,
  counting each report once, and still requires a report for every source.
- **H5 — gaps in the secret scan.** The evidence scan did not recognize AWS SigV4 material (the
  `AWS4-HMAC-SHA256` scheme or a `Signature=` of 64 hex digits) or long hexadecimal runs (32 or more digits, one case)
  such as HMAC keys. Both rules were added. The only exemptions are the exact JSON members `runId`, `commit` and `stateSha256`, whose values the gate
  itself validates; the same hex anywhere else fails.
- **H6 — smoke cleanup was assumed from an exit code.** `docker compose down` exiting 0 was taken as proof that the
  disposable project was gone. New `infra/smoke/assert-compose-removed.ps1` lists containers, volumes, networks and
  images still labeled with the project and throws if any remain. `check-core.ps1` and `check-media-m29.ps1` (for the
  main and the ingress project) call it after `down`. A cleanup failure no longer hides the smoke's own failure: both
  messages are reported.
- **H7 — the acceptance smokes had no gate.** M30, M33 and M34 evidence was written but never checked.
  `verify-local.ps1 -Acceptance` runs `check-media-m30.ps1`, `-m33`, `-m34` and the new `-m36` after the CI smokes,
  and `check_smoke_evidence.py --acceptance` then requires `media-m30.json`, `ingress-m30.json`, `media-m33.json`,
  `media-m34.json` and `media-m36.json` with this run's identity. The M30, M34 and M36 Python writers and the M33
  Playwright spec now write `runId`; `check-media-m29.ps1` passes `LIBRA_VERIFY_RUN_ID` to the browser run as
  `LIBRA_M33_RUN_ID`.
- **H8 — Kafka lost its data on `down`/`up`.** `apache/kafka:4.3.1` keeps its KRaft log in
  `/tmp/kraft-combined-logs` inside the container. A `compose down` followed by `up` recreated the container and lost
  every topic, unacknowledged event and consumer offset, while PostgreSQL and SeaweedFS kept theirs. Each recreation
  also left three unlabeled anonymous volumes from the image's `VOLUME` declarations. Reproduced with the new restart
  check on the old Compose file: `FAIL after 9 checks: Kafka end offsets changed after restart: {0: 2} -> {0: 0}`
  (`tmp/m36-restart-old-compose.log`, `tmp/m36-restart-diag.log`). `infra/compose.yaml` now sets
  `KAFKA_LOG_DIRS=/var/lib/kafka/data` on a named `kafka-data` volume and mounts the two unused image volumes as
  tmpfs.
- **H9 — every service role could connect to every service database.** PostgreSQL grants `CONNECT` and
  `TEMPORARY` to `PUBLIC` by default, so `libra_core` could open `libra_media` and the reverse
  (`tmp/m36-db-connect-repro.log`: `exit=0` under the old init SQL). Ownership was separate but not enforced at
  connect. The init SQL now revokes both from `PUBLIC` on the three service databases and grants `CONNECT` on each
  to its owner; under the new SQL the core/media cross connections fail with `User does not have CONNECT privilege.`
  while each owner still connects to its own database. The revoke covers only the service databases: `PUBLIC` keeps
  its default access to `postgres` and `template1`, which hold no service data.
- **Outbox recovery test setup.** M35 recorded one setup failure of `OutboxProcessRecoveryIntegrationTest` with an
  empty claim right after queueing a job. A job is queued with `next_attempt_at = clock_timestamp()` and claimed with
  `next_attempt_at <= clock_timestamp()`, so a backward step of the database clock (Docker Desktop's VM corrects its
  clock) makes the job briefly not yet due. Reproduced by shifting `next_attempt_at` 500 ms into the future: the old
  setup failed with `NoSuchElementException: No value present` at line 82
  (`tmp/m36-outbox-claim-repro-old.log`), the new `claimQueued()` waits up to 10 s and passed in 53.3 s
  (`tmp/m36-outbox-claim-repro-fixed.log`). The test's assertions are unchanged; production polls every second and was
  never affected.

### Restart check

New `infra/smoke/media_m36_restart.py`, run by `check-media-m29.ps1 -Milestone 36` (wrapper
`check-media-m36.ps1`), starts from a fresh disposable project and then restarts it with its volumes retained
(`compose down --timeout 15` without `--volumes`, then `up -d --wait`). It prints one `MEDIA_M36_RESTART_PASS` line
per check, and on failure the number of checks passed and a reason that never includes response bodies:

1. Fresh Flyway history matches the packaged migrations (Core and Media).
2. M29 playback and ingress hashes recorded.
3. A retained Premium viewer registered.
4. READY assets and Core candidates snapshotted with zero unsent outbox events.
5. Outbox, Kafka and Core viewer counts snapshotted.
6. Restart with retained volumes; ingress health restored.
7. Flyway history unchanged after the restart (no migration rerun).
8. Core accounts, active sessions and playback counts retained.
9. Media READY version, zero unsent events and SENT counts retained.
10. Kafka end offsets and Core's committed offsets for `core-media-projection-v1` retained, with nothing republished.
11. Core READY candidate versions retained.
12. The retained viewer logs in and is admitted to a new playback.
13. Master playlist, rendition and every segment hash retained.
14. A new ticket's playback decoded through the ingress.
15. The pre-restart ticket is honored only within its lifetime: more than 10 s before its `exp` it must return the
    same bytes as before the restart; more than 10 s after, a 401 `AUTHENTICATION_REQUIRED` or 403 `ACCESS_DENIED`
    problem; within 10 s of `exp` either answer (Media compares with the Docker VM's clock).
16. `libra_core` and `libra_media` connect to their own databases and are refused by each other's
    (`libra_recommendation` is not probed).

### Review against the original scope

The plan's eleven consolidated acceptance scenarios (`.claude/logs/2026-09-24-media-service-plan.md`, "Consolidated
acceptance scenarios") against the milestone evidence. "Gate" marks scenarios whose runners ran again in the final
M36 acceptance gate; the rest rest on their milestone's run and on the integration suites, which the gate reran.

| # | Scenario | Evidence | Real dependencies | Gate | Gaps |
|---|---|---|---|---|---|
| 1 | Upload → READY → publication → entitled playback and seeking | M29 slice (`media_m29_http.py`), M33 browser spec | Compose, FFmpeg, Chrome + hls.js | yes | One seek, in M33 only, on a 12 s clip |
| 2 | No upscaling; silent input and rotation policy | `FfmpegTranscodeIntegrationTest` (silent low-resolution, rotated sources), `SourcePolicyTest` | real FFmpeg | suites | Synthetic fixtures; no phone or camera files |
| 3 | Invalid size/checksum/format, corrupt input, partial output never READY | `SourceFreezingIntegrationTest`, `FfprobeFixtureIntegrationTest`, `FinalizationConsistencyIntegrationTest`, `OutputFinalizationIntegrationTest`; M34 F15 | PostgreSQL, SeaweedFS, FFmpeg | suites, M34 | Tolerance policy mostly synthetic |
| 4 | Concurrent or repeated create/complete → one upload/job | Media `UploadControlIntegrationTest`, `MediaPersistenceIntegrationTest`; Core `UploadIntentIntegrationTest`; M34 F12 | PostgreSQL | suites, M34 | The concurrent-complete race uses fake storage (M14) |
| 5 | Uncertain Core→Media response recoverable with the same intent | `UploadProvisioningIntegrationTest`, `MediaControlClientTest`; M09/M10 and M11/M14 fault-proxy runners; M34 F1 | real services, transport faults | suites, M34 | M09–M14 runners not rerun |
| 6 | Overwriting staging after freezing cannot change source or HLS | `SourceFreezingIntegrationTest` (overwrite and delete after freeze; presigned write to a frozen key 403), `SourceSnapshotRecoveryIntegrationTest` | PostgreSQL, SeaweedFS | suites | **Partial**: no test overwrites staging and then compares HLS bytes; no object lock |
| 7 | Restart during processing recovers; expired lease cannot finalize | `WorkerProcessRecoveryIntegrationTest`, `JobRecoveryIntegrationTest`, `FinalizationConsistencyIntegrationTest`; M34 F3, F5 | packaged JAR killed, PostgreSQL | suites, M34 | Coarse kill points |
| 8 | Kafka outage loses no readiness event; duplicates harmless | `OutboxPublicationIntegrationTest`, `OutboxDeliveryHardeningIntegrationTest`, `OutboxProcessRecoveryIntegrationTest`; Core `CatalogProfilesIntegrationTest`; M34 F6, F7; M36 check 10 | Kafka 4.3.1 | yes | One broker, at-least-once; H8 showed Compose itself lost the log on recreation |
| 9 | Missing, expired, tampered, wrong-binding tickets rejected | `PlaybackTicketVerifierTest`, `ProtectedStreamServerIntegrationTest`, `ProtectedStreamIntegrationTest`; M29 phase 2; M30 expiry; M36 check 15 | real sockets; Core's real signer in the smokes | yes | Suites sign with a fixture mirroring Core; no revocation before `exp` |
| 10 | Direct storage, filer and alternate routes cannot bypass authorization | M06 port/path probe, `ingress_m30_paths.py`, M29 `/libra-hls` and `/s3/` 404 | Compose, SeaweedFS | M30 paths | The M06 storage-port probe was not rerun after later `nginx.conf` changes |
| 11 | Browser CORS upload and HLS decoding with real services | M11 browser preflight runner, M33 spec | Chrome, SeaweedFS, Compose | yes | One browser, HTTP loopback, one clip |

No scenario lacks evidence. Scenario 6 is partial and scenario 10's storage-port probe is from M06; both are recorded
under Limits rather than reopened, because neither M36 change touches source freezing or the storage ports.

Contracts: M36 changes none. The versioned contracts under `contracts/` stay as M35 listed them, with
`core-media-uploads` 1.1.0, `media-internal-uploads` 1.1.0, `media-streams` 1.3.0 and the event schema
`media-asset-state` (wire `schemaVersion` 1); `check_contracts.py` passed in the final gate.

Service and database ownership: three services, each with its own database and role (`libra_core`, `libra_media`,
`libra_recommendation`) and its own Flyway history. H9 enforces that ownership at connect time for the service
databases instead of only by configuration; M36 check 16 verifies it for Core and Media on a fresh project.

Secret handling: no secret is committed. The smokes generate keys into a disposable env file per run, the evidence
gate deletes secret-shaped JSON before upload (H5 widened it), and the M36 driver prints exception type names, never
response bodies or subprocess output, on failure. Media objects stay private: every playback in the gate went
through Core's ticket and the ingress.

Earlier findings: the open items carried by M35's Limits were the outbox setup claim (fixed above), the acceptance
smokes outside any gate (H7) and remote CI (still open). The other M20–M35 limits are unchanged and listed in the M35
handoff reference.

## Gate

No unresolved high-impact correctness or security issue and no failed or skipped required check remains in the
claimed local slice. Retained data survives a supported restart (`down` without `--volumes`, then `up`). All final
evidence identifies the exact tested revision and state, directly (`toolchain.json`) or through the run identity.

## Verification

The final gate ran once on the final tree, with nothing edited before or during it:

```powershell
pwsh -NoProfile -File infra/ci/verify-local.ps1 -Python tmp/ci-venv/Scripts/python.exe -Offline -Acceptance
```

It ran from 09:08 to 09:49 on 2026-11-02 (`tmp/m36-verify-acceptance.log`, Maven output
`tmp/verify-local-maven.log`), exited 0 and printed
`PASS: LIBRA local verification reproduced CI order; commit=a4e0fe700be5fa45fdfb97755ec547466c4ef6c1 dirty=True`.

- **Revision.** HEAD `a4e0fe7` on `features/media` plus the 20 uncommitted M36 paths: `dirty` true,
  `changedPaths` 20, `stateSha256` `5b42006fac6b00b6ea0a6fc0be7ce2f809e7ce26414afe8fa26a36d806dfd5f7`. The start
  capture, `toolchain.json` and the evidence gate's recomputation agreed, and `record_toolchain.py --revision` run
  after the gate printed the same record. This doc and the README paragraph were written afterwards, so the committed
  tree differs from the tested one only by these two documentation files.
- **Run identity.** `21bc7be0decf4f8189e83e2613367ede`, in `tests.json` and in all eight smoke evidence files.
- The 109 verification-gate unit tests passed (71 in M35).
- Toolchain: Java 21.0.10, Docker 29.8.2, Compose v5.6.0, FFmpeg and ffprobe 9.0.2, Python 3.13.14, Maven wrapper
  3.3.4 with Maven 3.9.16.
- Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 13:02 min).
- `check_test_reports.py` passed 93 suites and 697 tests with zero failures, errors or skips, and wrote
  `TESTS_PASS`:
  - Core 237 (93 Surefire in 13 suites, 144 Failsafe in 14);
  - Media 458 (244 Surefire in 37 suites, 214 Failsafe in 27), including `OutboxProcessRecoveryIntegrationTest`
    with the new setup claim;
  - recommendation and analytics 2.
  The counts equal M35's: M36 changed one test's setup and added none.
- `check_contracts.py` passed 22 checks (5 event schemas, 14 HTTP contracts, 3 examples), and the Compose template
  passed `config --quiet`.
- Smokes, each in a fresh disposable project, with this run's identity:

  | Evidence | Result | Checks |
  |---|---|---|
  | `smoke.json` (Core) | PASS | 11 |
  | `ingress-m29.json` | `INGRESS_M29_PASS` | 6 |
  | `media-m29.json` (accepted flow) | `MEDIA_M29_SMOKE_PASS` | 10 |
  | `media-m30.json` (abuse matrix, waits out a 5 min ticket) | `MEDIA_M30_SMOKE_PASS` | 15 |
  | `ingress-m30.json` (path spellings) | `INGRESS_M30_PATHS_PASS` | 3 |
  | `media-m33.json` (Chrome + hls.js) | `MEDIA_M33_BROWSER_PASS` | 9 |
  | `media-m34.json` (fault matrix) | `MEDIA_M34_FAULTS_PASS` | 21 |
  | `media-m36.json` (restart) | `MEDIA_M36_RESTART_PASS` | 16 |

- `check_smoke_evidence.py --acceptance` printed `PASS: fresh sanitized Core and Media smoke evidence`: every file
  newer than both JARs and the run start, revision matching, no JSON matching a secret rule.
- After every smoke, `Assert-ComposeProjectRemoved` found nothing left of its project. After the run no container,
  volume or image of a disposable project remained. Three empty unlabeled volumes dated 2026-10-22 (left by the M34
  runs before H8) were removed by hand. The workstation's `libra-streaming` stack was not touched.
- `git diff --check` is clean (TypeScript excluded, see Limits).

Earlier M36 runs, kept in `tmp/`:

- `m36-restart-old-compose.log`, `m36-restart-diag.log`: the restart check on the old Compose file, failing at check
  10 (H8).
- `m36-restart-final.log`: the restart check alone on the final tree, 16 of 16.
- `m36-db-connect-repro.log` (H9), `m36-outbox-claim-repro-old.log` and `-fixed.log` (outbox setup),
  `m36-nodocker-storage-it.log`, `m36-noffmpeg-it.log`, `m36-cleanup-helper-check.log` (failure paths below).
- A first `-Acceptance` run on 2026-11-01 was stopped by the user during Maven; it left nothing behind and is not
  evidence.

Work was divided among agents. Two coding agents (Codex, gpt-6.1-sol) wrote the gate changes and the restart driver in
parallel on disjoint files; the main session ran every Docker, Maven and smoke run on the host. A code-reviewer agent
reviewed the full diff; its findings (an undefined variable and `$Error[0]` in `check-core.ps1`'s cleanup path, a
`require` thrown inside a wait loop in the offsets reader, a relaxed cookie rule, a blind `FAIL` line in the driver)
were fixed, and no high-impact finding remained. A doc-finder agent mapped the original acceptance scenarios to
evidence for the scope review.

### Failure paths of the verification tools

| Path | How it was exercised | Result |
|---|---|---|
| Missing report | `test_report_gate.py`: `test_requires_surefire_report`, `test_requires_each_media_suite_report`, `test_requires_core_failsafe_suite` | fails, names the suite |
| Skipped integration suite | `test_requires_executed_successful_integration_suite`, `test_unmapped_reports_enforce_all_failure_rules` | fails |
| Stale report / `tests.json` from another run | `test_stale_or_equal_report_fails`, `test_unmapped_report_must_be_fresh`; `test_smoke_evidence_gate.py`: `test_requires_matching_successful_test_evidence`, `test_jars_must_be_built_after_run_start` | fails |
| Stale PASS in `target/verification` | M35 `smoke-gate.json` found on disk; directory now deleted first | removed before revision capture |
| Absent Docker | `MediaStorageIntegrationTest` with Docker Desktop stopped (`tmp/m36-nodocker-storage-it.log`) | `IllegalStateException: Could not find a valid Docker environment`, 1 error, 0 skipped |
| Absent FFmpeg/ffprobe | Media FFmpeg and ffprobe suites with both tools unresolvable (`tmp/m36-noffmpeg-it.log`) | 22 tests: 4 failures, 18 errors, 0 skipped |
| Absent storage | covered by the Docker case: storage is Testcontainers-only | error, not skip |
| Failed smoke cleanup | helper against a project with one leftover volume (`tmp/m36-cleanup-helper-check.log`) | `1 volume(s) remain`, PASS after removal |
| Tree changed during the run | `test_revision_change_fails`, `test_revision_lookup_failure_fails_closed`; `test_record_toolchain.py` revision cases | fails |
| Secret-shaped evidence | `test_rejects_sigv4_*`, `test_rejects_*_hex_secret`, `test_only_exact_revision_members_are_exempt_from_long_hex` | file deleted, gate fails |

The gate unit tests grew from 71 to 109.

## Evidence classes

- **Local evidence.** Everything above: one Windows 11 workstation, Docker Desktop, the packaged JARs, the real
  `infra/compose.yaml` and `infra/nginx/nginx.conf`, real PostgreSQL, Kafka, SeaweedFS and FFmpeg.
- **Browser evidence.** The M33 slice, rerun here inside the acceptance gate: installed Chrome through Playwright,
  real upload with CORS, real hls.js decoding. One browser, one OS.
- **Remote CI.** Not run. `.github/workflows/core-ci.yml` gained one step; it has never run on GitHub's runners.
- **Deployment.** Not verified. There is no deployed environment; nothing here proves behavior behind a real domain,
  TLS, a CDN, multiple instances or managed storage.

## Limits

- **Remote CI not run** (as in M35). The acceptance smokes are not in the workflow; they run only through
  `verify-local.ps1 -Acceptance` (the M30 matrix waits out a 5 min ticket, M33 needs installed Chrome).
- **Workstation stack.** The existing `libra-streaming` Compose project predates H8 and H9. Its next `up` recreates
  Kafka: the broker data that lived in the old container's `/tmp` is lost once, and its three old anonymous volumes
  must be removed by hand. Its `postgres-data` volume was initialized with the old SQL, so `PUBLIC` keeps `CONNECT`
  there until the `REVOKE` statements are run by hand or the volume is recreated. Init scripts run only on an empty
  data directory.
- **`diff --check` blind spot.** `.gitattributes` marks `*.ts` as binary, so `git diff --check` and readable diffs
  skip TypeScript files.
- **Revision record.** `stateSha256` hashes git's view of the tree. Ignored files (for example `.env`, `tmp/`) are
  outside it by design; a change to an ignored file that the build reads would not change the revision. No test
  covers `core.autocrlf=true` checkouts.
- **Scope gaps carried, not reopened.** Scenario 6 has no test that overwrites staging after freezing and then
  compares HLS bytes, and storage has no object lock. Scenario 10's direct storage-port probe (`check-media-m06.ps1`)
  dates from M06 and was not rerun after the later `nginx.conf` changes; the M29/M30 ingress checks cover only routes
  through the ingress. The M09–M14 fault-proxy runners for scenario 5 were not rerun either. Seeking is checked once,
  in the browser slice.
- **Evidence linkage, not self-description.** Only `toolchain.json` holds the revision; the test and smoke files
  link to it through `runId`. The gate's own summaries, `smoke-gate.json` and `contracts.json`, carry neither, so
  either file read on its own does not say which tree it describes.
- **Secret-scan exemption by key name.** The long-hex rule exempts any `"runId"`, `"commit"` or `"stateSha256"`
  member of the right shape in any evidence file, not only where the gate writes them. A future writer that put a
  secret under one of those names would pass the rule.
- **Standalone report gate.** Run without `LIBRA_VERIFY_STARTED_AT`, `check_test_reports.py` skips its freshness
  check and omits `runId`/`startedAt`; the evidence gate then rejects that `tests.json`, so the full sequence still
  fails closed.
- **Connect checks.** The `REVOKE` covers the three service databases only; `PUBLIC` keeps its default access to
  `postgres` and `template1`. Check 16 probes Core and Media, not `libra_recommendation`.
- **Ticket on a local command line.** The M36 driver passes the playback ticket to the host FFmpeg as an argument, so
  it is visible in the workstation's process list for the few seconds of that playback.
- **No PowerShell tests.** The runners and `assert-compose-removed.ps1` are exercised only by running them.
- **FFmpeg lookup.** The toolchain preflight and the test fixtures resolve FFmpeg slightly differently; both fail
  closed when it is absent.
- **Heuristic secret scan, one broker, one instance per service, coarse crash points** (unchanged from M35).
- **Scope after M36.** Per the plan, multipart/resumable upload, long videos, multi-rendition adaptation, replacing an
  active asset, manual retry after exhaustion, product UI, CDN/DRM, production rollout and automatic deletion of READY
  assets remain separate future decisions.
- **Next authorized step.** None is implied. The change is left uncommitted for the user's review; commit, push,
  remote CI and any deployment need the user's instruction.
