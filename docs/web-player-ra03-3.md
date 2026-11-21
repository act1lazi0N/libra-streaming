# Web: Protected HLS Player (Milestone RA03.3)

Verification date: 2026-11-21.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA03.3 adds `/watch/{contentId}`: admission, hls.js playback of Media's protected HLS, ticket renewal, and progress
reporting that follows `core-playback.v1.yaml`. It builds on [RA03.1](web-session-ra03-1.md) and
[RA03.2](web-catalog-ra03-2.md); resume and next episode are [RA03.4](web-resume-ra03-4.md).

Done condition (plan): protected HLS plays; progress reaches Core and then Analytics; seeking does not create fake
watch time.

Contract and migration changes: none. `hls.js` 1.7.3 moved from devDependencies to dependencies (same version).

## Design (`web/src/lib/playback/`)

Pure modules with injected client, clock and timers, so they are unit-tested without a browser:

- **`ProgressReporter`.** Core credits watch time only as `min(playedMs, server elapsed)` for an interval that follows
  an accepted PLAYING report, never on a SEEKING report, and never across a gap over 30 s
  (`PlaybackService.progress`). The reporter therefore measures `playedMs` as wall time actually spent in PLAYING
  since the last report (capped at 30 000), never paused, buffering or seeking time. Every state change is reported;
  while playing, a heartbeat every 15 s. Sequences start at 1 per session and are never reused for a different body.
  Reports are serialized; a network or 5xx failure retries the identical body (same sequence — Core treats it as a
  safe duplicate) after 1 s and 3 s, then drops it, and its time is never re-attributed. ENDED goes to `/end`.
  Terminal answers (409 `PLAYBACK_EXPIRED`/`PLAYBACK_ENDED`/`MEDIA_BINDING_CHANGED`/`PLAYBACK_EXPIRING`, any 403,
  404, 401) stop it. On `pagehide`/hidden tab it sends one PAUSED report with `keepalive` and only the cached CSRF
  token, without refresh.
- **`RenewalScheduler`.** Renews 60 s before expiry (half the remaining time when 120 s or less remain, never sooner
  than 1 s). The expiry is converted to client time from the response's `Date` header
  (`clientNowAtReceipt + expiresAt − Date`), so a wrong client clock can neither cause a renew loop nor let the ticket
  lapse. Network/5xx/429 retry with backoff while before expiry; an expiry reached while Core is unreachable asks for a
  new admission. Other answers are terminal.
- **`PlaybackController`.** One admission per user action (`{profileId, contentId}`), never retried after a denial.
  hls.js with `startPosition` = the session's resume position (native HLS where MSE is absent). Video events map to
  states; seeks and stalls before the first frame are the player positioning itself and are not reported. A
  `MEDIA_BINDING_CHANGED`, expired, ended or expiring session is re-admitted automatically for the same profile — at
  most twice per page and never twice within 30 s — and otherwise shows `player-error` with a manual retry. A 403/404
  or sign-out shows `player-denied` and stops. A fatal 401/403 from Media (ticket lapsed, e.g. after sleep) renews once
  and restarts loading. `stop()` (navigation, profile switch, sign-out) flushes and then sends nothing more for the
  session; native HLS teardown pauses the element and removes its source.

## Findings fixed during the milestone

- **D1 (design).** The first design folded the time played before a seek into the SEEKING report, which Core credits
  as zero (`PlaybackService.java:78`): up to 15 s lost per seek. Now a PLAYING checkpoint at the estimated pre-seek
  position precedes SEEKING. Unit-tested; mutant M1 killed.
- **W1.** The watch page used the profile in its React key, so a profile switch remounted the player and admitted the
  title under the new profile without the viewer asking. The player now keeps the profile it was started for and
  stops on a switch; only the viewer's retry starts it again (browser test 18).
- **R1 (review).** Renewal timing used the client clock against Core's absolute expiry; a client clock 5 min fast
  renewed every second. Fixed with the `Date` header conversion above (tests at +7 h and −10 min).
- **R2 (review).** Native HLS teardown left the element playing with a still-valid ticket after a denial. Fixed.
- **First-frame noise.** The real browser fired `waiting` (autoplay) and a positioning `seeking` before the first
  frame, so the first report was BUFFERING or SEEKING. Harmless for credit, but now ignored before the first `playing`.

## Verification

- Unit tests: `progress.test.ts` 41, `renewal.test.ts` 27, `controller.test.ts` 63, `messages.test.ts` 2 (exact
  request bodies: sequence, state, playedMs, positionMs, endpoint).
- Mutation check (scratchpad driver; KILLED only when the named test file fails): 28 non-equivalent mutants in the
  reporter, renewal, controller, client and scoped fetch, all killed — including seek checkpoint removed, accrual of
  non-playing time, uncapped playedMs, a retry with a new sequence, a denial retried, an unlimited re-admission
  budget, any 409 treated as re-admission, the clock conversion ignored. One mutant (a `remaining/4` renewal floor)
  survived because the floor could never bind; the dead floor was removed.
- Real stack (RA03 browser suite, real Chrome + hls.js 1.7.3 through the repository Nginx): test 6 the free movie
  plays from real HLS after one admission, every stream response 200, the first report PLAYING, sequences in order;
  test 7 a seek produces an accepted SEEKING report, Core's `watchedMs` never exceeds the measured playing time
  + 1.5 s, and the view qualifies at 30 s; test 8 the administrator statistics through Core become AVAILABLE or
  STALE with accepted watch time and a qualified view, so progress reached Analytics through Kafka; tests 6/7 assert
  zero CSRF rejections (C1).

## Limits

- A seek while playing costs four reports (checkpoint, SEEKING, PLAYING on `seeked`, PLAYING on `playing`); credit is
  correct.
- Watch time is not credited while the tab is hidden (a PAUSED report is sent on hide; PLAYING again when visible).
- Replaying after the end requires reopening the page (the session is ENDED on the server).
- Watched time is wall-clock playing time without a playback-rate factor, as Core defines it.
- The 403 `PREMIUM_REQUIRED` mid-playback case is verified with one mocked response (RA03.5); Core cannot expire a
  simulated Premium on a live stack.
