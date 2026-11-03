# Media: Browser vertical slice (Milestone 33)

Verification date: 2026-10-22.
Status: VERIFIED_LOCAL (one headless Google Chrome 155.0.8059.86 driven by Playwright 1.64.0 against the disposable
Compose stack of the real `infra/compose.yaml` and `infra/nginx/nginx.conf`, packaged Core and Media JARs, PostgreSQL,
Kafka, Mailpit and SeaweedFS; Docker Desktop on one Windows workstation).

## Scope

M33 proves the first slice from a real browser. A test-only harness page, served through the repository ingress, does
the following:

1. The administrator logs in, creates a catalog entry and chooses the clip in a file input.
2. The page puts the clip directly into private storage with the signed URL Core obtained from Media.
3. The page polls until Media's worker makes it READY and Core projects the event from Kafka, then publishes it
   through Core.
4. A viewer registers, is refused admission until it verifies the emailed token, buys simulated Premium and is
   admitted.
5. The pinned hls.js plays the session's HLS through Nginx with the browser's own HttpOnly ticket cookie. The player
   seeks, renews the session through Core and plays to the end.

The test only reads results. It routes, mocks or fulfills no request, injects no READY event, and writes nothing to a
database. No product UI is built, and no service, contract or migration changes.

Deliverables:

| Path | Role |
| --- | --- |
| `web/e2e/m33/harness/` (`index.html`, `harness.js`) | The test-only page. Every Core call is a same-origin `fetch` with Core's CSRF double submit; the source PUT is a cross-origin CORS request. Credentials, tickets, signed URLs and identity bodies stay in the page. |
| `web/e2e/m33-vertical-slice.spec.ts`, `web/playwright.config.ts`, `web/e2e/tsconfig.json` | The Playwright test. No trace, video, screenshot or HAR. Type-checked on its own (`tsc -p e2e`) and kept out of `next build` and the web image. |
| `web/package.json` | `@playwright/test` 1.64.0 and `hls.js` 1.7.3 as exact dev dependencies. |
| `infra/smoke/compose.browser-m33.yaml`, `infra/smoke/browser-m33-web.conf` | Overlay: the ingress on a loopback port fixed before startup, and an `nginx:1.29.3-alpine` stand-in for the `web` upstream that serves only `/m33/` from a read-only mount. |
| `infra/smoke/check-media-m29.ps1 -Milestone 33`, `infra/smoke/check-media-m33.ps1`, `infra/smoke/media_m33_ready.py` | The runner: M29's disposable project, generated keys and cleanup, without M29's phase 1. |

The page's origin is fixed before startup because two settings must name it exactly. Core's public base URL is used
for the verification link. Media's `MEDIA_BROWSER_ORIGIN` is the only origin in the source bucket's CORS rule. Chrome
treats `http://127.0.0.1` as a secure context, so the `__Host-` identity cookies and the `__Secure-` ticket cookie are
set and sent with `CORE_COOKIE_SECURE` left at its Compose default (`true`).

Playwright's bundled Chromium has no H.264/AAC decoder. The test uses the installed Google Chrome
(`channel: "chrome"`), and the evidence records its exact version.

## Gate

One clip is uploaded directly from a browser and played from the real generated HLS. No success response is
intercepted, no READY event is injected, and no readiness is written to a database.

## What the browser run asserts

| Step | Assertion |
| --- | --- |
| Upload | The file input's 12 s 1280x720 H.264/AAC clip (4.8 MB) goes through Core's intent and grant. The grant targets `http://127.0.0.1:<s3 port>/libra-source/staging/`. The browser's preflight and signed PUT (`Content-Type`, `x-amz-checksum-sha256`) succeed, and completion answers 202 `QUEUED`. |
| Storage CORS | A PUT preflight from the page's origin is admitted. From `http://localhost:<same port>`, from another port, and a `DELETE` from the page's origin, it is not. These preflights come from the test: a page cannot choose its Origin. |
| Readiness | Polled from the browser through Core until the upload is READY with no failure code. Core's candidate then becomes READY from Media's Kafka event. |
| Publication | An explicit `POST .../publication` through Core. The public catalog then reports `mediaReady`. |
| Viewer | Registration is answered 202. A login before verification succeeds, but admission is refused `403 EMAIL_VERIFICATION_REQUIRED` and no ticket is set. After the emailed token (read from Mailpit), login and simulated Premium, admission answers 201. Exactly one `__Secure-LIBRA_PLAYBACK` cookie is set: HttpOnly, Secure, SameSite=Lax, path `/api/media/v1/streams/<session>/`. |
| Playback | `Hls.isSupported()`. One 720p level (`avc1`/`mp4a`) and a 1280x720 picture. Duration is within 0.5 s of 12 s. More than 60 frames are presented (`requestVideoFrameCallback`), and a canvas sample of the frame on screen has more than 32 distinct colours (298 in the final run). |
| Seek and renewal | The harness pauses the presentation and stops hls.js loading. The rendition's last segment is unfetched and no segment request is in flight. Core renews the session, which replaces the ticket cookie (compared by SHA-256 fingerprint). A seek to 8 s made while paused lands at its target (the `seeked` event's `currentTime`). The last segment's request starts after renewal, and playback continues with new frames. |
| Tickets per request | The fingerprint of the ticket in each stream request's `Cookie` header is recorded, never its value. Every request before renewal carried the first ticket, and every request after renewal carried the renewed one. |
| End | Playback reaches `ended` with no fatal player error. Every player stream response was 200, and the master playlist was fetched once. |
| Refusal | The page's own `fetch` of the master, rendition and first segment without cookies is answered `401 AUTHENTICATION_REQUIRED` as a problem, and no ticket was sent. |

## Observations

- **Storage CORS.** This run is the first browser check of the rule Media writes at bucket initialization. No earlier
  smoke made a browser preflight. SeaweedFS 4.46, behind the storage gateway, answers the preflight for the exact
  origin. It allows the two signed headers and refuses the origins and the method above.
- **First run.** In the first run, the test seeked backwards after renewal, with `backBufferLength: 0`, near the end of
  the clip. hls.js stayed `seeking` for 60 s. It made no fragment load and reported no error (`tmp/m33-smoke-run1.log`).
  That was the harness relying on player internals, not a refusal by Media. The final test renews while the player is
  held and seeks forward into the unfetched last segment.
- **Review.** The code-reviewer agent found a race in the second design. A segment request in flight at renewal could
  complete afterwards with the old ticket and still count as a load after renewal. The final design removes it: the
  harness holds the player and stops loading before renewal, and the test checks each request's ticket fingerprint.

## Verification

The final browser run, `infra/smoke/check-media-m33.ps1` (`tmp/m33-smoke.log`), used the JARs from the clean verify
below. Its Playwright test passed in 30.6 s, and it wrote `target/verification/media-m33.json`. That file holds
versions, counts, signed header names and the PASS lines, and no URL, cookie or credential. Neither file contains a
JWT, an S3 signature or a ticket cookie.

| Measure | Result |
| --- | --- |
| Browser and player | Google Chrome 155.0.8059.86 headless, Playwright 1.64.0, hls.js 1.7.3 |
| Clip | 12 s, 4,778,335 bytes, 1280x720, three 4 s segments |
| Frames | 353 decoded, 229 presented, 0 dropped; 294 distinct colours in the sampled frame |
| Renewal | Held at 3.69 s; seek landed at 8.00 s; segment 2 requested after renewal |
| Stream responses | 5 player responses, all 200. 4 before renewal carried the first ticket and 1 after it the renewed one. 3 cookieless requests were refused 401. |

Before the final design there were three development runs (`tmp/m33-smoke-run1.log` to `run3.log`). Run 1 hit the
backward seek above. Run 2 failed on a harness bug: an `evaluate` closure referenced a Node constant. Run 3 passed
with the racy renewal check the review then replaced.

A ci-gate agent ran every `.github/workflows/core-ci.yml` stage on the uncommitted tree (`tmp/ci-gate/*.log`, Maven
copied to `tmp/m33-verify.log`). Results:

- Java 21 offline root `clean verify` passed all three backends (BUILD SUCCESS, 15m22s).
- The report gate confirmed 91 suites and 648 tests with zero failures, errors or skips:
  - Core 188 (45 Surefire, 143 Failsafe);
  - Media 458 (244 Surefire, 214 Failsafe);
  - recommendation and analytics 2.

  These are M32's counts: M33 changes no Java.
- The 16 Python gate tests passed, and `check_contracts.py` passed 22 checks.
- `docker compose config --quiet` passed.
- The packaged Core smoke passed 10 checks.

The web checks were `tsc --noEmit` for the app, `tsc --noEmit -p e2e` for the test, `pnpm lint` and `pnpm build`.
All passed.

The runner's M29 path still passes after the M33 branch was added. `infra/smoke/check-media-m29.ps1` was run once,
with no milestone argument, after the clean verify (`tmp/m33-m29-regression.log`). It printed 17 PASS lines and
`MEDIA_M29_SMOKE_PASS`, covering both phases. M30's path was not run again. `git diff --check` is clean. No disposable
project left a container, volume or `libra-*` image behind.

Work was divided among agents. A doc-finder agent mapped Core's browser-facing cookies, CSRF and links, a
code-reviewer agent reviewed the full draft, and a ci-gate agent ran the gate. The reviewer's findings were all
applied:

- the renewal race and the unproven seek (above);
- a stale diagnostic in the playback wait;
- the full smoke environment, private keys included, reaching Node, Playwright and Chrome. Playwright now runs with
  only the `LIBRA_M33_*` variables, and its `DEBUG`/`PWDEBUG` switches are cleared;
- harness cleanup that could mask the original error;
- the e2e sources entering `next build` and the web image;
- two assertions that could not fail;
- a missing ffmpeg check.

## Limits

- **One browser.** Only headless Google Chrome 155.0.8059.86 on Windows, with Chrome's own MSE and H.264/AAC decoders,
  was tested. Firefox, Safari (native HLS), Edge, mobile browsers and a headed window were not, and no compatibility
  with them is claimed.
- **Harness, not product.** The page is a test-only harness served by a static stand-in for the `web` upstream. The
  Next.js app has no upload, playback or verify-email page; the emailed link points at `/verify-email`, which nothing
  serves, so the test reads the token from Mailpit and posts it.
- **Loopback HTTP.** Secure cookies work only because Chrome treats `http://127.0.0.1` as a secure context. HTTPS,
  another host name, and `localhost` versus `127.0.0.1` were not tested (they are different sites for cookies).
- **One short clip.** 12 s, three 4 s segments and one rendition. Long presentations, ticket expiry during playback in
  the browser (M30 covers it without a browser), slow networks and 503 retries by hls.js were not exercised.
- **CORS preflights from the test.** The negative CORS checks are preflights the test sends with chosen origins; a
  browser on another origin was not run.
- **Unchanged from M32.** Browser PUT duration is bounded only by the write grace; there is no retention policy for
  failed assets and no metrics scrape endpoint; Core and Media must be deployed together.
- **Next.** Per the plan, M34 repeats the whole flow under crash points and dependency outages.
