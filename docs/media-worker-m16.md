# Media: Lease recovery hardening (Milestone 16)

Verification date: 2026-10-02.
Status: VERIFIED_LOCAL.

## Scope

M16 hardens the M15 worker and lease lifecycle. It adds no schema, setting,
handler or API. Source freezing, probing, FFmpeg, HLS, readiness events and
playback remain later milestones, and the shipped runtime still has no job
handler. An admitted job is never READY or playable.

## Invariants checked

- **One fenced owner.** A claim allocates a new random token and increments the
  durable attempt. Renew, retry, fail and release each lock the job and require
  the current job, upload, asset version, attempt, token, an active stage and a
  lease later than PostgreSQL time. A stale worker is denied in every active
  stage (`CLAIMED`, `SOURCE_SELECTED`, `TRANSCODING`, `FINALIZING`). Denied
  writes leave the successor's job row unchanged and select no source, output or
  manifest, and add no outbox event.
- **Expiry is judged after the lock wait.** A renewal blocked on the row lock
  re-reads database time once it acquires the lock. If the lease expired while
  it waited, it is rejected and a successor can claim.
- **Three claims at most.** Repeated transient failure, repeated graceful
  cancellation and repeatedly interrupted claims all end in
  `EXHAUSTED` / asset `FAILED` / `RETRY_EXHAUSTED`. Release does not reset the
  attempt budget. A fourth claim is never admitted.
- **No immortal PROCESSING.** Graceful cancellation releases only a
  still-current lease. A lost lease cancels the handler and records nothing. A
  handler failure is a retry or a terminal failure. Each path either requeues
  with a bounded budget or reaches a terminal state.

## Repair

Before M16 the lease adapter had no bound on waits. A renewal queued behind a
row lock held by another session could wait indefinitely and kept the heartbeat
thread stuck, so the worker never noticed that it had lost authority. The JDBC
lease adapter now runs each transaction with a five second timeout. A blocked
renewal fails with a data-access error, the worker reports
`WORKER_LEASE_UNAVAILABLE`, cancels the handler and does not touch the
successor. The lease duration (at most five minutes) is unchanged, so the lease
can still expire and another instance can reclaim the job.

## Verification

| Evidence | Dependency | Result |
| --- | --- | --- |
| `WorkerRecoveryTest` | Mocked lease port | Database loss cancels a late outcome without a retry or release; shutdown is bounded for a handler that ignores interruption; a delayed heartbeat cannot interrupt the following job. |
| `JobRecoveryIntegrationTest` | Real PostgreSQL 18.6 | Renewal re-checks time after a lock wait; blocked renewal times out; a killed connection leaves a recoverable lease; a real worker cancels on a lost renewal connection and keeps its budget; every active stage rejects old writers; an uncooperative shutdown yields to a successor; exactly three attempts are admitted; repeated release exhausts the budget. |
| `WorkerProcessRecoveryIntegrationTest` | Real PostgreSQL 18.6 and the packaged Media JAR | The JAR worker is killed twice with `destroyForcibly`. Replacement processes recover the same durable job at attempts 2 and 3 using the real persisted expiry. The old lease cannot renew, retry, fail or release. The third attempt ends in `FAILED` / `RETRY_EXHAUSTED`, with no fourth claim, source, output, READY state or outbox row. |

The handler in the process test is a fixture class loaded from a temporary JAR
through `PropertiesLauncher`; it is not in the shipped artifact. The process test
runs in the Failsafe phase because it needs the packaged JAR.

## Limits

- The kill test uses PostgreSQL in a container and loopback processes. It is not
  a multi-host or network-partition experiment.
- Database loss is simulated by terminating a backend and by holding a row lock.
  It does not stop the server or exercise connection pool failover.
- Worker clocks are never trusted. Controlled-clock behavior is exercised by
  writing expiry through PostgreSQL time and not by changing host clocks.
- Local tests do not establish remote CI, production deployment, actual media
  processing or storage integration for this milestone.

Java 21.0.10 offline root `clean verify` passed all three backends (BUILD SUCCESS,
6m51s). The report gate confirmed 44 suites and 269 tests with zero failures, errors
or skips: Core 186, Media 81, recommendation and analytics 2. All 18 contracts
passed. M16 added 12 tests (3 worker, 8 PostgreSQL, 1 packaged process).

Run with Java 21 and Docker Desktop:

```powershell
.\mvnw.cmd clean verify
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_contracts.py
tmp/milestone8-tools/Scripts/python.exe infra/ci/check_test_reports.py
git diff --check
```
