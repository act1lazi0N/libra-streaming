# Web: Catalog and Title Detail (Milestone RA03.2)

Verification date: 2026-11-21.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA03.2 adds the catalog home and the title detail page on top of [RA03.1](web-session-ra03-1.md). All data comes from
Core's public catalog (`core-catalog-profiles.v1.json`) and the entitlement check (`core-entitlements.v1.yaml`).

Done condition (plan): hidden content does not appear; a Premium label is never mistaken for a right to play.

Contract and migration changes: none.

## Home (`/`)

- Grid of published movies and series from `GET /catalog?kind=MOVIE|SERIES&q=&limit=24&offset=` (merged newest
  first when "all" is chosen); seasons and episodes are never listed at top level. Search is debounced 300 ms. Empty
  and error states have their own elements (`catalog-empty`, `catalog-error` with retry). Anonymous visitors can
  browse; playing requires login.
- Above the grid, for a signed-in viewer with a profile: the continue-watching row of [RA03.4](web-resume-ra03-4.md).

## Title detail (`/title/{id}`)

- `GET /catalog/{id}`; a 404 (missing, hidden or unpublished ancestry) shows `title-not-found`. Nothing is cached
  across navigations, so hidden content is never served from a stale copy.
- Series: seasons from `GET /catalog/{id}/children?limit=100`, episodes of the first season loaded eagerly, others on
  expand, in ordinal order.
- Two separate labels per playable unit (movie or episode):
  - `tier-badge`: `metadata.accessTier` (Premium / Miễn phí). Informational only.
  - availability: `mediaReady` false → "Chưa sẵn sàng".
- Playability comes only from a live, profile-scoped `GET /profiles/{p}/entitlements/{contentId}` (no-store, never
  cached or reused): 200 enables `play-button`; `PREMIUM_REQUIRED`, `EMAIL_VERIFICATION_REQUIRED`, `MEDIA_NOT_READY`,
  `NOT_PLAYABLE_CONTENT` and 404 show `play-denied` with the reason; anonymous shows a login link. The Premium badge
  never enables the button, and admission rechecks everything anyway.

## Verification

- Unit tests: `catalog.test.ts` 9 (query building, merging movies and series, filtering seasons/episodes, pagination,
  children order, entitlement path, duration format, denial mapping).
- Real stack (RA03 browser suite): test 4 the grid shows the free movie, the Premium movie and the series, not the
  hidden title or an episode, and the hidden title's page shows `title-not-found`; test 5 the free viewer sees the
  Premium badge with `play-denied` (`PREMIUM_REQUIRED`) and no play button, the Premium viewer an enabled play
  button, and the series lists season 1 with episodes 1 and 2 in order.

## Limits

- "Tải thêm" refetches pages 0..n instead of appending (fine for the seed catalog; to revisit with real volumes).
- A season detail issues one entitlement request per episode in parallel (100 for a 100-episode season).
- `imageReferences` are references without an image service: cards show a placeholder.
