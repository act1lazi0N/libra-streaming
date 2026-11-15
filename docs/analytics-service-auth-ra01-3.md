# Analytics: Internal service authentication (Milestone RA01.3)

Verification date: 2026-11-15.
Status: VERIFIED_LOCAL (one Windows workstation with Docker Desktop; the CI workflow was not run remotely).

## Scope

RA01.3 implements the receiving side of Core's dedicated Analytics service identity, described in
[Core Analytics read adapters](core-analytics-adapter.md#dedicated-outbound-service-identity) and fixed by the
`serviceJwt` scheme of `contracts/http/analytics-internal.v1.json`. It follows the receiver Media already uses for
the same Core issuer ([Media M07](media-service-auth-m07.md)).

It routes the two contract operations but serves no projection. Statistics arrive in RA04.2 and recommendations in
RA05.2.

Contract and migration changes: none. Compose and `.env.example` changed (see Configuration).

## Identity and permissions

Analytics pins one RSA public key and its key ID from configuration. It never fetches a key from a URL named by a
token. A token is accepted only when all of the following hold:

- signature RS256 with the pinned key, at least 2048 bits; header `kid` equals the configured key ID;
- `iss` = `libra-core-services`, `sub` = `libra-core`, `aud` = exactly `[libra-recommendation-analytics]`,
  `purpose` = `service-access`;
- `jti` is a nonblank string of at most 128 characters;
- `scope` is one string, either `recommendations:read` or `statistics:read`;
- `iat <= now < exp`, `exp > iat`, `exp - iat <= 60 s`, with zero leeway; a future `nbf` is refused.

| Scope | Operation |
| --- | --- |
| `recommendations:read` | `POST /internal/v1/recommendations/query` |
| `statistics:read` | `POST /internal/v1/statistics/query` |

The chain covers `/internal/**`, is stateless, and reads the credential from one `Authorization: Bearer` header only.
Cookies, query or form parameters, and role or user headers carry no authority. Two `Authorization` headers, or a
token longer than 8,192 characters, are refused. CSRF protection is disabled in this bearer-only chain alone.

Any other path or method under `/internal/**` requires a valid service token first, and then answers 404
`NOT_FOUND` or 405 `METHOD_NOT_ALLOWED`. An unauthenticated caller therefore cannot map the internal API. Outside
`/internal/**` the existing chain still denies everything except health and info; a service token opens nothing
there.

Tokens are not one-time: no replay cache exists, as the contract states.

## Responses

Every refusal and failure is `application/problem+json` with the closed `Problem` schema, `Cache-Control: no-store`,
and, for 401, `WWW-Authenticate: Bearer`. Texts are fixed per code. The only request value ever returned is
`X-Correlation-ID`, and only when it is a canonical lowercase UUID; otherwise a new UUID is generated.

| Situation | Status and code |
| --- | --- |
| Missing, malformed or invalid credential | 401 `AUTHENTICATION_REQUIRED` |
| Valid credential, scope of the other operation | 403 `ACCESS_DENIED` |
| Authorized, and an input of the projection has no confirmed checkpoint | 503 `ANALYTICS_NOT_READY` |
| Authorized, checkpoints confirmed, projection not served by this build | 503 `ANALYTICS_UNAVAILABLE` |
| Database unreachable | 503 `ANALYTICS_UNAVAILABLE` |
| Unexpected failure | 500 `INTERNAL_ERROR` |

An authorized call never receives 200 with zeros. Core maps every non-200 answer to `UNAVAILABLE` or
`FALLBACK UNAVAILABLE` without reading the body, so enabling the integration against this build gives truthful
unavailable results. The request body is not read yet; validation (400, 413, 415) arrives with the projections.

`InternalApiErrors` handles failures inside the application. Without it the container would dispatch to `/error`,
which the default chain denies, and the caller would receive an empty 403.

## Configuration

Authentication is disabled by default. In disabled mode no token is accepted and no key is needed.

| Runtime | Variables |
| --- | --- |
| Core outbound (existing) | `CORE_ANALYTICS_ENABLED`, `CORE_ANALYTICS_BASE_URL`, `CORE_ANALYTICS_PRIVATE_KEY`, `CORE_ANALYTICS_PUBLIC_KEY`, `CORE_ANALYTICS_KEY_ID` |
| Analytics inbound (new) | `ANALYTICS_CORE_SERVICE_AUTH_ENABLED`, `ANALYTICS_CORE_SERVICE_PUBLIC_KEY`, `ANALYTICS_CORE_SERVICE_KEY_ID` |

The public key is Base64 X.509 DER; the key ID is 1–64 letters, digits, underscores or hyphens. Enabling
authentication without a usable key stops startup. Compose maps the three inbound variables from
`CORE_ANALYTICS_ENABLED`, `CORE_ANALYTICS_PUBLIC_KEY` and `CORE_ANALYTICS_KEY_ID`, so one switch enables both
sides and the private key reaches Core only.

Nginx proxies `/api/recommendations/` to the service. It now answers 404 for `/api/recommendations/internal/`, as
it already does for Core and Media, so the service API is not reachable from the public ingress at all. Core calls
the service on the internal network.

## Verification

| Suite | Lane | Tests | What it proves |
| --- | --- | --- | --- |
| `CoreServiceSecurityIntegrationTest` | integration, PostgreSQL 18.6, full security chain | 8 | Every registered `/internal/` handler allows exactly one scope (added in RA01.4); each scope reaches only its operation; a token built with the same encoder calls as Core's `AnalyticsServiceTokens.issue` is accepted and the 503 body equals the `problem-not-ready` fixture; no zeros while no projection is served, and answering moves no checkpoint; browser access tokens (HS256, algorithm confusion with the public key, browser claims signed by the service key) are refused; playback tickets and Media-audience service tokens are refused; unknown paths and methods answer only to an authenticated service; a service token opens nothing outside `/internal/**` |
| `CoreServiceDecoderTest` | unit | 4 | Maximum lifetime and expiry at exact boundaries with a fixed clock; disabled mode; refused key material |

Every test in the integration suite also asserts, after each request, that `input_checkpoints` is unchanged, except
the one test that confirms checkpoints on purpose.

Evidence limits: "Core can call the API" is shown with a token produced by the same Spring Security encoder calls
and claim set as Core's signer, inside the Analytics test JVM. Core's own `AnalyticsClient` was not run against a
started Analytics process; see Limits.

## Gate

RA01.3 shares the gate recorded in [RA01.2](analytics-foundation-ra01-2.md#gate): 102 Maven suites and 790
tests, zero failures, errors or skips; contracts PASS.

## Limits

- No end-to-end run of Core's `AnalyticsClient` against a running Analytics service exists yet. The two modules are
  tested against the same contract and fixtures, not against each other. A packaged smoke belongs to RA09.4; an
  earlier cross-service check is possible in RA04.2, when a 200 answer first exists.
- The new Nginx rule was checked with `nginx -t` in the `nginx:1.29.3-alpine` image only. No request was sent
  through a running ingress, and alternate spellings of the path (the class of defect Media M30 found) were not
  tested. The service JWT remains the control that is proven.
- One pinned key, no rotation, no JWKS discovery and no replay cache.
- TLS between Core and Analytics is a deployment concern; the Compose network uses HTTP, which Core permits only
  with `CORE_LOCAL_DEVELOPMENT=true`.
- Request-body limits and rate limiting are not implemented; the codes exist in the contract.
- Local evidence only; the CI workflow was not run remotely.
- Next: [RA01.4](analytics-foundation-hardening-ra01-4.md) hardens this chain and the startup path.
