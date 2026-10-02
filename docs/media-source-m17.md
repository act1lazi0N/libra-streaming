# Media: Verified immutable source freezing (Milestone 17)

Verification date: 2026-10-02.
Status: VERIFIED_LOCAL (stage only; see Scope).

## Scope

M17 adds the source freezing stage that the later pipeline will run first. It reads
the mutable staging object once, proves the bytes, stores a private copy under an
attempt-specific key and commits that key as the asset's selected source under the
current lease. It adds no schema, setting, API or Kafka event.

The stage is a Spring bean (`SourceFreezer`) but **no production job handler is
installed**. The shipped runtime still has no `MediaJobHandler`, so enabling the worker
still fails startup and admitted jobs stay `QUEUED`. Probing (M19), FFmpeg, HLS, READY
and playback remain later milestones, and a frozen source is never READY or playable.
Tests drive the stage through the real lease port and, once, through the real worker with
a fixture handler that is not part of the shipped artifact.

## Behavior

1. **Lease check first.** `JobSources.target` returns the upload's declared length, expected
   SHA-256 and staging key only for the current unexpired owner. A stale worker never starts
   reading.
2. **Bounded copy.** Staging streams through a 64 KiB buffer into a scratch file, counting and
   hashing exactly the bytes read. It reads at most `declared + 1` bytes (and never more than
   `libra.media.storage.max-object-bytes + 1`), so an oversized object is proven with one extra
   byte and the rest is aborted. Memory does not scale with the object.
3. **Reject before storing.** Missing → `SOURCE_MISSING`; short or long → `SIZE_MISMATCH`;
   same length, different bytes → `CHECKSUM_MISMATCH`. All are permanent (`FAILED_PERMANENT`)
   and nothing is stored or selected. S3 ETags are not used as the checksum.
4. **Private attempt key.** The copy goes to `<source-prefix><uploadId>/attempt-<n>/source.mp4`.
   The upload signer only signs `<staging-prefix><uploadId>/source.mp4`, so no issued grant can
   address a frozen object. A late object from an old attempt has a different key.
5. **Read back.** The stored object is streamed back and hashed. A different readback is a
   storage fault (retryable `PROCESSING_FAILED`), not an input rejection.
6. **Fenced commit.** `JdbcJobSources.select` locks the job with the same predicate as renew, retry,
   fail and release (`LeaseFence`, shared with `JdbcJobLeases`), judges expiry after the lock wait,
   sets `media_assets.selected_source_key` only while it is `NULL`, and moves the job to
   `SOURCE_SELECTED`. It does not touch the aggregate version or outbox. Selecting the same key again
   is idempotent; a different key is an error; the existing `UNIQUE (selected_source_key)` keeps a key
   from belonging to two assets.
7. **Retry reuses the selection.** A job reclaimed after selection re-verifies the selected object
   (length and SHA-256 by streaming) and never reads staging again. If the frozen object is missing or
   differs it fails permanently; it is not rebuilt from staging that may since have changed.
8. **Cancellation and loss.** Both streaming loops poll the worker's cancellation flag. A lost lease
   returns `LeaseLost` and records nothing. Scratch files are removed on every path.

## Orphan categories for the cleanup milestone

Storage and PostgreSQL are not one transaction, so these can remain and are never selected:

- `sources/<uploadId>/attempt-<n>/source.mp4` stored by an attempt that lost its lease before commit.
- Frozen objects of assets that later failed or were exhausted.
- Staging objects after freezing (staging is deliberately left in place).
- Scratch files if the process is killed mid-copy (not swept at startup yet; M18 owns this).

## Verification

| Evidence | Dependency | Result |
| --- | --- | --- |
| `SourceFreezerTest` (11) | In-memory fake ports | Rejection matrix, retryable storage faults, readback fault, stale lease, lease lost at commit, cancellation, reuse without reading staging, no repair from changed staging. |
| `JobSourceSelectionIntegrationTest` (8) | Real PostgreSQL 18.6 | Current owner commits one selection with no aggregate/outbox change; same key idempotent, other key refused; expired and reclaimed owners denied; selection survives release and is visible to the next claim; unique key across assets; invalid keys; expiry judged after a row-lock wait. |
| `MediaStorageIntegrationTest` (+4, 15 total) | Real SeaweedFS 4.46 | Exact bytes and SHA-256; stops at `declared + 1`; bounded by the object limit; missing object; cancel and offline leave no scratch file; frozen key outside staging; store and bounded digest round trip. |
| `SourceFreezingIntegrationTest` (6) | Real PostgreSQL and SeaweedFS | Frozen bytes equal staged bytes and the key is selected; staging overwritten and deleted after selection does not affect retry; five rejection cases store nothing; a real presigned upload URL can rewrite staging but a rewritten URL for the frozen key or another attempt is 403; an attempt that loses its lease before commit leaves an orphan and cannot replace the successor's selection; the real worker retries using the committed source with staging gone. |

The refactor that moved the lease predicate into `LeaseFence` is covered by the existing M15/M16 PostgreSQL
and packaged-process tests, which ran unchanged in the reactor.

Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 7m41s). The report gate
confirmed 47 suites and 298 tests with zero failures, errors or skips: Core 186, Media 110, recommendation
and analytics 2. All 18 contracts passed and `git diff --check` was clean. M17 added 29 tests (11 unit,
8 PostgreSQL, 4 SeaweedFS adapter, 6 combined). Raw log: `tmp/m17-reactor-verify.log` (ignored).

## Limits

- Not measured: heap usage. Boundedness comes from the 64 KiB buffer and file-backed upload, and is
  asserted by the bytes actually read (`declared + 1`), not by a memory profile.
- A 256 MiB object is hashed twice (copy and readback) plus read once for each retry's re-verification.
- Fixtures are small synthetic bytes, not valid MP4. Container and codec validation is M19.
- S3 has no write-once guarantee here; immutability rests on no code or signed grant addressing the key
  after selection. Disk exhaustion and truncated reads are covered only through the transient
  `Unavailable` path; deliberate fault matrices belong to M18.
- Local containers only: not multi-host, not a storage fault injector, not remote CI or deployment.
- GitNexus was consulted for symbol and change impact, but its index predates M16 and M17, so graph
  results are supplementary to source review and the executable tests.

Run with Java 21 and Docker Desktop:

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
```
