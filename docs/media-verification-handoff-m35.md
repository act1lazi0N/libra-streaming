# Media: Verification and handoff (Milestone 35)

Verification date: 2026-10-26.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was linted, not run remotely).

## Scope

M35 turns the verification that earlier milestones ran by hand into one repeatable sequence that fails closed, and
writes the handoff reference for operating the Media slice. It adds no service behavior: no Java, contract or Flyway
change.

- **Report gate.** `infra/ci/check_test_reports.py` already required every `*Test`/`*IntegrationTest` source to have
  an executed, passing, non-skipped report. It now also fails unless both Core and Media have executed Failsafe suites
  and four named real-dependency Media suites are present and passing: `FfprobeFixtureIntegrationTest` (real
  ffprobe), `FfmpegTranscodeIntegrationTest` (real FFmpeg), `ProtectedStreamServerIntegrationTest` (real sockets) and
  `MediaStorageIntegrationTest` (Testcontainers storage). Deleting or renaming one fails the gate instead of silently
  shrinking it. `tests.json` adds suite and test counts per module and lane.
- **Smoke evidence gate.** New `infra/ci/check_smoke_evidence.py` runs after the smokes. It first scans every
  `target/verification/*.json` for secret-shaped content (PEM blocks, bearer tokens, JWTs, cookie headers, presigned
  S3 query parameters, long mixed-case base64 runs). It deletes any file that matches before failing, so the
  always-run artifact upload cannot publish it. It then requires `smoke.json`, `ingress-m29.json` and
  `media-m29.json`, each with the result and non-empty checks its writer produces, a `runId` equal to this run's
  `LIBRA_VERIFY_RUN_ID`, and a modification time newer than both packaged JARs. It also requires `toolchain.json`,
  newer than the JARs. A PASS left by an earlier run cannot satisfy it.
- **Toolchain record.** New `infra/ci/record_toolchain.py` writes `toolchain.json` with version strings only. It covers
  Java (from `JAVA_HOME`, which the Maven wrapper uses), Docker, Compose, FFmpeg and ffprobe (from `LIBRA_FFMPEG_PATH`
  and `LIBRA_FFPROBE_PATH`, else `PATH`) and Python. It adds the Maven wrapper's version and its distribution URL with
  any user info stripped. JVM `Picked up ...` option lines are skipped. A missing tool fails it. It runs after Maven,
  because the root `clean` deletes `target/`.
- **Stable Media smoke.** New `infra/smoke/check-media.ps1` is the accepted-flow smoke used by CI and the handoff. It
  checks that Python, FFmpeg and ffprobe are present, then runs `check-media-m29.ps1 -Milestone 29`:
  - fresh project name, generated keys and an empty env file;
  - the real `infra/compose.yaml` full profile and `infra/nginx/nginx.conf` with the packaged JARs;
  - a real upload, worker transcode, Kafka projection, publication and ticketed playback;
  - removal of that project's containers, volumes and images, and nothing else.
  The smoke drivers write the run identity into their evidence. On the host, `media_m29_http.py` now plays and
  probes with the FFmpeg and ffprobe that the toolchain record names. The Media container transcodes with the FFmpeg
  of its own image, which the record does not cover (see Limits).
- **Local reproduction.** New `infra/ci/verify-local.ps1` runs the CI job's steps in CI order and stops at the first
  failure. The steps are:
  1. Docker and Compose;
  2. the gate unit tests and a toolchain preflight, so a missing FFmpeg fails before the build;
  3. root `mvnw -B -ntp clean verify` (`-Offline` adds `-o`; output goes to the ignored `tmp/verify-local-maven.log`);
  4. the toolchain record;
  5. the report gate and the contract gate;
  6. the Compose template;
  7. the Core smoke and the Media smoke;
  8. the evidence gate.
  No switch skips a step. The script generates the run identity. During Maven it clears the workstation's `CORE_*`,
  `MEDIA_*`, `SPRING_*`, `KAFKA_*`, `RECOMMENDATION_*` and `LIBRA_*` variables, except the two tool paths and the run
  identity, so that they cannot override what the tests configure. The `-Python` interpreter must already have
  `infra/ci/requirements.txt`.
- **CI lane.** `.github/workflows/core-ci.yml` (now `LIBRA CI`, job `Core and Media verification`, 80 min) keeps
  every Core step and pin. It installs FFmpeg from the ubuntu-24.04 archive and asserts major version 6. It records
  the toolchain after Maven, runs the Media smoke after the Core smoke, and always runs the evidence gate before the
  artifact upload. Step timeouts are 10 min for apt, 35 min for Maven and 15 min for each smoke. When Maven fails,
  the job prints the report gate's diagnosis and deletes `tests.json`, so the artifact never holds a PASS summary of a
  failed build.
- **Smoke cleanup.** `check-core.ps1` now also removes the image it builds (`--rmi local`), as the Media runner
  already did; earlier runs had left one `libra-smoke-*-core` image each.
- **Documentation.** The handoff reference below. M35 also corrected three stale statements:
  `docs/core-configuration.md` now says Flyway V1–V8, and `docs/core-playback.md` and the README no longer describe
  Media HLS delivery as future work.

## Gate

A fresh local run builds the packaged services and reproduces the accepted flow through the real ingress, with no
workstation secret file, no manual READY injection and no skipped dependency test. Every gate fails closed on missing,
failed, skipped or stale evidence, and on evidence with secret-shaped content.

## Reproduction

From the repository root, with Docker running, Java 21, PowerShell 7, FFmpeg/ffprobe on `PATH` (or in
`LIBRA_FFMPEG_PATH`/`LIBRA_FFPROBE_PATH`) and a Python that has `infra/ci/requirements.txt`:

```powershell
pwsh -File infra/ci/verify-local.ps1 -Python python
```

Add `-Offline` once the Maven repository is populated. After a `clean verify`, the smokes can also run on their own:

```powershell
pwsh -File infra/smoke/check-core.ps1 -Python python
pwsh -File infra/smoke/check-media.ps1 -Python python
```

The evidence gate compares the run identity the smokes wrote with `LIBRA_VERIFY_RUN_ID`. Outside `verify-local.ps1`,
set the same value for the smokes and for `check_smoke_evidence.py`.

## Verification

The final run on the final tree, `pwsh -File infra/ci/verify-local.ps1 -Python tmp/ci-venv/Scripts/python.exe
-Offline` (`tmp/m35-verify-local-confirm.log`, Maven output `tmp/m35-verify-local-confirm-maven.log`), printed
`PASS: LIBRA local verification reproduced CI order`:

- The 71 verification-gate unit tests passed: the report gate, the evidence gate, the toolchain record and the
  contract gates.
- The toolchain preflight and record passed: Java 21.0.10, Docker 29.8.2, Compose v5.6.0, FFmpeg and ffprobe 9.0.2,
  Python 3.13.14, Maven wrapper 3.3.4 with Maven 3.9.16.
- Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 12:58 min).
- `check_test_reports.py` passed 93 suites and 697 tests with zero failures, errors or skips, including the four
  required Media suites:
  - Core 237 (93 Surefire, 144 Failsafe);
  - Media 458 (244 Surefire, 214 Failsafe);
  - recommendation and analytics 2.
  These are M34's counts; M35 changed no Java.
- `check_contracts.py` passed 22 checks, and the Compose template passed `config --quiet`.
- The Core smoke printed 11 PASS lines. The Media smoke printed the 6 ingress lines (`INGRESS_M29_PASS`), the
  Media-down line and the 10 slice lines (`MEDIA_M29_SMOKE_PASS`), with a real upload, transcode, Kafka projection,
  publication and ticketed playback through `infra/nginx/nginx.conf`.
- `check_smoke_evidence.py` passed: three evidence files with 11, 6 and 10 checks, all carrying the run's identity
  and newer than the JARs, and no published JSON matched a secret rule.

Afterwards no container, volume or image of a disposable project remained. The workstation's own `libra-streaming`
stack was not touched. `actionlint` passed the workflow, and `git diff --check` is clean.

Earlier runs, kept in `tmp/`:

- Run 1 (`m35-verify-local-run1.log`) passed on the first draft. It showed that `toolchain.json` was deleted by the
  root `clean` it ran before.
- A run with Docker Desktop stopped (`m35-verify-local-nodocker.log`) failed at once with `Docker is required` and ran
  nothing else.
- Run 2 (`m35-verify-local-final.log`) passed after the round-2 fixes. It left three `libra-smoke-*-core` images
  behind (one dated from M34), which were removed. That led to the `--rmi local` change.

Work was divided among agents. Codex (gpt-6.1-sol), through the coding agent, wrote the gates, scripts and workflow in
three rounds. A second Codex task compiled the handoff reference from the source, and the main session spot-checked
it. Codex's sandbox cannot reach Docker, so the main session ran every Maven and smoke run on the host. A code-reviewer
agent reviewed twice. Its findings and their outcome:

- Secret-shaped evidence was still uploaded when the gate failed. The gate now deletes it, scans every uploaded JSON,
  and runs with `always()` before the upload.
- `toolchain.json` was deleted by `clean`. It is now recorded after Maven and required by the evidence gate.
- `JAVA_TOOL_OPTIONS` and `NOTE: Picked up JDK_JAVA_OPTIONS` lines, and user info, query or fragment in the wrapper
  URL, could reach the record. All are dropped.
- Workstation variables could change the build. Six prefixes are now cleared during Maven.
- The recorded FFmpeg differed from the FFmpeg used. The host side now agrees with the tests' own lookup; the Media
  image's FFmpeg is a documented limit.
- Freshness relied only on mtime. A run identity is now required as well.
- Further findings and outcomes:
  - FFmpeg is now preflighted;
  - the major version is asserted;
  - step timeouts sit inside an 80 min job;
  - the base64, Bearer and presigned-URL rules were corrected and tested against the real check strings;
  - a file error no longer stops the scan;
  - `tests.json` is deleted after a failed Maven run.
- Not taken: rerunning the M30, M33 and M34 smokes in CI, and pinning the Media base image by digest (both under
  Limits). The renamed job also needs any branch-protection rule that names `Core verification` updated.

## Handoff reference

The tables below were compiled from the source, with spot checks against it. Each row cites its source.

### 1. Configuration

#### Media application environment inventory

All named environment placeholders below come from [services/media/src/main/resources/application.yml][my]. No other `application*.yml`, `.yaml`, or `.properties` exists under Media's main resources. Defaults mean **application defaults**, not Compose values. `empty` means the explicit empty-string fallback; `none` means no fallback. “Required” means a usable value must exist when that component is active; a valid listed default need not be overridden. Configuration-property groups are `libra.media.storage`, `core-binding`, `core-service`, `playback`, and `delivery`, bound by their corresponding `*Properties` records. Worker/outbox/lifecycle/tool settings instead use `@Value` and conditional configuration. [my][storage-config][binding][service-security][ticket-security][delivery-config][worker-config][outbox-config][lifecycle-config][probe-config][transcode-config]

| Variable | Application default | Required / startup rejection or behavior | Validation source (defaults: [application.yml][my]) |
| --- | --- | --- | --- |
| `MEDIA_DB_URL` | none | Always; unresolved/missing DB configuration or unusable DB prevents datasource/Flyway/schema startup. | [runtime M05][m05], [my] |
| `MEDIA_DB_USERNAME` | none | Always; no application fallback; failed DB authentication prevents startup. | [m05], [my] |
| `MEDIA_DB_PASSWORD` | none | Always; no application fallback; failed DB authentication prevents startup. | [m05], [my] |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Required by enabled outbox publisher; blank explicitly rejected; Kafka producer construction validates its configuration. Broker delivery failures are retried at runtime. | [KafkaEventPublisher][publisher], [OutboxRelay][relay] |
| `MEDIA_PORT` | `8082` | Default supplied; invalid port binding/listener configuration prevents server startup; no bespoke Media port validator found. | [my] `server.port` |
| `MEDIA_WORKER_ENABLED` | `false` | Optional switch. Enabled worker requires a `MediaJobHandler`; production finalizer is installed with the transcode executable. Missing handler prevents bean creation. | [worker-config], [finalizer-config] |
| `MEDIA_WORKER_POLL_INTERVAL` | `1s` | Enabled worker: 10 ms–1 min, otherwise `Invalid worker timing`. | [worker] `Settings` |
| `MEDIA_WORKER_LEASE_DURATION` | `30s` | Enabled worker: 100 ms–5 min, otherwise startup fails. | [worker] `Settings` |
| `MEDIA_WORKER_RENEW_INTERVAL` | `5s` | Enabled worker: 10 ms–lease/3, otherwise startup fails. | [worker] `Settings` |
| `MEDIA_WORKER_RETRY_DELAY` | `10s` | Enabled worker: 10 ms–1 h, otherwise startup fails. | [worker] `Settings` |
| `MEDIA_WORKER_SHUTDOWN_TIMEOUT` | `10s` | Enabled worker: 10 ms–30 s, otherwise startup fails. | [worker] `Settings` |
| `MEDIA_OUTBOX_ENABLED` | `false` | Optional switch; enables relay/settings/producer beans. | [outbox-config] |
| `MEDIA_OUTBOX_POLL_INTERVAL` | `1s` | Enabled relay: 10 ms–1 min, otherwise `Invalid outbox timing`. | [relay] `Settings` |
| `MEDIA_OUTBOX_LEASE_DURATION` | `30s` | Enabled relay: at least 2 × send timeout, at most 5 min. | [relay] `Settings` |
| `MEDIA_OUTBOX_SEND_TIMEOUT` | `10s` | Enabled relay: 100 ms–1 min. | [relay] `Settings` |
| `MEDIA_OUTBOX_MAX_RETRY_DELAY` | `5m` | Enabled relay: 1 s–1 h. | [relay] `Settings` |
| `MEDIA_OUTBOX_SHUTDOWN_TIMEOUT` | `10s` | Enabled relay: 10 ms–30 s. | [relay] `Settings` |
| `MEDIA_LIFECYCLE_ENABLED` | `false` | Optional switch; enables cleanup and sweeper beans. | [lifecycle-config] |
| `MEDIA_LIFECYCLE_POLL_INTERVAL` | `1m` | Enabled sweeper: 1 s–1 h, otherwise `Invalid lifecycle timing`. | [sweeper] `Settings` |
| `MEDIA_LIFECYCLE_BATCH_SIZE` | `20` | Enabled sweeper: 1–500, otherwise `Invalid lifecycle batch`. | [sweeper] `Settings` |
| `MEDIA_LIFECYCLE_WRITE_GRACE` | `1h` | Enabled sweeper: 5 min–7 days. Operationally must outlast admitted writes; startup cannot prove that. | [sweeper] `Settings`, [m32] Limits |
| `MEDIA_LIFECYCLE_MAX_OBJECTS_PER_PREFIX` | `1000` | Enabled cleanup: 1–10000, otherwise `Invalid cleanup batch`. | [cleanup] constructor |
| `MEDIA_LIFECYCLE_SHUTDOWN_TIMEOUT` | `10s` | Enabled sweeper: 10 ms–30 s. | [sweeper] `Settings` |
| `MEDIA_FFPROBE_PATH` | empty | Nonempty enables probe stage; must be an absolute regular executable file. Required for configured transcoder. Invalid file fails startup. | [probe-config], [transcode-config] |
| `MEDIA_FFPROBE_TIMEOUT` | `20s` | Configured probe: positive, ≤5 min. | [prober] constructor |
| `MEDIA_FFPROBE_MAX_OUTPUT_BYTES` | `262144` | Configured probe: 1024–4194304 bytes. | [prober] constructor |
| `MEDIA_FFMPEG_PATH` | empty | Nonempty enables transcoder and finalizer; absolute regular executable file and configured probe required. Needed for production worker handler. | [transcode-config], [finalizer-config], [worker-config] |
| `MEDIA_FFMPEG_TIMEOUT` | `15m` | Configured transcoder: positive, ≤1 h. | [transcoder] constructor |
| `MEDIA_FFMPEG_THREADS` | `2` | Configured transcoder: 1–16. | [transcoder] constructor |
| `MEDIA_FFMPEG_MAX_OUTPUT_BYTES` | `314572800` | Configured transcoder: 1048576–1073741824 bytes. | [transcoder] constructor |
| `MEDIA_CORE_BINDING_ENABLED` | `false` | Optional switch. Disabled binding lookup returns `CORE_UNAVAILABLE`; does not prevent Media startup. | [binding] `requireCurrent` |
| `MEDIA_CORE_BINDING_BASE_URL` | empty | Required when enabled: HTTP(S) origin, host, no credentials/query/fragment, path empty or `/`; HTTP needs allow flag. Invalid origin fails property construction. | [binding-props] |
| `MEDIA_CORE_BINDING_PRIVATE_KEY` | empty | Enabled: Base64 PKCS#8 RSA DER, matching public key; missing/malformed/weak/mismatched material fails startup. | [binding-props], [binding] constructor |
| `MEDIA_CORE_BINDING_PUBLIC_KEY` | empty | Enabled: Base64 X.509 RSA DER, ≥2048 bits, matches private key, distinct from enabled inbound Core service key. | [binding] constructor |
| `MEDIA_CORE_BINDING_KEY_ID` | empty | Enabled: `[A-Za-z0-9_-]{1,64}`, otherwise startup fails. | [binding-props] |
| `MEDIA_CORE_BINDING_ALLOW_HTTP` | `false` | Optional; false rejects an enabled HTTP binding origin. | [binding-props] |
| `MEDIA_CORE_SERVICE_AUTH_ENABLED` | `false` | Optional; disabled verifier rejects supplied credentials at runtime. | [service-security] `decoder` |
| `MEDIA_CORE_SERVICE_PUBLIC_KEY` | empty | Enabled: required Base64 X.509 RSA public DER, ≥2048 bits; missing/malformed/weak fails startup. | [service-props], [service-security] |
| `MEDIA_CORE_SERVICE_KEY_ID` | empty | Enabled: required `[A-Za-z0-9_-]{1,64}`. | [service-props] |
| `MEDIA_PLAYBACK_ENABLED` | `false` | Optional; disabled playback verifier rejects tickets (401), while delivery-limit bean still validates. | [ticket-security], [delivery-config] |
| `MEDIA_PLAYBACK_PUBLIC_KEY` | empty | Enabled: required Base64 X.509 RSA public DER, ≥2048 bits; malformed/weak fails startup. | [ticket-props], [ticket-security] |
| `MEDIA_PLAYBACK_KEY_ID` | empty | Enabled: required `[A-Za-z0-9_-]{1,64}`. | [ticket-props] |
| `MEDIA_PLAYBACK_SECURE_COOKIE` | `true` | Optional; chooses cookie name. Must agree with Core cookie setting; mismatch causes runtime 401, with no cross-service startup check. | [ticket-props] `cookieName`, [tickets] `cookieName` |
| `MEDIA_DELIVERY_MAX_STREAMS` | `64` | Always validated: ≥1. Invalid delivery limits fail bean creation even if playback disabled. | [delivery-config], [delivery] `Limits` |
| `MEDIA_DELIVERY_MAX_STREAMS_PER_SESSION` | `4` | Always: ≥1 and ≤max streams. | [delivery] `Limits` |
| `MEDIA_DELIVERY_TRANSFER_TIMEOUT` | `60s` | Always: 1 s–10 min. | [delivery] `Limits` |
| `MEDIA_STORAGE_ENABLED` | `true` | Optional switch; disabled skips storage beans/startup checks, supplies no fallback store. | [storage-config], [m05] |
| `MEDIA_S3_INTERNAL_ENDPOINT` | empty | Storage enabled: required HTTP(S) origin, valid host/port, no credentials/query/fragment, path empty or `/`; HTTP requires allow flag. | [storage-props] `endpoint` |
| `MEDIA_S3_BROWSER_ENDPOINT` | empty | Storage enabled: same origin rules; required even though it is only used for browser signing. | [storage-props], [storage-config] |
| `MEDIA_BROWSER_ORIGIN` | empty | Storage enabled: required origin; no path (including `/`); HTTP requires explicit port and allow flag. | [storage-props] `origin` |
| `MEDIA_S3_REGION` | empty | Storage enabled: required nonblank, trimmed string. | [storage-props] `required` |
| `MEDIA_S3_ACCESS_KEY` | empty | Storage enabled: required `[A-Za-z0-9_+=/-]{3,128}`, different from secret. | [storage-props] |
| `MEDIA_S3_SECRET_KEY` | empty | Storage enabled: required `[A-Za-z0-9_+=/-]{16,256}`, different from access key. | [storage-props] |
| `MEDIA_SOURCE_BUCKET` | empty | Storage enabled: required 3–63 lowercase/digit/dot/hyphen name, alphanumeric ends, no `..`; distinct from HLS bucket. Layout mismatch also fails startup. | [storage-props], [layout] |
| `MEDIA_HLS_BUCKET` | empty | Storage enabled: same bucket rules; distinct from source bucket; layout mismatch fails. | [storage-props], [layout] |
| `MEDIA_STAGING_PREFIX` | `staging/` | Validated by upload store even with storage disabled; lowercase prefix grammar, no `//`. Storage validation additionally caps at 255 characters and disallows overlap with source prefix. Layout mismatch fails. | [upload-store], [storage-props], [layout] |
| `MEDIA_FROZEN_PREFIX` | empty | Storage enabled: required source prefix, ≤255 chars, `[a-z0-9][a-z0-9/_-]*/`, no `//`, no overlap with staging. Layout mismatch fails. | [storage-props], [layout] |
| `MEDIA_HLS_PREFIX` | empty | Storage enabled: same prefix grammar/length; layout mismatch fails. | [storage-props], [layout] |
| `MEDIA_S3_CONNECT_TIMEOUT` | empty | Storage enabled: required duration, 1–30 s. | [storage-props] |
| `MEDIA_S3_REQUEST_TIMEOUT` | empty | Storage enabled: required duration, ≥connect timeout and ≤5 min. | [storage-props] |
| `MEDIA_MAX_OBJECT_BYTES` | `0` | Storage enabled: must override to 1–268435456; default 0 fails startup. | [storage-props] |
| `MEDIA_SCRATCH_DIRECTORY` | empty | Storage enabled: required absolute path; link root/unwritable directory or failed scratch setup prevents startup. | [storage-props], [scratch], [storage-config] |
| `MEDIA_S3_ALLOW_HTTP` | `false` | Optional; false rejects HTTP endpoints/origin. | [storage-props] |
| `MEDIA_STORAGE_INITIALIZE_BUCKETS` | `false` | Optional; true creates missing buckets and installs source CORS. Both buckets are HEAD-checked on every storage-enabled startup, even when false; failed storage access fails startup. | [storage-startup] |
| `MEDIA_STORAGE_PROBE_ON_STARTUP` | `false` | Optional; true adds write/HEAD/read/compare/delete probe. Probe failure prevents successful startup. | [storage-startup] |

Storage validation throws `IllegalArgumentException("Media storage configuration: ...")`; bucket connectivity/permission checks occur in `MediaStorageStartup`, independently of the probe flag. Layout records bucket/prefix choices in PostgreSQL and refuses changed values before adapters/worker/sweeper use them. Duration/integer conversion errors also prevent construction of the consuming settings. Executable validation checks the path; a successful tool `-version` is not an explicit startup requirement (`ToolProcesses.version` can return `unavailable`). [storage-props][storage-startup][layout][worker-config][lifecycle-config][probe-config][transcode-config][tools]

Additional direct environment read outside the requested YAML/property inventory: `SystemRoot` is copied, if present, into the otherwise cleared tool subprocess environment on Windows; no default or explicit startup-required check exists. Test-only `LIBRA_FFPROBE_PATH`/`LIBRA_FFMPEG_PATH` in README are not production `MEDIA_*` configuration. [tools] `builder`, [README][readme] Verification

#### Core variables for Media integration

Defaults in this table are from [services/core/src/main/resources/application.yml][cy].

| Variable | Default | Required / startup behavior | Source |
| --- | --- | --- | --- |
| `CORE_MEDIA_CONTROL_ENABLED` | `false` | Optional; disabled adapter yields upload `MEDIA_UNAVAILABLE`, no startup refusal merely for disabling. | [control-client], [provisioning] |
| `CORE_MEDIA_CONTROL_BASE_URL` | empty | Enabled: required HTTP(S) origin, no credentials/query/fragment, path empty or `/`; outside explicit local development HTTPS required. | [control-props], [control-tokens] |
| `CORE_MEDIA_CONTROL_PRIVATE_KEY` | empty | Enabled: required Base64 PKCS#8 RSA private DER, matching public key; invalid pair fails startup. | [control-props], [control-tokens] |
| `CORE_MEDIA_CONTROL_PUBLIC_KEY` | empty | Enabled: Base64 X.509 public DER, ≥2048 bits; distinct from playback and enabled Analytics/inbound Media keys; invalid/reused pair fails startup. | [control-tokens] |
| `CORE_MEDIA_CONTROL_KEY_ID` | empty | Enabled: required `[A-Za-z0-9_-]{1,64}`. | [control-props] |
| `CORE_MEDIA_SERVICE_AUTH_ENABLED` | `false` | Optional; disabled inbound verifier rejects tokens. | [internal-security] |
| `CORE_MEDIA_SERVICE_PUBLIC_KEY` | empty | Enabled: required Base64 X.509 public RSA DER, ≥2048 bits, distinct from playback/enabled Analytics key. Invalid/missing/reused key fails startup. | [inbound-props], [internal-security] |
| `CORE_MEDIA_SERVICE_KEY_ID` | empty | Enabled: required `[A-Za-z0-9_-]{1,64}`. | [inbound-props] |
| `CORE_MEDIA_LISTENER_ENABLED` | `true` | Optional; controls listener auto-start; no custom value validator or missing-value refusal. | [listener] `@KafkaListener`, [cy] |
| `CORE_PLAYBACK_PRIVATE_KEY` | empty | Always required, no playback-enabled switch: Base64 PKCS#8 RSA DER, matching pair. | [playback-props], [tickets] constructor |
| `CORE_PLAYBACK_PUBLIC_KEY` | empty | Always required: Base64 X.509 public RSA DER, ≥2048 bits, matching modulus and verified signature; malformed/weak/mismatched fails startup. | [tickets] constructor |
| `CORE_PLAYBACK_KEY_ID` | empty | Always required `[A-Za-z0-9_-]{1,64}`. | [playback-props] |

#### What Compose actually interpolates

This is the complete `${...}` inventory for the requested services in [infra/compose.yaml][compose]; grouped names have the listed individual fallback. Empty/missing `SEAWEEDFS_*` causes Compose interpolation to fail (`:?`), before container startup. Literal environment entries are separate below. [compose]

| Service | Interpolated variables → fallback / destination |
| --- | --- |
| `core` | `CORE_JWT_KEY`, `CORE_MAIL_ENCRYPTION_KEY`, `CORE_PLAYBACK_PRIVATE_KEY`, `CORE_PLAYBACK_PUBLIC_KEY`, `CORE_PLAYBACK_KEY_ID`, `CORE_BOOTSTRAP_ADMIN_EMAIL`, `CORE_BOOTSTRAP_ADMIN_PASSWORD` → empty. [compose] |
| `core` | `CORE_LOCAL_DEVELOPMENT` → `false`; `CORE_COOKIE_SECURE` → `true`; `CORE_PUBLIC_BASE_URL` → `https://localhost`; `CORE_MEDIA_LISTENER_ENABLED`, `CORE_OUTBOX_PUBLISHER_ENABLED`, `CORE_DLT_LISTENER_ENABLED` → `true`. [compose] |
| `core` | `CORE_MEDIA_SERVICE_AUTH_ENABLED` → `false`; `CORE_MEDIA_SERVICE_PUBLIC_KEY`, `CORE_MEDIA_SERVICE_KEY_ID` → empty; `CORE_MEDIA_CONTROL_ENABLED` → `false`; `CORE_MEDIA_CONTROL_BASE_URL` → `http://media:8082`; `CORE_MEDIA_CONTROL_PRIVATE_KEY`, `CORE_MEDIA_CONTROL_PUBLIC_KEY`, `CORE_MEDIA_CONTROL_KEY_ID` → empty. [compose] |
| `core` | `CORE_ANALYTICS_ENABLED` → `false`; `CORE_ANALYTICS_BASE_URL` → `http://recommendation-analytics:8083`; `CORE_ANALYTICS_PRIVATE_KEY`, `CORE_ANALYTICS_PUBLIC_KEY`, `CORE_ANALYTICS_KEY_ID` → empty. [compose] |
| `media` | `MEDIA_CORE_SERVICE_AUTH_ENABLED` → `false`; **Core** `CORE_MEDIA_CONTROL_PUBLIC_KEY`, `CORE_MEDIA_CONTROL_KEY_ID` → empty, passed as `MEDIA_CORE_SERVICE_PUBLIC_KEY`, `MEDIA_CORE_SERVICE_KEY_ID`. [compose] |
| `media` | `MEDIA_PLAYBACK_ENABLED` → `false`; **Core** `CORE_PLAYBACK_PUBLIC_KEY`, `CORE_PLAYBACK_KEY_ID` → empty, passed as `MEDIA_PLAYBACK_PUBLIC_KEY`, `MEDIA_PLAYBACK_KEY_ID`; `CORE_COOKIE_SECURE` → `true`, passed as `MEDIA_PLAYBACK_SECURE_COOKIE`. [compose] |
| `media` | `MEDIA_CORE_BINDING_ENABLED` → `false`; `MEDIA_CORE_BINDING_PRIVATE_KEY`, `MEDIA_CORE_BINDING_PUBLIC_KEY`, `MEDIA_CORE_BINDING_KEY_ID` → empty. [compose] |
| `media` | `MEDIA_DELIVERY_MAX_STREAMS` → `6`; `MEDIA_DELIVERY_MAX_STREAMS_PER_SESSION` → `4`; `MEDIA_DELIVERY_TRANSFER_TIMEOUT` → `60s`; `MEDIA_S3_BROWSER_ENDPOINT` → `http://localhost:8333`; `MEDIA_BROWSER_ORIGIN` → `http://localhost:8080`; `MEDIA_STORAGE_INITIALIZE_BUCKETS` → `false`; `MEDIA_WORKER_ENABLED`, `MEDIA_OUTBOX_ENABLED`, `MEDIA_LIFECYCLE_ENABLED` → `true`. [compose] |
| `media` | `SEAWEEDFS_ACCESS_KEY`, `SEAWEEDFS_SECRET_KEY` → required, mapped to `MEDIA_S3_ACCESS_KEY`, `MEDIA_S3_SECRET_KEY`. [compose] |
| `seaweedfs` | Same required `SEAWEEDFS_ACCESS_KEY`, `SEAWEEDFS_SECRET_KEY` → `MEDIA_STORAGE_ACCESS_KEY`, `MEDIA_STORAGE_SECRET_KEY`. Entrypoint validates grammar/length/difference and exits with `STORAGE_CREDENTIAL_CONFIGURATION_INVALID` on invalid values. [compose], [start-private] |
| `nginx` | **None**; mounted configuration and loopback port are literal. [compose] |

Other dependency interpolation: `postgres` reads `POSTGRES_SUPERUSER_PASSWORD` → `libra_local`. [compose]

Compose fixes Media DB to `jdbc:postgresql://postgres:5432/libra_media`, `libra_media`/`media_local`; storage enabled; binding origin `http://core:8081`, binding HTTP allowed; S3 internal `http://s3-gateway:8333`, region `us-east-1`, buckets `libra-source`/`libra-hls`, prefixes `staging/`/`sources/`/`hls/`, connect/request `3s`/`30s`, maximum object bytes `268435456`, scratch `/tmp/media`, S3 HTTP allowed, startup probe false, tool paths `/usr/bin/ffprobe` and `/usr/bin/ffmpeg`, Kafka `kafka:9092`. Core DB/SMTP/Kafka are also literal (`libra_core` DB, `mailpit:1025`, STARTTLS false, `kafka:9092`). Putting an unreferenced variable in `.env` does **not** change those container settings. [compose], [core configuration][core-config]

#### Exact `.env.example` inventory

The following are all assignments in [.env.example][env], not a complete runnable configuration. It contains no private credentials and leaves required keys blank. [env]

| Value | Variables |
| --- | --- |
| empty | `SEAWEEDFS_ACCESS_KEY`, `SEAWEEDFS_SECRET_KEY`, `CORE_JWT_KEY`, `CORE_MAIL_ENCRYPTION_KEY`, `CORE_PLAYBACK_PRIVATE_KEY`, `CORE_PLAYBACK_PUBLIC_KEY`, `CORE_PLAYBACK_KEY_ID`, `CORE_ANALYTICS_PRIVATE_KEY`, `CORE_ANALYTICS_PUBLIC_KEY`, `CORE_ANALYTICS_KEY_ID`, `CORE_MEDIA_SERVICE_PUBLIC_KEY`, `CORE_MEDIA_SERVICE_KEY_ID`, `CORE_MEDIA_CONTROL_PRIVATE_KEY`, `CORE_MEDIA_CONTROL_PUBLIC_KEY`, `CORE_MEDIA_CONTROL_KEY_ID`, `CORE_BOOTSTRAP_ADMIN_EMAIL`, `CORE_BOOTSTRAP_ADMIN_PASSWORD`. [env] |
| `false` | `MEDIA_STORAGE_INITIALIZE_BUCKETS`, `CORE_ANALYTICS_ENABLED`, `CORE_COOKIE_SECURE`, `CORE_MAIL_STARTTLS`, `CORE_MEDIA_SERVICE_AUTH_ENABLED`, `CORE_MEDIA_CONTROL_ENABLED`, `MEDIA_CORE_SERVICE_AUTH_ENABLED`, `MEDIA_PLAYBACK_ENABLED`. [env] |
| `true` | `CORE_LOCAL_DEVELOPMENT`, `CORE_MEDIA_LISTENER_ENABLED`, `CORE_OUTBOX_PUBLISHER_ENABLED`, `CORE_DLT_LISTENER_ENABLED`, `MEDIA_WORKER_ENABLED`, `MEDIA_LIFECYCLE_ENABLED`. [env] |
| individual | `POSTGRES_SUPERUSER_PASSWORD=libra_local`; `MEDIA_S3_BROWSER_ENDPOINT=http://localhost:8333`; `MEDIA_BROWSER_ORIGIN=http://localhost:8080`; `CORE_ANALYTICS_BASE_URL=http://recommendation-analytics:8083`; `CORE_PUBLIC_BASE_URL=http://localhost:8080`; `CORE_MEDIA_CONTROL_BASE_URL=http://media:8082`. [env] |

Notably absent from the example: all `MEDIA_CORE_BINDING_*` settings and `MEDIA_OUTBOX_ENABLED` (Compose supplies true). `CORE_MAIL_STARTTLS` is present but is not interpolated by main Compose, which fixes it to false. [env][compose]

### 2. Local startup

These are the workstation startup commands; M35 verified the disposable smoke stacks below, not a long-lived workstation stack. Run from repository root. README gives the dependency and full-profile commands exactly as shown; it also lists Java 21 and Docker Desktop/Compose prerequisites. [.env preparation and local commands][readme]

1. Prepare the ignored environment file once:

   ```powershell
   if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
   ```

   Supply independent persistent storage credentials per [media-storage-m06.md][m06], separate Base64 random 32-byte Core identity/mail keys per [core-identity.md][identity-doc], and persistent playback RSA keys per [core-playback.md][playback-doc]. Core requires those keys even if Media playback remains disabled. [.env.example][env], [PlaybackProperties][playback-props]

2. For a working upload-to-playback slice, enable `CORE_MEDIA_CONTROL_ENABLED`, `MEDIA_CORE_SERVICE_AUTH_ENABLED`, `MEDIA_CORE_BINDING_ENABLED`, `CORE_MEDIA_SERVICE_AUTH_ENABLED`, and `MEDIA_PLAYBACK_ENABLED`. Provision independent Core→Media and Media→Core RSA pairs. Compose forwards the Core control public key to Media; set Core's inbound public key/ID to match Media's binding public key/ID. Keep the Media binding private key in Media. Worker/outbox/lifecycle and Core listener are already on in Compose. Without these optional integration switches, “full” starts containers but does not enable the complete feature. [m09], [m07], [compose], [binding], [ticket-security]

3. Start dependencies:

   ```powershell
   docker compose --env-file .env -f infra/compose.yaml up -d
   ```

   No profile starts `postgres`, `kafka`, `mailpit`, `seaweedfs`, and `s3-gateway`. Host loopback ports: PostgreSQL 5432, Kafka 9092, S3 gateway 8333, Mailpit SMTP 1025/UI 8025. SeaweedFS itself stays on the private storage network. [compose], [readme]

4. Before first browser upload, set `MEDIA_STORAGE_INITIALIZE_BUCKETS=true` for one Media startup to create missing buckets/install source CORS, then turn it off for subsequent starts. SeaweedFS receives `S3_BUCKET=libra-source,libra-hls`; the explicit Media initializer installs CORS (`PUT`, `HEAD`, `Content-Type`, `x-amz-checksum-sha256`, configured browser origin). Both bucket HEAD checks always run. [m05], [m06], [compose], [storage-startup]

5. Start application images and ingress using README's full-profile command:

   ```powershell
   docker compose --env-file .env -f infra/compose.yaml --profile full up --build
   ```

   Compose orders Core after healthy PostgreSQL/Kafka/Mailpit; Media after healthy PostgreSQL/Kafka and started S3 gateway; gateway after SeaweedFS started; recommendation/analytics after healthy PostgreSQL/Kafka; Nginx after Core/Media/recommendation/web started. There is no Core↔Media health dependency or readiness gate on Nginx. Public ingress is `http://localhost:8080`; Media has no published application port. All listed application services use `restart: unless-stopped`; bucket-check refusal during a storage outage can converge by restart when storage returns. [compose], [m34]

Host-process alternative in README starts dependencies first, then Core/other services in separate terminals and `pnpm.cmd dev` in `web`. Maven does not read `.env`; host processes need explicit process environment. [readme], [core-config]

### 3. States and error codes

#### Authoritative states and transitions

| Domain | States / transitions | Source |
| --- | --- | --- |
| Media upload | `OPEN → SUBMITTED` on accepted completion; `OPEN → EXPIRED` on lifecycle expiry. Submitted stays submitted through processing outcomes. Duplicate completion returns existing job/status. Expiry creates no job. | [Media V1][v1], [upload-store] `queue`, [workflow] `complete`, [expiry] |
| Media asset | `UPLOADING → QUEUED` on completion; `QUEUED → PROCESSING` on first claim; `PROCESSING → READY` on finalization; `QUEUED/PROCESSING → FAILED` on permanent/exhausted job; `UPLOADING → FAILED` on upload expiry. Retry requeues the job while asset remains PROCESSING. | [v1], [upload-store], [leases], [selections], [expiry] |
| Media job | `QUEUED → CLAIMED → SOURCE_SELECTED → TRANSCODING → FINALIZING → SUCCEEDED`. Active lease recovery restarts at CLAIMED with incremented attempt. Retry/release can return to QUEUED; permanent input failure → `FAILED_PERMANENT`; third retryable failure/expired final claim → `EXHAUSTED`. Maximum 3 claims, including interrupted claims. | [v1], [leases], [job-sources], [job-stages], [selections] |
| Media event delivery | `PENDING → LEASED → SENT`; failure/release → PENDING; expired LEASED reclaimable. SENT terminal; event identity/payload immutable and deletion forbidden. | [v1], [outbox-db], [Media V6][v6] |
| Core catalog projection | Binding initially `PENDING` in DB; incoming event enum only `PROCESSING`, `READY`, `FAILED`. Exact content/binding/asset/version must match. Duplicate event IDs and versions ≤current projection ignored transactionally. Higher valid versions update state; there is no Media-like READY-terminal guard in this projection method. Candidate updates do not publish or replace active binding. | [Core V3][cv3], [CatalogModels][catalog-models], [projection] |
| Core upload status | Relays Media's `OPEN/SUBMITTED/EXPIRED`, all five asset states and eight job stages; separate from catalog's projection enum. | [control-models], [provisioning] |

Media persistence uses string states constrained by SQL; its event enum is `MediaAssetEvents.State { PROCESSING, READY, FAILED }`. Core's HTTP adapter explicitly declares `UploadState`, `AssetState`, and `JobStage` enums matching the Media status vocabulary. [v1], [upload-store], [asset-events], [control-models]

Migration guards: V2 ties active job stages and LEASED outbox state to lease presence and checks master under output; V3 adds job failure/recovery index; V4 adds validated source metadata; V5 makes selected source/output immutable, READY terminal and READY event mandatory; V6 protects immutable ordered outbox delivery; V7 enforces exact transition/event contract and broker failure codes; V8 makes terminal jobs/EXPIRED/cleanup mark final and expiry require FAILED asset without job; V9 fixes storage layout after an upload exists. These are **not** a general SQL transition whitelist for every possible state pair. [v2][v3][v4][v5][v6][v7][v8][v9]

#### Stable HTTP problems

The tables cover implemented upload/control, completion, playback admission/renewal, and protected delivery. Successful status responses may contain failure codes listed afterward; those codes are not automatically HTTP problems. [upload-problems][control-models][stream-problems][api-handler]

| Surface | HTTP | Stable code(s) and trigger | Source |
| --- | --- | --- | --- |
| Media internal upload operations | 400 | `INVALID_REQUEST`: invalid body/UUID/validation; handled framework 4xx except 404 normalized to this code. | [upload-problems], [upload-controller] |
| Media internal upload operations | 401 / 403 | `AUTHENTICATION_REQUIRED` / `ACCESS_DENIED`: missing/invalid service token, or scope/route refusal. | [service-security], [media-problems] |
| Media ensure/read/URL/complete | 404 | `NOT_FOUND`: upload absent, or binding absent/noncurrent/mismatched in Core lookup. | [workflow], [persistence], [binding], [upload-problems] |
| Media ensure | 409 | `IDEMPOTENCY_CONFLICT`: same upload identity with changed fingerprint; `UPLOAD_STATE_CONFLICT`: conflicting persisted identity/state. | [persistence], [upload-problems] |
| Media URL/complete | 409 | `UPLOAD_STATE_CONFLICT`; completion additionally `SOURCE_MISSING` for absent staging object or wrong length/content type. HEAD does not prove actual content checksum. | [upload-store], [staging-inspector], [grant-adapter], [upload-problems] |
| Media ensure/URL/complete | 410 | `UPLOAD_EXPIRED`: deadline/state rejection; new ensure also refuses expiry outside its future ≤1 h reservation window. | [persistence], [upload-descriptor], [upload-problems] |
| Media upload operations | 503 | `CORE_UNAVAILABLE`: disabled/failed binding read or otherwise unmapped known upload/binding failure; `STORAGE_UNAVAILABLE`: signing/storage inspection unavailable; `MEDIA_UNAVAILABLE`: DB/transaction or unexpected runtime failure. | [binding], [grant-adapter], [staging-inspector], [upload-problems] |
| Core upload create | 409 | `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `UPLOAD_NOT_ALLOWED`, `UPLOAD_STATE_CONFLICT`: stale version, changed request, ineligible catalog state, retired candidate. | [intents] |
| Core upload read/URL/complete | 409 | `UPLOAD_STATE_CONFLICT`: upstream conflict/not-found or noncurrent candidate; completion preserves `SOURCE_MISSING` from Media's 409. | [provisioning], [control-client] |
| Core upload mutations/reprovisioning | 410 | `UPLOAD_EXPIRED` from Media. Ordinary status read can successfully report EXPIRED instead. | [provisioning], [control-client] |
| Core upload operations | 503 | `MEDIA_UNAVAILABLE`: disabled/unavailable/denied/malformed/ambiguous remote exchange. Media's `CORE_UNAVAILABLE` and `STORAGE_UNAVAILABLE` do not pass through under those names. | [provisioning], [control-client] |
| Core upload and playback | 400 / 401 / 403 / 404 | `INVALID_REQUEST`; `AUTHENTICATION_REQUIRED` (security) or `INVALID_CREDENTIALS` (live identity check); `ACCESS_DENIED` (role/CSRF); `NOT_FOUND` (missing/foreign upload/profile/playback session or hidden content). | [api-problems], [api-handler], [core-security], [identity-access], [identity-exception], [intents], [intent-store], [playback-store], [entitlement] |
| Core admission/renewal | 403 | `EMAIL_VERIFICATION_REQUIRED`, `PREMIUM_REQUIRED`. | [entitlement], [entitlement-service] |
| Core admission/renewal | 409 | `NOT_PLAYABLE_CONTENT`, `MEDIA_NOT_READY`, `PLAYBACK_EXPIRING` (rounded/capped expiry not after now). Renewal additionally `MEDIA_BINDING_CHANGED`, `PLAYBACK_ENDED`, `PLAYBACK_EXPIRED`. | [entitlement], [entitlement-service], [playback-service], [tickets] |
| Core general framework/unexpected failures on these routes | 405 / 415 / 429 / 500 | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `RATE_LIMITED`, `INTERNAL_ERROR`; generic 409 fallback `CONFLICT`; other status fallback `REQUEST_REJECTED` for non-5xx, `INTERNAL_ERROR` for 5xx. These are shared mappings, not evidence of a playback-specific rate limiter. DB exceptions not otherwise mapped reach generic 500. | [api-problems], [api-handler] |
| Protected Media stream | 401 | `AUTHENTICATION_REQUIRED`: missing/duplicate/empty/oversized/malformed/expired/wrong-key ticket; disabled playback; Authorization header also refused. No Bearer challenge on streams. | [ticket-security], [media-problems] |
| Protected Media stream | 403 | `ACCESS_DENIED`: valid ticket for another session, mismatched content/binding, disallowed method. | [ticket-security], [delivery], [stream-problems] |
| Protected Media stream | 404 | `NOT_FOUND`: invalid name, unknown/not READY generation, absent object. Binding/content mismatch is checked before READY in `StreamDelivery.open`. | [delivery], [stream-problems] |
| Protected Media stream | 503 | `MEDIA_UNAVAILABLE`: DB/storage/unexpected precommit failure; capacity refusal adds `Retry-After: 1`; other failures do not. | [stream-controller], [stream-problems] |
| Public Nginx Media routes | 400 / 403 / 404 | `INVALID_REQUEST` for announced body on stream/health; `ACCESS_DENIED` for non-GET stream/health; `NOT_FOUND` for other `/api/media` paths. | [nginx] |
| Public Nginx upstream failure | 503 | `MEDIA_UNAVAILABLE` for stream upstream 502/504; `CORE_UNAVAILABLE` for Core upstream 502/504. Stream connection-limit 503 adds `Retry-After: 1`; upstream failures do not. | [nginx] |

`STREAM_TRANSFER_EXPIRED` and `STREAM_INTERRUPTED` are internal exceptions, not additional public problem codes: before response commitment failure becomes 503 `MEDIA_UNAVAILABLE`; after commitment Media aborts the body. Direct malformed requests failing before MVC may still get framework/container 400/403 without a contract problem; the docs explicitly limit the public guarantee to ingress paths. [delivery][stream-controller][stream-problems][m30] Limits

| Status field / internal diagnostic | Codes | HTTP relationship / source |
| --- | --- | --- |
| Upload `failureCode` | `UPLOAD_EXPIRED`, `SOURCE_MISSING`, `SIZE_MISMATCH`, `CHECKSUM_MISMATCH`, `UNSUPPORTED_MEDIA`, `CORRUPT_INPUT`, `PROCESSING_FAILED`, `RETRY_EXHAUSTED` (or null) | Returned in successful status JSON, not a status-code mapping. Top-level field populated for FAILED asset or EXPIRED upload; job's `processing.failureCode` can report a pending retry. [upload-store], [control-models], [v3] |
| Retry classification | Only `PROCESSING_FAILED` is retryable in `ProcessingFailure`; source missing/size/checksum/media-policy/corrupt errors permanent. Retry exhaustion stores `RETRY_EXHAUSTED`. | No separate HTTP status; job terminal state/asset FAILED exposed by polling. [processing-failure], [leases] |
| Outbox `last_error_code` | `BROKER_UNAVAILABLE`, `BROKER_REJECTED` | Durable delivery diagnostics; no Media operator HTTP endpoint. [v7], [publisher], [m26] Limits |

### 4. Recovery behavior and limits

| Mechanism | Implemented recovery | Bound / remaining limit | Source |
| --- | --- | --- | --- |
| Ambiguous upload control call | Core commits intent before remote call; retry same POST/request fingerprint or GET status can ensure the same Media upload if absent. Completion is one durable job and repeated accepted completion returns existing job, even if original acknowledgement was lost. | No automatic HTTP retry; exchanges use 2 s budget, 500 ms connect timeout, 16 in-flight slots and bounded response subscribers. Caller must reuse identity. | [intents], [provisioning], [control-client], [binding], [workflow], [upload-store], [m09] |
| Worker claim/restart | DB-time due/expired active rows selected `FOR UPDATE SKIP LOCKED`; fresh token/attempt; every selection/renewal/terminal write fenced by identity, token, attempt, active stage and strict post-lock expiry. | One job slot + heartbeat slot per worker, no memory backlog; maximum 3 claims. Defaults: lease 30 s, renew 5 s, retry 10 s, poll 1 s. Interrupted claims consume attempts; release on third claim exhausts. | [worker], [leases], [fence], [my] |
| Lost/stalled heartbeat | Renewal failure interrupts handler; local monotonic **or** wall-clock lapse cancels work even if renewal thread is stuck. Graceful shutdown stops claims and releases still-current work; a stuck owner eventually loses authority by expiry. | A pause between validity check and outbound storage request can still permit one late write, bounded by request timeout afterward. `Error` can silently kill schedules. | [worker], [m32] Limits |
| Source retry | Frozen verified source selected once; later claims validate/reuse selected source and committed metadata instead of trusting overwritten staging bytes. No staging fallback if selected source disappears. | Lost selected source is permanent; failed asset's selected source retained. | [freezer], [source-prober], [m31] Limits |
| READY commit | Store sealed inventory, read back, then one lease-fenced transaction selects output, records duration, increments aggregate/event and marks SUCCEEDED. Lost acknowledgement cannot overwrite READY selection. | No storage object lock; readback proves availability once, not future availability. Deletion between readback and commit is undetected. | [selections], [v5], [m23] Limits, [m24] Limits |
| Media outbox | Reclaim expired leases, publish outside DB transaction, mark SENT after ACK. Stable event ID on duplicate delivery; exponential retry starts at 1 s, capped at configured 5 min. Fatal producer discarded/replaced; retriable producer faults keep producer. | At least once; crash/timeout/lease loss around ACK can duplicate. Per-asset earlier unsent version blocks later versions; no attempt limit, PARKED state, manual retry/replay or SENT redelivery. 100 records maximum per drain. | [relay], [outbox-db], [publisher], [v6], [m25] Limits, [m26] Limits |
| Core Media event retry | Transient DB/transaction failures sanitized into `MediaEventRetryableException`: retry 1 s ×2, capped 30 s, without finite stop. SQL classes 08, 53, 57P0*, states 40001/40P01/55P03 and corresponding Spring/SQL transient/recoverable types covered; class 54 explicitly poison. | Blocking retry holds affected partition until recovery; misclassification can block indefinitely. Poison gets 2 retries at 1 s then same-partition DLT; DLT publish waits up to 10 s, failure throws rather than accepting recovery. DLT capture itself retries indefinitely. | [listener], [consumer-config], [m34] Limits |
| Core projection redelivery | Event receipt + state update transactionally deduplicated; stale aggregate versions ignored. | No end-to-end exactly-once promise; replay requires retained Kafka data or Core's separate operations workflow. | [projection], [m25], [core-config] |
| Upload deadline | Core reservation + Media descriptor allow ≤1 h; signed PUT expires by min(upload expiry, now+15 min). URL/complete enforce deadline even if sweeper is disabled. Enabled sweeper atomically expires OPEN/UPLOADING, fails asset and appends FAILED event; completion/expiry lock same upload. | Sweeper default 1 min poll, batch 20. SUBMITTED processing is not expired by this sweep. Worker leases use DB time; upload admission/completion checks use injected application Clock, whereas sweeper uses DB clock. | [intents], [upload-descriptor], [staging-signer], [persistence], [upload-store], [expiry], [my] |
| Object cleanup | Select only settled uploads (EXPIRED without job or SUBMITTED with terminal job), after grace from deadline / later terminal transition. Delete staging and unselected attempts' sources/output, never selected source or selected/overlapping output. Repeat idempotently; record clean only on a later empty pass. | Default grace 1 h; 20 uploads/pass, 1000 output objects/prefix/pass. Fairness via last attempted timestamp. A PUT finishing after final clean mark can leave an orphan: gateway timeout limits inactivity, not total PUT duration. No periodic rescan of marked-clean uploads. | [cleanup-plans], [cleanup], [sweeper], [m31] Limits, [m32] Limits, [s3-gateway] |
| Storage layout / scratch | V9 pins buckets/prefixes; startup mismatch fails; cleanup checks selected keys agree with layout. Scratch owner lock protects live instances; startup reclaims dead owners' directories. | First V9 recording cannot detect an older layout change. Scratch reclaimed only at startup, no sweep inside live instance. No failed-asset retention or READY-output deletion policy. | [layout], [v9], [cleanup], [scratch], [m31], [m32] Limits |
| Process/ingress recovery | Compose `unless-stopped`; Nginx re-resolves application upstreams via Docker DNS every 5 s, resolver timeout 2 s. | Exited processes restart; hung processes do not. Misconfiguration can loop. S3 gateway resolves SeaweedFS only once at startup; M34 did not restart SeaweedFS itself. | [compose], [nginx], [s3-gateway], [m34] Limits |

#### Limits carried forward from M20–M34 documents

| Area | Current limitation or evidence boundary | Source |
| --- | --- | --- |
| Probe / encoding policy | Input ≤256 MiB, duration ≤600 s, one MP4 H.264 video stream, optional AAC, no extra streams; profile/resolution/frame-rate/audio checks in source policy. Strict ffmpeg decode errors reject files that lenient players might display. Policy/track tolerances tested mainly on synthetic content; real phone/camera edit lists/priming remain unproven. | [upload-descriptor], [policy], [v4], [m20] Limits, [m21] Limits, [m22] Limits |
| Resource / timing enforcement | Probe allocation cap is per allocation; aggregate tool memory depends on container; Java has no independent CPU quota. Starved supervisor can enforce deadline late. Probe kernel kill can be judged permanent input failure; encoder exit classification is heuristic. | [m20] Limits, [m21] Limits, [m22] Limits |
| Output verification | Duration tolerance is max(2 s, 10%); structural TS checks can miss a cut on a packet boundary if playlist survives. VFR is converted to average output rate, dropping/duplicating frames. | [policy] `durationSlackMillis`, [m22] Limits |
| Monitoring / operations | Media actuator exposes only health/info; gauges registered but no scrape endpoint/operator identity. M32 bounds gauge statements but pool acquisition and dropped cancel remain limits. No manual Media job retry. | [my], [m26] Limits, [m31] Limits, [m32] Limits |
| Delivery limits | Media limits per instance/in memory; Nginx limits per Nginx. A single blocked write cannot be interrupted by Media's between-write deadline. Wall-clock steps affect running-transfer deadline. No overlapping key rotation, revocation list, multiple renditions or READY-version replacement. | [delivery], [m27] Limits, [m28] Limits, [m29] Limits, [m30] Limits |
| Browser / deployment evidence | M33 used a test harness and one headless Chrome on Windows, loopback HTTP, one 12 s clip; no product-page/other-browser/mobile/HTTPS or browser slow-network/503-retry guarantee. M34 used one workstation/one instance normally, coarse kills and synthetic expiry; no packet loss, network partition, clock skew, disk-full/slow-disk or broker failover evidence. | [m33] Limits, [m34] Limits |
| Contract rollout | Core and Media status contract 1.1.0 must deploy together: strict Core client rejects unknown/missing properties; no transitional client documented. | [control-client], [m31] Limits, [m32] Limits |

**Document chronology discrepancies:** M21 says no production handler, M23 says no outbox publisher, M24 says outbox deletion is unguarded, M25–M30 list cleanup/expiry events as future work, and earlier delivery docs list missing limits/integer timestamp checks. Those were milestone-local limits, superseded by current finalizer/relay/V6, M28 verifier/limits and M31 lifecycle code. They are not current missing features. [finalizer-config][relay][v6][ticket-security][delivery][expiry][cleanup]

### 5. Protected delivery semantics

| Concern | Verified behavior | Source |
| --- | --- | --- |
| Admission / renewal | Core checks live account/auth session, verified email, owned profile, published ancestry, exact READY active binding and Premium where required; same authentication session owns renewal. New session starts PAUSED. Renewal must precede expiry and reevaluates rights after catalog locks; expired/ended sessions need new admission. | [playback-service], [playback-store], [entitlement], [playback-doc] |
| Ticket time | Dedicated RS256 signature; issue time integer seconds; expiry min(now+300 s, auth session expiry, applicable Premium expiry), rounded down. Equality with exp is expired; no leeway. Renewal extends from current server time and issues fresh JTI; heartbeat alone does not renew. | [tickets], [ticket-security], [playback-doc] |
| Required claims | `alg=RS256`, pinned `kid`, `typ=JWT`, `iss=libra-core-playback`, sole `aud=libra-media`, `purpose=media-playback`, canonical UUID sub/JTI/content/binding/asset IDs, positive integer assetVersion, integral `iat`/`exp`, iat≤now<exp and 0<exp−iat≤300. No token-chosen key URL. | [ticket-security], [tickets] |
| Cookie | `__Secure-LIBRA_PLAYBACK` when secure; explicit local insecure setting uses `LIBRA_PLAYBACK`. HttpOnly, SameSite=Lax, no Domain, Path `/api/media/v1/streams/{sessionId}/`, max-age to expiry. Media and Core must select same name. Exactly one cookie accepted; Authorization header or duplicate cookie refused; URL/query values are not ticket sources. | [tickets], [ticket-props], [ticket-security], [playback-doc] |
| Per-object authorization | Every playlist/segment request independently verifies ticket/session and exact Media-owned READY asset generation/content/binding. Keys derived from DB selection and closed name vocabulary, never a supplied storage path. Relative credential-free playlists remain under session path. | [ticket-security], [delivery], [stream-name], [playback-doc] |
| Public route | Nginx exact lowercase UUID location: `/api/media/v1/streams/{uuid}/(master.m3u8\|rendition.m3u8\|segment-[0-9]{5}.ts)`, rewrite removes `/api/media`. Media further caps segment numbers to 00000–00299; syntactically matched higher names still reach Media and fail 404. Only bodiless GET; alternate Media paths get 404 except bodiless GET health probes. | [nginx], [stream-name] |
| Limits | Nginx: 4 connections/session, 16/peer address; Media application defaults 64 total/4 session, Compose total 6/4. Per-response transfer deadline is min(admission time+60 s default, ticket exp). Gateway permits 8 connections/client and 64 total, motivating Compose's smaller Media total. | [nginx], [my], [compose], [delivery], [s3-gateway] |
| Busy / dependency failures | Capacity refusals: 503 `MEDIA_UNAVAILABLE`, `Retry-After: 1`. Storage/DB/unknown precommit failure or unreachable/timed-out Media: same 503 without Retry-After. Media answers pass through Nginx unchanged. Core upstream 502/504: 503 `CORE_UNAVAILABLE` without Retry-After; command may already have committed. | [stream-problems], [stream-controller], [nginx], [m29], [m34] Limits |
| Cache / range | Every successful object returns full 200 with length, type, `no-store`, `nosniff`; implementation does not provide partial-range/validator responses. Nginx has no proxy cache and forbids temporary-file spooling; buffers 16k header + eight 64k body buffers (busy 128k). | [stream-controller], [nginx], [m30] |
| Expiry during transfer | Deadline checked before each write. Before commitment, failure reset to 503; after commitment abort incomplete body. Already buffered socket/Nginx bytes can continue reaching player; downloaded bytes remain. Blocked writes are limited by container/proxy behavior, not a hard interrupt at exp. | [delivery], [stream-controller], [m28] Limits, [m30] Limits |
| Revocation / outage | Logout, suspension, history clear, deletion/session end, or Core outage stop current-authority renewal/admission as applicable, but cannot revoke a copied ticket at Media before exp (≤300 s). End clears current browser cookie; copied bearer tickets and downloaded bytes survive until expiry / indefinitely for downloaded bytes. Media needs its own DB/storage and configured key, not live Core authorization per object. | [playback-doc], [ticket-security], [delivery], [m34] Limits |

M35 corrected `docs/core-playback.md` and the README, which still described Media HLS delivery as future work. Automated overlapping-key rotation is still absent; one pinned Media key is configured. [playback-doc][readme][ticket-security][stream-controller][m27][m34]

### 6. Versioned contracts under `contracts/`

HTTP versions below are **`info.version`**, not OpenAPI specification version. Every HTTP filename has the `.v1` contract family; all currently declare OpenAPI 3.1.0. Event schemas use wire `schemaVersion: 1` directly or through `core-event-envelope.v1.schema.json`. Sources: individual contract files linked in the tables below.

| HTTP contract path | Version |
| --- | --- |
| [contracts/http/analytics-internal.v1.json](../contracts/http/analytics-internal.v1.json) | 1.0.0; proposed downstream Analytics contract per its description |
| [contracts/http/core-analytics.v1.json](../contracts/http/core-analytics.v1.json) | 1.0.0 |
| [contracts/http/core-catalog-profiles.v1.json](../contracts/http/core-catalog-profiles.v1.json) | 1.0.0 |
| [contracts/http/core-community.v1.json](../contracts/http/core-community.v1.json) | 1.0.0 |
| [contracts/http/core-entitlements.v1.yaml](../contracts/http/core-entitlements.v1.yaml) | 1.0.0 |
| [contracts/http/core-foundation.v1.yaml](../contracts/http/core-foundation.v1.yaml) | 1.0.0 |
| [contracts/http/core-identity.v1.json](../contracts/http/core-identity.v1.json) | 1.0.0 |
| [contracts/http/core-internal-media.v1.json](../contracts/http/core-internal-media.v1.json) | 1.0.0 |
| [contracts/http/core-media-uploads.v1.yaml](../contracts/http/core-media-uploads.v1.yaml) | 1.1.0 |
| [contracts/http/core-operations.v1.json](../contracts/http/core-operations.v1.json) | 1.0.0 |
| [contracts/http/core-playback.v1.yaml](../contracts/http/core-playback.v1.yaml) | 1.0.0 |
| [contracts/http/core-subscriptions.v1.yaml](../contracts/http/core-subscriptions.v1.yaml) | 1.0.0 |
| [contracts/http/media-internal-uploads.v1.yaml](../contracts/http/media-internal-uploads.v1.yaml) | 1.1.0 |
| [contracts/http/media-streams.v1.yaml](../contracts/http/media-streams.v1.yaml) | 1.3.0 |

| Event schema path | Version / event types |
| --- | --- |
| [contracts/events/core-event-envelope.v1.schema.json](../contracts/events/core-event-envelope.v1.schema.json) | Wire schemaVersion 1; common envelope |
| [contracts/events/core-catalog-publication.v1.schema.json](../contracts/events/core-catalog-publication.v1.schema.json) | 1 via envelope; `CatalogPublished`, `CatalogUnpublished`, `CatalogAvailabilityChanged` |
| [contracts/events/core-profile-lifecycle.v1.schema.json](../contracts/events/core-profile-lifecycle.v1.schema.json) | 1 via envelope; `ProfileCreated`, `ProfileUpdated`, `ProfileDeleted` |
| [contracts/events/core-playback-progress.v1.schema.json](../contracts/events/core-playback-progress.v1.schema.json) | 1 via envelope; `PlaybackProgressAccepted` |
| [contracts/events/media-asset-state.v1.schema.json](../contracts/events/media-asset-state.v1.schema.json) | Wire schemaVersion 1; `MediaAssetStateChanged`, states PROCESSING/READY/FAILED, READY duration required (1–600 s) |

Media topic/key are `media.assets.v1` / asset UUID, aggregateId equals payload.assetId, monotonically increasing aggregateVersion; no upload/failure/storage secret is added to event payload. Core topics are `core.catalog.v1`, `core.profiles.v1`, `core.playback.v1`. [media schema](../contracts/events/media-asset-state.v1.schema.json), [Media V7][v7], [CoreEventTopic][core-topics]

Three wire examples, not additional contracts: [media-asset-state.v1.processing.json](../contracts/events/examples/media-asset-state.v1.processing.json), [media-asset-state.v1.ready.json](../contracts/events/examples/media-asset-state.v1.ready.json), [media-asset-state.v1.failed.json](../contracts/events/examples/media-asset-state.v1.failed.json), each `schemaVersion: 1`.

**Known discrepancies:** `core-media-uploads.v1.yaml` introductory text says completion remains planned, although its complete endpoint and both services implement it. `core-configuration.md` said Flyway V1–V7 although Core includes V8 upload intents; M35 corrected it. Historical docs describing missing publisher, delivery, cleanup or expiry events are superseded as noted above. The M35 run repeated the M29 smoke only; the M30, M33 and M34 smokes were not rerun. Production readiness, other-browser compatibility, broker failover and a hard end-to-end transfer/cleanup deadline remain unproven. [core upload contract](../contracts/http/core-media-uploads.v1.yaml), [upload-controller], [provisioning], [core-config], [Core V8][cv8], [m28] Limits, [m29] Limits, [m32] Limits, [m33] Limits, [m34] Limits

[my]: ../services/media/src/main/resources/application.yml
[cy]: ../services/core/src/main/resources/application.yml
[compose]: ../infra/compose.yaml
[env]: ../.env.example
[readme]: ../README.md
[nginx]: ../infra/nginx/nginx.conf
[s3-gateway]: ../infra/nginx/s3-gateway.conf
[start-private]: ../infra/seaweedfs/start-private.sh
[core-config]: ../docs/core-configuration.md
[identity-doc]: ../docs/core-identity.md
[playback-doc]: ../docs/core-playback.md
[m05]: ../docs/media-runtime-m05.md
[m06]: ../docs/media-storage-m06.md
[m07]: ../docs/media-service-auth-m07.md
[m09]: ../docs/media-upload-provisioning-m09.md
[m20]: ../docs/media-probe-hardening-m20.md
[m21]: ../docs/media-transcode-m21.md
[m22]: ../docs/media-encoding-hardening-m22.md
[m23]: ../docs/media-finalization-m23.md
[m24]: ../docs/media-finalization-hardening-m24.md
[m25]: ../docs/media-outbox-publication-m25.md
[m26]: ../docs/media-delivery-hardening-m26.md
[m27]: ../docs/media-protected-delivery-m27.md
[m28]: ../docs/media-protected-delivery-hardening-m28.md
[m29]: ../docs/media-protected-delivery-ingress-m29.md
[m30]: ../docs/media-protected-delivery-abuse-m30.md
[m31]: ../docs/media-lifecycle-cleanup-m31.md
[m32]: ../docs/media-cleanup-race-hardening-m32.md
[m33]: ../docs/media-browser-vertical-slice-m33.md
[m34]: ../docs/media-outage-restart-hardening-m34.md
[v1]: ../services/media/src/main/resources/db/migration/V1__create_media_upload_pipeline.sql
[v2]: ../services/media/src/main/resources/db/migration/V2__harden_media_pipeline_constraints.sql
[v3]: ../services/media/src/main/resources/db/migration/V3__add_media_job_failure_and_recovery.sql
[v4]: ../services/media/src/main/resources/db/migration/V4__add_media_source_metadata.sql
[v5]: ../services/media/src/main/resources/db/migration/V5__guard_media_ready_selection.sql
[v6]: ../services/media/src/main/resources/db/migration/V6__guard_media_outbox_delivery.sql
[v7]: ../services/media/src/main/resources/db/migration/V7__guard_media_outbox_contract.sql
[v8]: ../services/media/src/main/resources/db/migration/V8__add_media_lifecycle_cleanup.sql
[v9]: ../services/media/src/main/resources/db/migration/V9__record_media_storage_layout.sql
[cv3]: ../services/core/src/main/resources/db/migration/V3__create_profiles_catalog.sql
[cv8]: ../services/core/src/main/resources/db/migration/V8__create_media_upload_intents.sql
[storage-config]: ../services/media/src/main/java/com/libra/streaming/media/storage/MediaStorageConfiguration.java
[storage-props]: ../services/media/src/main/java/com/libra/streaming/media/storage/MediaStorageProperties.java
[storage-startup]: ../services/media/src/main/java/com/libra/streaming/media/storage/MediaStorageStartup.java
[layout]: ../services/media/src/main/java/com/libra/streaming/media/storage/MediaStorageLayout.java
[scratch]: ../services/media/src/main/java/com/libra/streaming/media/storage/ScratchSpace.java
[binding]: ../services/media/src/main/java/com/libra/streaming/media/upload/infrastructure/CoreBindingClient.java
[binding-props]: ../services/media/src/main/java/com/libra/streaming/media/upload/infrastructure/CoreBindingProperties.java
[service-security]: ../services/media/src/main/java/com/libra/streaming/media/security/CoreServiceSecurity.java
[service-props]: ../services/media/src/main/java/com/libra/streaming/media/security/CoreServiceProperties.java
[ticket-security]: ../services/media/src/main/java/com/libra/streaming/media/security/PlaybackTicketSecurity.java
[ticket-props]: ../services/media/src/main/java/com/libra/streaming/media/security/PlaybackTicketProperties.java
[media-problems]: ../services/media/src/main/java/com/libra/streaming/media/security/MediaProblems.java
[delivery]: ../services/media/src/main/java/com/libra/streaming/media/delivery/application/StreamDelivery.java
[delivery-config]: ../services/media/src/main/java/com/libra/streaming/media/delivery/infrastructure/StreamDeliveryConfiguration.java
[stream-controller]: ../services/media/src/main/java/com/libra/streaming/media/delivery/api/StreamController.java
[stream-problems]: ../services/media/src/main/java/com/libra/streaming/media/delivery/api/StreamProblems.java
[stream-name]: ../services/media/src/main/java/com/libra/streaming/media/delivery/domain/StreamName.java
[worker]: ../services/media/src/main/java/com/libra/streaming/media/processing/application/BoundedMediaWorker.java
[worker-config]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/MediaWorkerConfiguration.java
[leases]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/JdbcJobLeases.java
[fence]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/LeaseFence.java
[job-sources]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/JdbcJobSources.java
[job-stages]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/JdbcJobStages.java
[selections]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/JdbcOutputSelections.java
[asset-events]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/MediaAssetEvents.java
[finalizer-config]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/OutputFinalizationConfiguration.java
[probe-config]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/SourceProbeConfiguration.java
[transcode-config]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/SourceTranscodeConfiguration.java
[prober]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/FfprobeMediaProber.java
[transcoder]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/FfmpegMediaTranscoder.java
[tools]: ../services/media/src/main/java/com/libra/streaming/media/processing/infrastructure/ToolProcesses.java
[processing-failure]: ../services/media/src/main/java/com/libra/streaming/media/processing/domain/ProcessingFailure.java
[freezer]: ../services/media/src/main/java/com/libra/streaming/media/processing/application/SourceFreezer.java
[source-prober]: ../services/media/src/main/java/com/libra/streaming/media/processing/application/SourceProber.java
[policy]: ../services/media/src/main/java/com/libra/streaming/media/processing/application/SourcePolicy.java
[relay]: ../services/media/src/main/java/com/libra/streaming/media/events/application/OutboxRelay.java
[outbox-config]: ../services/media/src/main/java/com/libra/streaming/media/events/infrastructure/OutboxPublicationConfiguration.java
[outbox-db]: ../services/media/src/main/java/com/libra/streaming/media/events/infrastructure/JdbcOutboxEvents.java
[publisher]: ../services/media/src/main/java/com/libra/streaming/media/events/infrastructure/KafkaEventPublisher.java
[lifecycle-config]: ../services/media/src/main/java/com/libra/streaming/media/lifecycle/infrastructure/LifecycleConfiguration.java
[sweeper]: ../services/media/src/main/java/com/libra/streaming/media/lifecycle/application/LifecycleSweeper.java
[cleanup]: ../services/media/src/main/java/com/libra/streaming/media/lifecycle/application/ObjectCleanup.java
[cleanup-plans]: ../services/media/src/main/java/com/libra/streaming/media/lifecycle/infrastructure/JdbcCleanupPlans.java
[expiry]: ../services/media/src/main/java/com/libra/streaming/media/lifecycle/infrastructure/JdbcUploadExpiry.java
[upload-controller]: ../services/media/src/main/java/com/libra/streaming/media/upload/api/UploadControlController.java
[upload-problems]: ../services/media/src/main/java/com/libra/streaming/media/upload/api/UploadControlProblems.java
[upload-store]: ../services/media/src/main/java/com/libra/streaming/media/upload/infrastructure/MediaUploadStore.java
[persistence]: ../services/media/src/main/java/com/libra/streaming/media/upload/infrastructure/MediaPersistenceService.java
[workflow]: ../services/media/src/main/java/com/libra/streaming/media/upload/application/UploadWorkflow.java
[upload-descriptor]: ../services/media/src/main/java/com/libra/streaming/media/upload/domain/UploadDescriptor.java
[staging-signer]: ../services/media/src/main/java/com/libra/streaming/media/storage/StagingUploadSigner.java
[staging-inspector]: ../services/media/src/main/java/com/libra/streaming/media/storage/StagingObjectInspector.java
[grant-adapter]: ../services/media/src/main/java/com/libra/streaming/media/upload/infrastructure/StagingGrantAdapter.java
[control-client]: ../services/core/src/main/java/com/libra/streaming/core/integration/media/MediaControlClient.java
[control-models]: ../services/core/src/main/java/com/libra/streaming/core/integration/media/MediaControlModels.java
[control-props]: ../services/core/src/main/java/com/libra/streaming/core/integration/media/MediaControlProperties.java
[control-tokens]: ../services/core/src/main/java/com/libra/streaming/core/integration/media/MediaControlTokens.java
[inbound-props]: ../services/core/src/main/java/com/libra/streaming/core/integration/security/MediaServiceProperties.java
[internal-security]: ../services/core/src/main/java/com/libra/streaming/core/integration/security/InternalServiceSecurity.java
[playback-props]: ../services/core/src/main/java/com/libra/streaming/core/playback/PlaybackProperties.java
[tickets]: ../services/core/src/main/java/com/libra/streaming/core/playback/PlaybackTickets.java
[playback-service]: ../services/core/src/main/java/com/libra/streaming/core/playback/PlaybackService.java
[playback-store]: ../services/core/src/main/java/com/libra/streaming/core/playback/PlaybackStore.java
[intents]: ../services/core/src/main/java/com/libra/streaming/core/catalog/UploadIntentService.java
[intent-store]: ../services/core/src/main/java/com/libra/streaming/core/catalog/UploadIntentStore.java
[provisioning]: ../services/core/src/main/java/com/libra/streaming/core/catalog/UploadProvisioningService.java
[projection]: ../services/core/src/main/java/com/libra/streaming/core/catalog/MediaProjectionService.java
[catalog-models]: ../services/core/src/main/java/com/libra/streaming/core/catalog/CatalogModels.java
[listener]: ../services/core/src/main/java/com/libra/streaming/core/integration/media/MediaAssetListener.java
[consumer-config]: ../services/core/src/main/java/com/libra/streaming/core/integration/media/MediaConsumerConfiguration.java
[entitlement]: ../services/core/src/main/java/com/libra/streaming/core/entitlement/application/EntitlementUseCase.java
[entitlement-service]: ../services/core/src/main/java/com/libra/streaming/core/entitlement/infrastructure/EntitlementService.java
[identity-access]: ../services/core/src/main/java/com/libra/streaming/core/identity/IdentityAccess.java
[identity-exception]: ../services/core/src/main/java/com/libra/streaming/core/identity/IdentityException.java
[api-problems]: ../services/core/src/main/java/com/libra/streaming/core/api/ApiProblems.java
[api-handler]: ../services/core/src/main/java/com/libra/streaming/core/api/ApiExceptionHandler.java
[core-security]: ../services/core/src/main/java/com/libra/streaming/core/config/SecurityConfiguration.java
[core-topics]: ../services/core/src/main/java/com/libra/streaming/core/events/domain/CoreEventTopic.java

## Limits

- **Remote CI not run.** The workflow passed actionlint, and `verify-local.ps1` reproduced its steps on one Windows
  workstation. It has not run on GitHub's ubuntu-24.04 runners. The 80 min budget, the FFmpeg 6 package and
  PowerShell on the runner are expectations, not observations.
- **Pinning.** Actions are pinned by commit and Java by major version through `setup-java`. FFmpeg is pinned only to
  the ubuntu-24.04 archive and its major version, so a point update reaches CI without a change here. The workstation
  used FFmpeg 9.0.2; the major-version assertion exists only in the workflow. The Media image installs FFmpeg from the
  archive of its `eclipse-temurin:21-jre` base, whose tag is not pinned by digest, and its version is not recorded.
- **The secret scan is a heuristic.** The evidence gate recognizes the shapes listed above. A secret in another shape,
  a short secret or one split across fields would pass it. The real control is that the writers emit fixed check
  strings.
- **Freshness.** The run identity and modification times prove that the evidence came from this run and is newer than
  the JARs. They do not prove that the JARs were built from this commit.
- **Accepted flow only.** The CI and handoff smoke is the M29 slice. The M30 abuse matrix (it waits out a 5 min
  ticket), the M33 browser slice (installed Chrome, `pnpm install`) and the M34 fault matrix remain manual runners.
- **Environment isolation.** `verify-local.ps1` clears six prefixes during Maven. Other workstation variables (proxy,
  `JAVA_TOOL_OPTIONS`, Testcontainers settings) still reach the build, which Docker access requires.
- **Intermittent outbox recovery test.** In one later `verify-local.ps1` run on 2026-10-26 (20:04),
  `OutboxProcessRecoveryIntegrationTest` failed in its setup after 2.4 s. The cause was an empty
  `leases.claim(...).orElseThrow()` right after queueing a job. Both the queue insert and the claim read PostgreSQL's
  `clock_timestamp()`, and no worker was running. So only a backward step of the container clock, or a transiently
  held row lock, could empty the claim. Neither could be confirmed afterwards. The test passed in the three full M35
  runs above and in three isolated reruns (`tmp/m35-outbox-recovery-rerun1..3.log`). The gate failed closed: it named
  the suite and deleted `tests.json`. Production is unaffected, because the worker polls every second. A bounded wait
  for the setup claim is left to M36.
- **Unchanged from M34.** One instance of each service, one broker, coarse crash points, no metrics scrape endpoint,
  no retention policy for failed assets, and Core and Media deployed together.
- **Next.** Per the plan, M36 is final acceptance and evidence hardening. It tests the verification tools' own failure
  paths (missing report, skipped suite, absent Docker/FFmpeg/storage, failed smoke cleanup, stale PASS) and reviews
  the whole change against the original scope. It also stabilizes the outbox recovery test's setup claim.
