# Web: Browser Hardening on the Real Stack (Milestone RA03.5)

Verification date: 2026-11-22.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA03.5 hardens the viewer app of [RA03.1](web-session-ra03-1.md)–[RA03.4](web-resume-ra03-4.md) against slow
networks, duplicate actions, concurrent refresh, profile switches mid-request, closing the tab, Premium ending,
logout elsewhere and playback denial, verified in a real browser against real protected HLS.

Done condition (plan): no retry loop; no progress sent under the wrong profile or session; verified with a browser and
real HLS.

Contract and migration changes: none. Core change C1 (see [RA03.1](web-session-ra03-1.md#finding-c1-core-the-csrf-cookie-was-deleted-on-every-authenticated-request)).

## Method

`infra/smoke/check-web-ra03.ps1` runs `check-media-m29.ps1 -Milestone RA03` on a disposable Compose project: the real
`infra/compose.yaml` and `infra/nginx/nginx.conf`, the **real web image** built from `web/Dockerfile` (Next.js
standalone), the Core, Media and Analytics JARs from `clean verify`, PostgreSQL, Kafka, Mailpit and SeaweedFS behind
the storage gateway. Every key is generated per run; Node and Chrome see only `LIBRA_RA03_*` addresses and the seed
file path. The ingress listens on a loopback port fixed before startup so Core's public URL and Media's browser
origin name the page's exact origin.

`infra/smoke/web_ra03_seed.py` creates through the ingress, with real uploads, FFmpeg transcoding and publication: a
40 s Free movie, a 12 s Premium movie, a series with one season and two episodes, a published-then-hidden movie; and
three verified Premium viewers with two profiles each, one verified Free viewer and one unverified viewer. Core limits
login to 10 per email per 15 min and registration to 10 per IP, so tests are spread over the viewer pool and
`support.ts` fails a run that would log one account in more than 8 times.

`web/e2e/ra03/*.spec.ts` (Playwright 1.64.0, installed Google Chrome headless, hls.js 1.7.3) drive the product UI by
`data-testid`. Traffic is recorded as method, path template, status and whitelisted progress fields only; no trace,
video, screenshot, HAR, header, cookie or authentication body is kept. The evidence
`target/verification/web-ra03.json` (`WEB_RA03_BROWSER_PASS`) is written only when all 18 tests pass and lists every
mocked, delayed and dropped step.

## Matrix (18 tests)

| # | Case | Result |
|---|---|---|
| 1 | Login through `/login` | header shows the account from `/me`; both profiles listed |
| 2 | Concurrent refresh: access cookie removed, two tabs reload at once | exactly one `POST /auth/refresh`; `/me` stays 200 (family not revoked) |
| 3 | Profile switch mid-request (A's continue-watching **delayed** 3 s) | request aborted; A's item never rendered for B (6 s) |
| 4 | Hidden content | not in the grid; detail shows `title-not-found` |
| 5 | Premium label vs right to play | Free viewer: badge + `PREMIUM_REQUIRED`, no play button; Premium viewer: enabled |
| 6 | Play from the detail page | real HLS, every stream response 200, one admission, first report PLAYING, no CSRF rejection |
| 7 | Seek while playing | accepted SEEKING; Core `watchedMs` ≤ measured playing time + 1.5 s; qualified at 30 s |
| 8 | Progress to Analytics | admin statistics via Core AVAILABLE/STALE with watch time and a qualified view |
| 9 | Last heartbeats lost (context **offline**, tab closed) | reopen resumes within 2.5 s of the last accepted position |
| 10 | Next episode | offered at the end; plays episode 2; two admissions |
| 11 | Binding changed (**mocked** one 409) | exactly one re-admission, new session, playback continues |
| 12 | Slow network (**delayed**: 400 ms, ~1.5 Mbps) | plays after one admission; no endpoint in a loop |
| 13 | Double click on play | exactly one admission |
| 14 | Close the tab while playing | history within 3 s of the last observed position (keepalive flush) |
| 15 | Premium ends mid-playback (**mocked** one 403) | `player-denied`; no admission/progress/renew for 20 s |
| 16 | Logout in another tab | playing tab stops within 20 s; ≤ 1 refresh; nothing after the first 401 |
| 17 | Denial: unverified account; Free account on Premium | `player-denied`, one admission each, no ticket cookie |
| 18 | Profile switch while playing | at most one PAUSED flush for A's session; B's list unchanged |

Mocked steps exist only where the live stack cannot produce the condition: Core refuses replacement media for
published content (`UPLOAD_NOT_ALLOWED`), and a simulated Premium cannot expire during a run. Everything the page does
in reaction is real.

## Findings

Found by running the suite and by an independent review; each fixed and covered.

| Id | Finding | Fix |
|---|---|---|
| C1 | Core deleted the CSRF cookie on every authenticated request, so every POST was 403 then retried | `NullAuthenticatedSessionStrategy`; Core IT reproduced first; browser guard in tests 6/7/10/12 |
| P1 | `web/Dockerfile` had never built: pnpm 11 failed on ignored build scripts without `pnpm-workspace.yaml` | copy it in the dependencies stage |
| D1 | Time played before a seek was folded into SEEKING, which Core credits as zero | PLAYING checkpoint before SEEKING |
| W1 | A profile switch remounted the watch page and admitted under the new profile | player keeps its profile; retry is explicit |
| N1 | Every anonymous page load attempted a refresh (22 in one run; shared 60/min per-IP limit) | auth hint; initial `/me` refreshes only with it |
| R1 | Renewal used the client clock against Core's absolute expiry (loop or lapse when skewed) | expiry from the `Date` header |
| R2 | Native HLS kept playing after a denial | pause and remove the source on teardown |
| R3 | Any `/me` failure was treated as signed out | only 401; otherwise retry with backoff |
| R5/R6 | Requests after `stop()`/sign-out could trigger a refresh | `auth: false` after close and on hide; forced `/end` keeps its backoff |
| R8/R9 | Public `refresh()` bypassed the lock; unencoded hrefs | routed through the lock; encoded |
| R10 | Regression: the M33 runner read the hls.js pin from `devDependencies`, which RA03 moved to `dependencies` | read either section |
| T1 | Harness: login rate limit, abort vs late answer, per-session sequences, keepalive not caught by `page.route` | viewer pool + budget guard; assertions corrected; offline instead of route abort |

## Gate

Local, 2026-11-22, on commit `f7a0f3a` (the RA03 code as pushed):

| Check | Result |
|---|---|
| `infra/ci/verify-local.ps1 -Offline` (CI order: infra/ci unit tests, clean Maven verify, report gate, contracts, Compose template, packaged Core and Media smokes, evidence gate) | PASS. 146 Maven suites / 1229 tests, zero failures/errors/skips (Core 15 + 16 suites, 140 + 156 tests; Media 37 + 27, 244 + 214; Analytics 34 + 17, 349 + 126). infra/ci 122 tests. Contracts PASS. `INGRESS_M29_PASS`, `MEDIA_M29_SMOKE_PASS`. |
| Web unit tests (`pnpm test` in `web/`) | 195 tests, 0 failures. `pnpm lint`, `tsc --noEmit` (app and e2e) and `pnpm build` clean. |
| Web mutation check | 28/28 non-equivalent mutants killed (one equivalent mutant exposed dead code, removed). |
| `infra/smoke/check-web-ra03.ps1` | `WEB_RA03_BROWSER_PASS`: 18/18 in Google Chrome 157 headless with hls.js 1.7.3; 17 admissions, 36 progress reports, 2 refreshes for the whole run (22 before N1). |
| `infra/smoke/check-media-m33.ps1` (regression) | First run FAILED before startup (R10 below); after the fix `MEDIA_M33_BROWSER_PASS`, 1/1. |

The gate adds exactly one test to the previous 146 suites / 1228 tests: the C1 Core integration test. The first two
gate attempts were lost to Docker Desktop stopping mid-run (`Wsl/Service/.../ERROR_NO_SYSTEM_RESOURCES`: the
15 GB workstation ran out of memory with other applications open), and one to a file written during the run (the
gate refuses a tree that changes while it runs). Raw logs: `tmp/ra03-verify-local-5.log`, `tmp/ra03-run6.log`.

## Limits

- Local disposable Compose on one workstation; the remote CI workflow does not run the browser suite (as for M33).
- Two mocked responses (tests 11 and 15), named in the evidence.
- No refresh serialization across tabs without the Web Locks API (insecure context); no grace window for a lost
  refresh response in Core.
- Chrome only; Safari's native HLS path is unit-tested, not browser-verified.
- Docker Desktop on this 15 GB workstation can exhaust host memory during the full Media integration run
  (`ERROR_NO_SYSTEM_RESOURCES`); run the gate with other heavy applications closed.
