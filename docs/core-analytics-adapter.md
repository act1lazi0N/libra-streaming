# Milestone 7: Core Analytics read adapters

This slice implements Core's recommendation/statistics read boundary and its
outbound scoped service identity. It does **not** implement the Analytics service,
its projections or algorithms. The public [Core contract](../contracts/http/core-analytics.v1.json)
and proposed [downstream contract](../contracts/http/analytics-internal.v1.json)
are versioned separately. Downstream behavior is tested with a local HTTP fixture.

Milestone 7 also implements [integration operations](core-integration-operations.md):
durable outbox delivery, inbound Media service authentication, DLT inspection/redrive,
transactional operation audit and metrics. Those capabilities use migration V7;
the Analytics read adapters themselves need no additional tables.

## Profile recommendations

`GET /v1/profiles/{profileId}/recommendations?limit=20` requires an authenticated
live account and an owned profile. Limit is 1–100. Unverified accounts may browse;
recommendations do not grant playback entitlement or Premium access.

Core sends only account ID, profile ID and candidate limit 100 to
`POST /internal/v1/recommendations/query` on the configured service origin. Analytics
must return the same profile ID, `updatedAt` and up to 100 candidate content IDs.
Unknown fields, invalid IDs, missing fields, future timestamps and oversized
lists are rejected. A snapshot older than 15 minutes triggers fallback.

Core rechecks current catalog state in its own database after the call. Only
published movies with READY active media, and published series with at least one
published season/episode with READY active media, survive. Episode IDs are not
silently converted to series IDs. Missing, hidden and unavailable candidates are
removed, duplicate IDs are collapsed, and surviving rank order is preserved.
Premium titles may appear with their existing public `accessTier` metadata.
Metadata always comes from Core's published revision, never from Analytics.

The response contains `source` (`ANALYTICS` or `FALLBACK`), `reason`, `updatedAt`
and `items` using the existing public catalog DTO. A valid nonempty filtered result
uses `ANALYTICS`, retains the snapshot timestamp and has a null reason. A partial
result is not padded with unrelated titles. If no candidates survive, or Analytics
is disabled, unavailable, malformed or stale, Core returns the newest currently
recommendable titles, ordered by publication timestamp descending then UUID.
Fallback can legitimately be empty; it has a null Analytics timestamp and an
explicit reason (`DISABLED`, `UNAVAILABLE`, `INVALID_RESPONSE`, `STALE`, or
`NO_VISIBLE_CANDIDATES`). No popularity/personalization is fabricated locally.

## Administrator statistics

`GET /v1/admin/statistics?from=2026-09-01&to=2026-09-07` requires a live ADMIN role.
Dates describe an inclusive UTC calendar range, at most 31 days, with `to` no later
than today's UTC date. Core sends exactly that range to
`POST /internal/v1/statistics/query`; a different returned range is invalid.

The downstream contract returns aggregate `qualifiedViews`, `uniqueViewers`,
`acceptedWatchedMs`, and `updatedAt`. Counts must be nonnegative and unique viewers
cannot exceed qualified views. These metrics are derived from Core-accepted events:
one view per session reaching 30 seconds; unique viewers are distinct qualified
accounts over the entire requested window, not summed daily unique counts. Views
and unique qualification are attributed to the UTC date when a session first
qualifies; accepted watched duration is attributed to each accepted event's UTC
date. Late events must correct the affected historical bucket. These are requirements
for the future Analytics implementation, not proof that projections already exist.

Core returns `status`:

- `AVAILABLE`: valid snapshot at most 15 minutes old; real zeros stay zero.
- `STALE`: valid older snapshot with its counts and actual `updatedAt`, reason `STALE`.
- `UNAVAILABLE`: disabled/failed/invalid downstream response, with explicit reason
  and null timestamp and all null counts. No previous snapshot is cached by Core.

All responses use existing no-store security headers. Public DTOs expose no service
JWT, account/profile identifiers from telemetry, email, private URLs or downstream
error bodies. Statistics are aggregate only; recommendation items contain public
catalog IDs and metadata.

## Authorization and concurrency

Account/session and profile ownership or ADMIN role are checked in short local
transactions before and after HTTP. Network I/O holds no account row lock. Profile
deletion, logout, suspension or administrator demotion during the call therefore
prevents returning a protected result. Publication is a current query snapshot;
recommendations are never a reservation or a playback authorization grant.

The adapter has a 500 ms downstream budget covering token/request preparation,
connection, headers and full body receipt, with a final deadline check after
bounded JSON parsing. This is not a 500 ms bound on the complete Core endpoint:
local database work, thread scheduling and CPU processing also take time.
No application retry or redirect following is enabled. A shared HTTP client reuses
connections; at most 16 calls are admitted concurrently per Core instance, with
excess calls falling back immediately. Future multi-instance rate limits/metrics
are separate operational work. Responses are capped at 65,536 bytes while streaming,
including chunked bodies. Only status 200 and uncompressed `application/json` are
accepted; downstream authentication failures also degrade safely.

## Dedicated outbound service identity

The adapter is disabled by default. Enabling it requires:

- `CORE_ANALYTICS_ENABLED=true`
- `CORE_ANALYTICS_BASE_URL`: trusted service origin, no path, query, fragment or
  credentials. HTTPS is mandatory outside `CORE_LOCAL_DEVELOPMENT=true`.
- `CORE_ANALYTICS_PRIVATE_KEY`: Base64 PKCS#8 RSA private DER.
- `CORE_ANALYTICS_PUBLIC_KEY`: matching Base64 X.509 RSA public DER.
- `CORE_ANALYTICS_KEY_ID`: 1–64 letters/digits/underscore/hyphen.

Use at least 2048-bit RSA and a **different pair from playback**. Startup fails on
missing, invalid, mismatched or reused enabled keys. Keys are never generated by
the running application. Disabled mode requires no new keys and makes no outbound
calls. Provision only the public key/key ID to Analytics through a trusted operator
configuration. The private key stays with Core. No new public JWKS endpoint or
automatic rotation/discovery is introduced in this slice.

Every request carries a freshly signed RS256 Bearer JWT with issuer
`libra-core-services`, subject `libra-core`, sole audience
`libra-recommendation-analytics`, purpose `service-access`, unique `jti`, `iat` and
60-second `exp`. Exactly one `scope` is issued: `recommendations:read` or
`statistics:read`. Claims contain no user identity, cookie or playback credential.
The correlation UUID is forwarded in `X-Correlation-ID`; browser cookies and
authorization headers are never forwarded.

The future Analytics receiver must pin the trusted key and algorithm, validate
issuer/subject/audience/purpose, reject absent/future/expired timestamps or lifetime
over 60 seconds, and enforce the exact scope for the endpoint. Do not trust JWT
headers to select a remote key URL or treat forwarded browser identity as authority.
These tokens authorize read-only queries, not telemetry ingestion or business
mutations. They are not one-time tokens; no distributed replay cache is claimed.
Inbound Core Media authentication uses a separate key, audience and scope, described
in [integration operations](core-integration-operations.md).

Compose passes the optional variables to Core but leaves the integration disabled.
The current Recommendation/Analytics scaffold does not serve these endpoints;
enabling against it will produce truthful unavailable/fallback results. No attempt
is made to disguise that boundary as a working live integration.

## Verification

`AnalyticsServiceTokensTest` covers signing, claims, key separation and fail-closed
configuration. `AnalyticsReadIntegrationTest` uses real PostgreSQL and Core HTTP
security with a local downstream HTTP fixture: candidate visibility/ranking,
fallback, invalid/profile-mismatched payloads, slow headers/body, redirects,
minimal public data, ownership, concurrent deletion/revocation/demotion, scopes,
statistics freshness/zeros, validation, and disabled mode.

Run `mvnw.cmd -pl services/core -Dit.test=AnalyticsReadIntegrationTest verify`,
then root `mvnw.cmd clean verify` with JDK 21 and Docker. Adapter tests do not prove
real Analytics consumption, production TLS or deployment. Kafka publication and
recovery are covered separately by the integration operations suite. The completed
milestone's local verification on 2026-09-20 passed 143 tests across all services.

Implementation references: [JDK 21 HttpClient cancellation and body handling](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
and [Spring Security NimbusJwtEncoder](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/jwt/NimbusJwtEncoder.html).
