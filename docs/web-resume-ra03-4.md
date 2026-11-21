# Web: Resume, Continue Watching and Next Episode (Milestone RA03.4)

Verification date: 2026-11-21.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA03.4 connects the player of [RA03.3](web-player-ra03-3.md) to Core's history: resume position, the
continue-watching row and the next episode, plus recovery when the session expires or the media binding changes.

Done condition (plan): the right position and episode open; losing the last heartbeat keeps the progress Core already
accepted.

Contract and migration changes: none.

## Behaviour

- **Resume** is server-owned: admission returns `resumePositionMs` (Core already returns 0 for completed content and
  clamps it to a new asset's duration). hls.js starts there; the page shows `resume-from`.
- **Continue watching** on `/`: profile-scoped `GET /profiles/{p}/continue-watching?limit=20`, a progress bar from
  `positionMs/durationMs`, hidden when empty.
- **Next episode**: after ENDED (whether `/end` succeeded, was terminal or was dropped after its retries),
  `GET /profiles/{p}/next-episode/{contentId}`. A non-null id shows `next-episode` with a 10 s countdown, play and
  cancel; null shows `playback-finished` with a link back to the title.
- **Expired or replaced sessions**: `PLAYBACK_EXPIRED`, `PLAYBACK_ENDED`, `PLAYBACK_EXPIRING` and
  `MEDIA_BINDING_CHANGED` from progress or renewal re-admit the same title for the same profile (bounded, see RA03.3)
  and resume from Core's position.

## Verification

- Unit tests: `history.test.ts` 3; next-episode and re-admission paths in `controller.test.ts`.
- Real stack (RA03 browser suite):
  - Test 9: after a PAUSED report is accepted, the browser context goes offline, playback continues locally for 4 s
    and the tab closes, so no later report reaches Core — the `keepalive` unload flush included (a `page.route` abort
    does not catch requests sent during unload; the first version of this test was fooled by exactly that). Reopening
    resumes within 2.5 s of the accepted position, shows `resume-from`, and Home lists the title under continue
    watching.
  - Test 10: seeking to 1.2 s before the end of episode 1 shows `next-episode`; its play button opens episode 2, which
    plays; exactly two admissions; sequences checked per session.
  - Test 11: the page plays the free movie, one progress response is **mocked** as `409 MEDIA_BINDING_CHANGED`; the
    page makes exactly one new admission (a new session in the manifest path, answered 200), keeps playing and shows
    no `player-error`.

## Limits

- A real binding change cannot be produced on a live stack: Core refuses an upload for content that already has a
  published revision, an active binding or a candidate (`UploadIntentService.java:64`, `UPLOAD_NOT_ALLOWED`), and an
  asset cannot be bound twice (`ASSET_ALREADY_BOUND`). Test 11 therefore mocks the one 409 answer; everything after it
  is real. Replacing published media is a Core capability for a later milestone.
- No history page (`/history`) and no "clear history" action in the UI.
