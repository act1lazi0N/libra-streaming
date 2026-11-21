# Web: Core Client, Session and Profiles (Milestone RA03.1)

Verification date: 2026-11-21.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA03.1 replaces the landing page of `web/` with a real client of Core: an API client with CSRF handling and session
refresh, login, registration, logout, and per-tab profile selection. The browser talks only to same-origin
`/api/core/v1/...` through the repository Nginx; it never calls Analytics or Media control APIs. RA03.2–RA03.5 build
the catalog, player, resume and hardening on top ([RA03.2](web-catalog-ra03-2.md),
[RA03.3](web-player-ra03-3.md), [RA03.4](web-resume-ra03-4.md), [RA03.5](web-hardening-ra03-5.md)).

Done condition (plan): identity comes from the backend; switching profile never shows the previous profile's response.

Contract and migration changes: none. One Core fix (C1, below) changes no contract: it restores the behaviour
`docs/core-identity.md` already documents.

## Core client (`web/src/lib/core/`)

- Every request is `credentials: "same-origin"`, `cache: "no-store"`, `redirect: "error"`. Credentials are HttpOnly
  cookies; the client never reads, stores or logs a cookie, token, ticket or an authentication response body. Errors
  are `CoreProblem` (status, code, correlation id, `Retry-After`) or `CoreNetworkError`, with no body in the message.
- **CSRF.** `GET /auth/csrf` is cached in memory and single-flight. Every unsafe method carries the header. The cache
  is dropped whenever Core issues or clears authentication cookies (login, refresh, logout, logout-all, success or
  failure). A `403 ACCESS_DENIED` on an unsafe request is treated as a stale token: one fresh token, one retry.
- **Refresh.** A `401 AUTHENTICATION_REQUIRED`/`INVALID_CREDENTIALS` on a non-`/auth/` path runs one refresh, then
  retries the request exactly once. Refresh is serialized across tabs with the Web Locks API (`libra-auth-refresh`)
  and single-flight inside a tab. Inside the lock, a `localStorage` timestamp newer than the request's start means
  another tab already rotated, so no second refresh is sent. Core revokes the whole session family when a consumed
  refresh token is replayed; serializing is what prevents that. A refresh `401` signs out (no loop).
- **Anonymous loads.** A non-identity hint `localStorage["libra.auth.present"]` is set after a successful login,
  refresh or `/me` and removed on every sign-out. The initial `/me` refreshes only when the hint exists. Without it, a
  logged-out visitor's page load made a pointless refresh (the first browser run counted 22); all users behind the
  ingress share Core's 60-per-minute refresh limit per IP.
- **Tabs.** A `BroadcastChannel("libra-auth")` carries `signed-in` and `signed-out`. A remote `signed-out` first
  aborts profile-scoped work and stops the player, then re-reads `/me` without refresh: 401 ends the session, 200
  keeps it (review finding F1: a tab whose refresh failed while another tab was logging in used to sign that tab out).

## Session and profiles (`web/src/lib/session/`)

- `SessionProvider` derives identity only from `GET /me`. Only a 401 means anonymous; a network error or 5xx keeps
  the page loading and retries with backoff (2 s doubling to 30 s) instead of sending the viewer to `/login`.
- `ProfileProvider` lists `GET /profiles` and keeps the selection in `sessionStorage` per tab and account, accepted
  only when the id is in the list; otherwise the viewer is routed to `/profiles`. A `generation` counter increments on
  every switch and sign-out.
- `useProfileScoped(fetcher, deps)` captures the generation at start, aborts on switch or unmount, drops any late
  result from an earlier generation, and reports `loading` immediately after a switch, so the previous profile's data
  can never be rendered for the new one.
- Pages: `/login`, `/register` (202, then verify by email; the verification page itself is out of scope),
  `/profiles`, and a header with the account from `/me`, the current profile, switch and logout. Logout clears local
  state on success or 401; on a network error it stays signed in and shows an error. An unverified account sees a
  banner.
- Security headers in production builds: CSP (`default-src 'self'`, `connect-src 'self'`, `media-src 'self' blob:`,
  `worker-src 'self' blob:`, `frame-ancestors 'none'`, …), `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: same-origin`. `pnpm dev` with `LIBRA_DEV_INGRESS_URL` rewrites `/api/core` and `/api/media` to a
  local ingress.

## Finding C1 (Core): the CSRF cookie was deleted on every authenticated request

The first real browser run showed every progress report sent twice: each POST was answered `403 ACCESS_DENIED`,
then retried with a fresh token. Cause: Core's `SessionCreationPolicy.STATELESS` makes Spring Security install a
`SessionManagementFilter`, and with a stateless repository it treats every authenticated request as a new
authentication and runs `CsrfAuthenticationStrategy`, which deleted the CSRF cookie. A browser that caches one token
(as `docs/core-identity.md` tells it to) therefore always held a stale one. The M33 harness and the Python smokes
fetch a token before every unsafe call, which is why it was never seen.

- Reproduced first: `IdentitySecurityIntegrationTest#csrfTokenStaysValidAcrossAuthenticatedRequestsUntilAuthenticationCookiesChange`
  failed on the old code (`expected: <cookie> but was: null` after `GET /me`; `tmp/ra03-c1-before.log`).
- Fix: `SecurityConfiguration` sets `NullAuthenticatedSessionStrategy` as the CSRF configurer's strategy. Rotation at
  the authentication boundary is unchanged: `IdentityCookies.issue/clear` still deletes the CSRF cookie on login,
  refresh, logout, logout-all, password change/reset and session revocation, and the existing
  `loginRefreshAndLogoutRequireCsrfAndOldHeaderFailsAfterRotation` still proves an old token fails after rotation.
- Browser guard: the RA03 suite asserts zero `403 ACCESS_DENIED` answers to Core POSTs in tests 6, 7, 10 and 12.

## Verification

- Unit tests (Node's built-in runner, type stripping): `client.test.ts` 41 (CSRF single-flight and invalidation,
  stale-token retry once, five concurrent 401s → one refresh, refresh by another tab → none, refresh 401 → one
  sign-out and no loop, `/auth/*` never refreshes, the auth hint, remote sign-out routed to its own handler),
  `scoped.test.ts` 6, `identity.test.ts` 3.
- Real stack (RA03 browser suite, see [RA03.5](web-hardening-ra03-5.md)): test 1 login through the UI and `/me` in
  the header; test 2 two tabs with the access cookie removed reload at once → exactly one `POST /auth/refresh`, both
  render and `/me` stays 200; test 3 profile A's continue-watching request delayed 3 s, the viewer switches to B, the
  request is aborted and A's item never appears for 6 s.

## Limits

- Without the Web Locks API (an insecure context such as plain HTTP to a LAN address) refresh is serialized only
  inside one tab; two tabs refreshing at once can then trigger Core's family revocation. Serve the web app over HTTPS
  or `localhost`.
- Core rotates refresh tokens without a grace window: a refresh response lost in transit (reload mid-request) leaves
  the browser with a consumed token and the next refresh revokes the family. Out of scope for RA03.
- No profile management UI (create, rename, delete); no email-verification or password-reset pages.
- Identity's React wiring (provider retry loop, broadcast handling) is covered by the browser suite, not by unit tests.
