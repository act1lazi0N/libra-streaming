# Media: Staging mutation and source snapshot recovery (Milestone 18)

Verification date: 2026-10-02.
Status: VERIFIED_LOCAL.

## Scope

M18 hardens the M17 source freezing stage. It adds no schema, setting, API or event, and still installs no
production job handler. A frozen source is never READY or playable.

## Gate

Every retry reads the same committed bytes, or the job has no selected source and re-enters freezing from
staging under the same checksum rule. Storage and PostgreSQL are never assumed to be one transaction: every
storage write happens before the fenced database commit, and a write that loses its lease is simply never
referenced.

## Findings and repairs

| Finding | Repair | Regression evidence |
| --- | --- | --- |
| The SDK call timeout ends when headers arrive, and the socket timeout bounds each read only. A storage endpoint dripping one byte per read could hold the single worker slot forever while the healthy heartbeat kept renewing its lease. | Each staging copy and readback has a total deadline equal to `request-timeout`, the bound that already applies to the whole frozen-object PUT. A breach is a transient `PROCESSING_FAILED`. | With the deadline removed, the drip case ran past its 15 s preemptive timeout. With the repair, two drips end in about 4 s. |
| A full scratch disk was only discovered mid-copy. | `ScratchSpace.requireSpace` checks the usable space for `min(declared, max-object-bytes) + 1` before any request is sent. A shortfall is transient. | No GET is sent when space is short, and no partial file is created. |
| Scratch files from a killed process were never removed. | Each instance owns `instance-<uuid>/` under the scratch root and holds an OS file lock on `instance-<uuid>.lock`. On startup, every directory whose lock can be acquired is deleted, because its owner is dead. No age threshold is used, so a live owner in this JVM or another process is never swept. Spring releases the lock on context close. | A child JVM holding a partial copy was `destroyForcibly`-killed. A neighbour started while the child was alive left the copy alone, and the next instance after the kill reclaimed it. |
| An early exit closed an unfinished response stream. | Every early exit now aborts the response instead of closing it. | Defensive only: with `UrlConnectionHttpClient`, `close()` did not drain the remaining body even without this change, so the cancellation test passes either way. |

`put` still accepts only regular files under the configured scratch root, as it did before per-instance
directories existed.

## Failure matrix

Real PostgreSQL 18.6 and SeaweedFS 4.46 (`SourceSnapshotRecoveryIntegrationTest`). A crash is simulated
in-process: the attempt is abandoned with an `Error` at the checkpoint and nothing is released or recorded.
Its lease is then expired through PostgreSQL time, which is what a killed worker leaves behind.

| Crash point | Left behind | Successor result |
| --- | --- | --- |
| After download, before store | No object, no selection | Re-freezes from staging and selects attempt 2 |
| After store, before readback | Verified object at attempt 1, unselected | Selects attempt 2; attempt 1 is an orphan with correct bytes |
| After readback, before commit | Same as above | Same as above |
| After commit | Attempt 1 selected | Reuses attempt 1 with staging deleted, never reading staging |

Additional cases:

- **Uncommitted crash, then staging changed:** the successor rejects with `CHECKSUM_MISMATCH` (permanent). The verified orphan is never adopted as a shortcut.
- **Old attempt still uploading when reclaimed:** the successor selects attempt 2. The old attempt then finishes its PUT and readback, but `select` returns false (`LeaseLost`) and its renewal is denied. Selection and bytes are unchanged.
- **Staging overwritten mid-download** (900 KB object, overwritten on the third buffer poll): the invariant is checked for any outcome. A `Frozen` result must hold exactly the declared bytes; otherwise nothing is selected. In the recorded run, SeaweedFS served the original body and the outcome was `Frozen`.
- **Staging overwritten after download, before store:** the snapshot holds the downloaded, verified bytes.
- **Retry stability:** attempts 2 and 3 return the same key and SHA-256 while staging is overwritten and then deleted. Only one frozen object exists.
- **Committed snapshot lost or altered:** fails permanently with `SOURCE_MISSING` or `CHECKSUM_MISMATCH`. The selection is not moved, and staging (still correct) is not used to rebuild it.

Loopback S3 stand-in, real SDK client and adapter (`SourceStorageFaultTest`). SeaweedFS cannot be made to
truncate, stall, drip or lie, so these cases use a scripted HTTP server:

- **Truncated body** (declared 4096, sent 2048, then dropped): transient, not a smaller object.
- **Shorter `Content-Length`, fully delivered:** read as the object's real length, so `SIZE_MISMATCH` and no store.
- **Forged ETag, `x-amz-checksum-sha256` and SHA metadata on different bytes:** `CHECKSUM_MISMATCH` and no store. Only bytes decide.
- **Dripping body:** cut off at the deadline for both copy and readback.
- **Stalled body:** fails at the read timeout.
- **Cancellation during a drip:** returns promptly.
- **Oversized body:** aborted after one extra byte, although the server would drip for minutes.
- **Exhausted scratch:** no request is sent, the failure is transient, and no partial file remains.

Process and file-lock evidence (`ScratchSpaceTest`): per-instance ownership, close cleanup, live owners in the
same JVM untouched, dead owners reclaimed (including an interrupted reclaim that left only a lock file),
unknown directories without a lock file left alone, the space check, and the killed child JVM described above.

## Orphan inventory for the cleanup milestone

| Category | Shape | Safe to delete when |
| --- | --- | --- |
| Stored but uncommitted attempt (crash, lost lease, late PUT after a client timeout) | `<source-prefix><uploadId>/attempt-<n>/source.mp4` not equal to `selected_source_key` | Attempt `n` no longer holds the current lease (a lower attempt, or the job is terminal). `select` is fenced, so it can never become selected afterwards. |
| Frozen source of a terminally failed asset | Selected key of a `FAILED` asset | Retention policy for failed assets; nothing reads it. |
| Selected key whose object was lost | Row points at nothing; job `FAILED_PERMANENT` / `SOURCE_MISSING` | Operator decision; no automatic rebuild. |
| Staging objects | `<staging-prefix><uploadId>/source.mp4` | Not on expiry alone: an admitted PUT can complete after its URL expires (M12), so reconcile late writes. |
| Scratch of dead instances | `instance-<uuid>/` and lock file under the scratch root | Reclaimed automatically when the next instance starts on the same root. |
| Directory without a lock file | Anything else under the root | Never touched automatically. |

## Verification

| Evidence | Dependency | Tests |
| --- | --- | --- |
| `ScratchSpaceTest` | Real file system, OS file locks, killed child JVM | 5 |
| `SourceStorageFaultTest` | Loopback scripted HTTP server, real AWS SDK and adapter | 8 |
| `SourceSnapshotRecoveryIntegrationTest` | Real PostgreSQL and SeaweedFS | 10 (4 crash points + 6) |

`SourceFreezingIntegrationTest` (M17) now shares `SourceStorageFixture`, which starts the containers once per
JVM so the cached Spring context never outlives them. Scratch assertions count `.part` files recursively.

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 7m37s). The report gate
confirmed 50 suites and 321 tests with zero failures, errors or skips: Core 186, Media 133, recommendation
and analytics 2. All 18 contracts passed and `git diff --check` was clean. M18 added 23 tests. An earlier
attempt failed before running Media tests because `clean` could not delete `services/media/target` while a
shell held it as its working directory; it is not counted. Raw log: `tmp/m18-reactor-verify.log` (ignored).

## Limits

- Crash points are simulated in-process. Only scratch cleanup is proven with a real process kill, because
  freezing keeps no other process-local state.
- The mid-download overwrite outcome depends on SeaweedFS buffering. The invariant is asserted for any outcome,
  but only one outcome (`Frozen`, original bytes) was observed.
- Disk exhaustion is exercised through the preflight seam, not by filling a disk. An `ENOSPC` during a write
  follows the same `IOException` → transient → cleanup path as a truncated read.
- A worst-case stalled transfer takes about `request-timeout` plus one read timeout. Repeated crash-restarts
  within one instance lifetime are bounded only by the space check and the three-attempt budget.
- If an instance is killed between creating its lock file and locking it, another instance might reclaim it.
  The owner then works without a reclaimable lock until it exits, which is harmless but leaves its directory to manual cleanup.
- Local containers and loopback only: not multi-host, not remote CI or deployment. GitNexus was consulted for impact and change
  scope, but its index is one commit behind and new classes are not in it, so graph results are supplementary.

Run with Java 21 and Docker Desktop:

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
```
