# Media: Lifecycle cleanup and operational visibility (Milestone 31)

Verification date: 2026-10-18.
Status: VERIFIED_LOCAL (Media's adapters against real PostgreSQL 18.6 and SeaweedFS 4.46 through Testcontainers, the
Core client and contracts against their own suites; Docker Desktop on one Windows workstation; no Compose smoke).

## Scope

M31 delivers what the earlier milestones deferred to it: abandoned uploads are expired through the contract
transition (`OPEN -> EXPIRED`, asset `UPLOADING -> FAILED` with its FAILED event, promised in
[the first-slice contracts](media-first-slice-contracts.md) and open since M23), the objects of attempts that were
never selected are deleted (the orphan inventory of [M18](media-snapshot-m18.md), accumulating since
[M23](media-finalization-m23.md)), and the upload status and Media's metrics tell an operator where a job stands. It
adds the `lifecycle` package, `V8__add_media_lifecycle_cleanup.sql`, `S3CleanupStorage`, and moves
[`media-internal-uploads.v1.yaml`](../contracts/http/media-internal-uploads.v1.yaml) and
[`core-media-uploads.v1.yaml`](../contracts/http/core-media-uploads.v1.yaml) to 1.1.0. Core's Media client and its
admin upload status relay the new fields. There is no event contract change.

## Gate

A known abandoned attempt can be removed without touching a live upload, a current worker, a selected source or
output, or an unrelated prefix; operators can tell queued, processing, retrying, failed and unpublished work apart.

## What changed

### Upload expiry

The sweeper expires, in one transaction per batch, uploads still `OPEN` at their deadline (the database clock, as for
every lease): the upload becomes `EXPIRED`, its asset `FAILED`, and `MediaAssetEvents` appends the FAILED event with
the next aggregate version. Completion already locks the same row and judges the deadline after the lock, so an upload
is either submitted or expired, never both; the expiry query uses `FOR UPDATE ... SKIP LOCKED` and never waits on a
completion. Completion and `upload-url` on an expired upload answer `410 UPLOAD_EXPIRED` (before M31 they could only
see `OPEN` past its deadline), and the status reports the contract's `EXPIRED / FAILED / UPLOAD_EXPIRED` shape.

### Scoped cleanup

Eligibility is derived only from durable state and two deadlines, never from an object's age:

| Upload | Settled at | Deleted | Kept |
| --- | --- | --- | --- |
| `EXPIRED` (no job) | its deadline | the staging object | nothing else exists |
| `SUBMITTED`, job `SUCCEEDED` (asset READY) | later of the deadline and the terminal transition | staging; the source and output prefix of every attempt but the selected one | selected source, selected output |
| `SUBMITTED`, job `FAILED_PERMANENT` or `EXHAUSTED` | same | staging; every output prefix; every unselected source | the selected source, if any (retention of failed sources is a limit) |
| Anything else (`OPEN`, a queued or leased job) | not settled | nothing | everything |

An upload is due once it has been settled for `MEDIA_LIFECYCLE_WRITE_GRACE` (default 1 h, at least 5 min): the
grace covers a browser PUT admitted before the deadline (M12) and a storage request of a worker whose lease had
already run out. The keys an attempt may have written are named from the job's attempt count by `MediaObjectKeys`, the
same definition the worker uses (`<source-prefix><uploadId>/attempt-<n>/source.mp4`,
`<hls-prefix><uploadId>/attempt-<n>/`); output prefixes are listed only beneath `attempt-<n>/`, never an upload
directory, area or bucket, and every listed key is checked to lie under that prefix. `ObjectCleanup` never names the
selected source key or an output prefix overlapping the selected one.

A pass that deleted anything does not finish the upload: `objects_cleaned_at` is written only by a later pass that
finds nothing left (and no bounded prefix still holding objects), so a late write that lands after the first delete
is found and deleted rather than orphaned. Deletes are idempotent; an interrupted pass is simply repeated.

Why nothing deleted can become selected: a settled upload is expired (no job) or has a terminal job, V8 makes a
terminal job's stage, attempts, lease and failure final, M24's triggers make READY and every selection immutable, and
the lease fence refuses any selection without an active lease. V8 also refuses `objects_cleaned_at` for an upload
that is not settled, and refuses `EXPIRED` unless the asset fails with it and no job exists.

Taking a batch records `cleanup_attempted_at` and the next batch is taken least recently attempted first, so an
upload that keeps failing (a staging key outside today's staging area after a prefix change, an object storage refuses
to delete) goes to the back of the queue instead of holding the front of every batch. A storage outage ends the pass;
any other failure of one upload is counted and the pass continues.

`LifecycleSweeper` runs one bounded batch of each per poll (`MEDIA_LIFECYCLE_POLL_INTERVAL` 1 m,
`MEDIA_LIFECYCLE_BATCH_SIZE` 20, `MEDIA_LIFECYCLE_MAX_OBJECTS_PER_PREFIX` 1000). It is off by default
(`MEDIA_LIFECYCLE_ENABLED`) and on in Compose and `.env.example`; any number of instances may run it.

### Status (contracts 1.1.0)

`MediaStatus` and Core's `UploadStatus` gain two required fields:

| Field | Meaning |
| --- | --- |
| `processing` | `null` before submission and for an expired upload; otherwise `{stage, failureStage, failureCode, retryAt}`: the job's stage, the stage and code of its last recorded failure (a pending retry, or the terminal one) and, while a retry waits, when it is due. Fixed codes only. |
| `unsentEvents` | MediaAssetStateChanged events of this asset version that Kafka has not acknowledged yet. |

`failureCode` keeps its meaning (the asset's terminal failure, or `UPLOAD_EXPIRED`); the retry diagnostics M15 kept
internal are now in `processing`, as the plan asked of M31. `media_jobs.failure_stage` (V8) records the stage a
failure happened in; a claim clears it with the code, and exhaustion by an expired last lease records that lease's
stage. Core's client rejects unknown and missing fields, so a 1.0.0 peer and a 1.1.0 peer refuse each other's
status in both directions: **Core and Media are deployed together** (the Compose stack builds both from one tree).

### Metrics

`LifecycleMetrics` reads gauges from PostgreSQL when sampled (NaN, never zero, when it cannot read):
`libra.media.jobs{stage=queued|retrying|processing|succeeded|failed|exhausted}`,
`libra.media.jobs.unfinished{attempts=0..3}`, `libra.media.jobs.terminal{failure=<code>}`,
`libra.media.jobs.queue.age`, `libra.media.jobs.unfinished.age`, `libra.media.jobs.duration.max` (admission to
READY, last hour), `libra.media.uploads{state=abandoned|expired}` and `libra.media.cleanup.pending` (settled uploads
not yet cleaned, the orphan backlog). Counters: `libra.media.lifecycle.expired`, `libra.media.cleanup.deleted`,
`libra.media.cleanup.cleaned`, `libra.media.lifecycle.failures{failure=<code>}`. Every label is a fixed value; M26's
outbox gauges already cover unpublished events across all assets.

## Findings of the review

A code-reviewer agent reviewed the first full draft. Its findings and their disposition:

| Finding | Disposition |
| --- | --- |
| R1. Contract and doc said Core could be deployed first, but the hardened client also fails on *missing* fields, so neither order works. | The contract and this doc now require Core and Media to be deployed together; a transitional lenient client would weaken the M14 client hardening (Limits). |
| R2. Plans were taken in deadline order and nothing recorded a failed attempt, so one upload that always fails (or a batch of them) would hold the front of every pass. | `cleanup_attempted_at` rotates the queue (above); test `anUploadThatKeepsFailingDoesNotHoldTheQueue`, mutant J10. |
| R3. Every unsettled upload in the suite had a future deadline, so dropping the terminal-job condition (mutant J4, which first **survived**), the SUBMITTED grace, or `j.updated_at` from the settlement went unnoticed. | `unfinishedJobsAreNeverDueHoweverOldTheirDeadline`: deadlines three hours back with a waiting retry, a held lease, a lapsed lease and a job terminal a moment ago; J4 and J9 are now killed. |
| R4-R7 (nits). `MediaAssetEvents` became public for the expiry; `cleanup.pending` repeats the settlement predicate without the grace; the queued branch of the exhaustion stage is defensive only; gauge queries have no statement timeout. | The comment now says defensive; the others are kept and the last one is a limit shared with M26's gauges. |

## Evidence

**Real storage and database** (`LifecycleCleanupIntegrationTest`, 9 tests, PostgreSQL 18.6 and SeaweedFS 4.46): an
abandoned upload expires once with exactly one FAILED event while a live upload and a submitted one with a past
deadline do not, and completion and grants then answer `UPLOAD_EXPIRED`; a READY upload whose first attempt failed
loses its staging object, attempt 1's source and output, and nothing else (attempt 2's source and output and a key
no attempt reserved stay), while an open upload, a worker holding a lease and a terminal upload inside its grace are
untouched and the lease still renews; a staging object written again after the first delete is found by the next
pass, and only the pass after that records the upload; a failed asset keeps its selected source and loses its output;
an expired upload loses its staging object only after the grace from its deadline; uploads with a waiting retry, a
held or lapsed lease, or a job terminal a moment ago are never due however old their deadline; a failing upload does
not hold the queue; the retry view and exhaustion stage; V8's refusals; and the storage adapter's refusal of keys
outside the named attempt, including the sibling `attempt-1x`.

**HTTP and Core**: `UploadControlIntegrationTest` (2 new) checks the exact status key set, the retry view and the
expired upload's contract shape and 410s through Media's controller; `MediaControlClientTest` (1 new) has Core accept
a consistent processing view and refuse a missing one, a stage contradiction, `UPLOAD_EXPIRED` as a job failure, an
unknown key and a negative count. Unit suites: `ObjectCleanupTest` (8), `LifecycleMetricsTest` (2, NaN on an
unreadable database and only fixed labels), `LifecycleArchitectureTest` (2). Python contract tests: one new case for
the 1.1.0 processing view; the M02 hardening examples carry the new fields.

**Mutants**, each run against `LifecycleCleanupIntegrationTest` and `UploadControlIntegrationTest` and each killed by
a failing assertion (not a build error): J1 cleanup deletes the selected source; J2 no grace; J3 the first pass is
final; J4 unfinished jobs are due (survived the first draft, see R3); J5 expiry without its event; J6 expiry an hour
early; J7 an output listing without the trailing slash and without the key check; J8 no failure stage; J9 grace from
the deadline only; J10 no queue rotation; D1 no terminal-job trigger; D2 no cleanup-mark trigger
(`tmp/m31-mutation-*.log`).

## Verification

| Evidence | Dependency | Checks |
| --- | --- | --- |
| `LifecycleCleanupIntegrationTest` | PostgreSQL 18.6, SeaweedFS 4.46 | 9 (new) |
| `UploadControlIntegrationTest` | PostgreSQL 18.6, Media's controller and security | 15 (2 new) |
| `ObjectCleanupTest`, `LifecycleMetricsTest`, `LifecycleArchitectureTest` | In-memory ports, mocked JDBC, ArchUnit | 8 + 2 + 2 (new) |
| `MediaControlClientTest` (Core) | Local HTTP server | 14 (1 new) |
| `test_media_contracts.py`, `test_media_contract_hardening.py` | Contracts only | 1 new case; 16 Python gate tests in all |
| Mutants J1-J10, D1-D2 | `LifecycleCleanupIntegrationTest`, `UploadControlIntegrationTest` | 12 killed |

A ci-gate agent ran every `.github/workflows/core-ci.yml` stage on the final tree (`tmp/ci-gate/m31-*.log`): Java 21
offline root `clean verify` passed all three backends (BUILD SUCCESS, 12m13s); the report gate confirmed 89 suites and
634 tests with zero failures, errors or skips: Core 188 (45 Surefire, 143 Failsafe), Media 444 (238 Surefire, 206
Failsafe), recommendation and analytics 2. The 16 Python gate tests passed, `check_contracts.py` passed 22 checks,
`docker compose config --quiet` with `.env.example` passed with and without `--profile full`, the packaged Core
smoke passed, and `git diff --check` was clean. Against M30 that is four new suites and 24 new tests (Core 1, Media
23). Everything ran on the uncommitted tree with offline Maven and Docker Desktop on Windows, not on CI's runner.

Work was divided among agents: a doc-finder agent collected the contract promises and orphan inventory, a
code-reviewer agent reviewed the first full draft (R1-R7), and a ci-gate agent ran the gate.

## Limits

- The grace is an operational bound, not a proof: a write admitted before settlement that is still in flight after
  the grace (a stalled browser PUT, a worker paused longer than its lease plus the grace) lands after cleanup has
  recorded the upload, and stays. M32 races cleanup with these writers.
- A failed asset's selected source is kept; there is no retention policy for failed assets yet, and no READY output
  is ever deleted (no replacement of a READY version exists).
- Scratch directories are still reclaimed only at startup (M18); a leftover scratch file inside a live instance is
  not swept.
- The metrics are registered in Media's meter registry; Media exposes only health and info over HTTP, so a scrape
  endpoint (and its access control) belongs with the deployment's monitoring, as for M26's outbox gauges.
- Core's admin status relays `processing`, but no client shows it yet; there is no manual retry.
- Core and Media must be deployed together for the 1.1.0 status (R1); there is no transitional client.
- Gauge queries run on the sampling thread without their own statement timeout, as M26's outbox gauges do.
- No Compose smoke runs the sweeper; it is enabled in Compose and covered by the integration suite only.
