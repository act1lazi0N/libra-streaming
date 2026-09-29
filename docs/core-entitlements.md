# Core: Entitlement matrix (Milestone 04)

`EntitlementService.requireEligible(actor, profileId, contentId)` centralizes Core's
current eligibility policy. It returns an internal selection of the exact published
revision and active asset, or throws a stable domain/identity error. Caller-provided
roles and email-verification flags are not authoritative.

The public read-only endpoint is
`GET /v1/profiles/{profileId}/entitlements/{contentId}` (external prefix
`/api/core/v1`). See [versioned OpenAPI](../contracts/http/core-entitlements.v1.yaml).
Success returns only `eligible: true`, `accessTier`, and `checkedAt`. Responses use
`Cache-Control: no-store`; errors use Problem Details with a correlation ID.

## Decision order

| Check | Failure |
| --- | --- |
| Current active account and matching, unrevoked session with expiry strictly after server time | 401 AUTHENTICATION_REQUIRED at HTTP authentication, or INVALID_CREDENTIALS in the service |
| Verified email, including Free viewers and administrators | 403 EMAIL_VERIFICATION_REQUIRED |
| Existing profile owned by the authenticated account | 404 NOT_FOUND |
| Published content with all ancestors published | 404 NOT_FOUND |
| Movie or episode (series/seasons are containers) | 409 NOT_PLAYABLE_CONTENT |
| Exact active binding belongs to that content and is READY with duration | 409 MEDIA_NOT_READY |
| Published playable unit is PREMIUM and account Premium is absent/expired | 403 PREMIUM_REQUIRED |
| Every applicable condition passes | 200 eligible |

The tier comes from the published movie/episode revision. Draft changes and
container tiers do not implicitly change it. Profile ownership remains mandatory
for administrators. Profiles share account Premium; there is no stream quota.
Expiration equality means expired. Expired Premium still permits otherwise
eligible Free content.

An unpublished ancestor hides every descendant without rewriting individual child
publication state. Missing and foreign profiles and hidden content all return
NOT_FOUND. No asset identifiers, storage paths, account email, session identifiers,
or reusable credentials appear in the public response.

## Publication and media consistency

One parameterized SELECT reads identity, ownership, publication hierarchy, published
tier, active binding, and subscription from a single PostgreSQL statement snapshot.
This avoids mixing a previous tier with a replacement asset across separate reads.
It uses the [PostgreSQL Read Committed snapshot behavior](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED).

The selected asset is `active_binding`, never `candidate_binding`. A PROCESSING,
FAILED, or READY replacement does not change eligibility until explicitly published.
If the active asset becomes unavailable, the service refuses eligibility even when
another candidate is READY. Existing versioned Media projection handling rejects
stale events; a stale READY event cannot restore failed current media or select an
old asset version.

## Playback integration (Milestone 5)

This check is a point-in-time observation, with no reservation, session creation,
ticket issuance, or playlist/segment access. A concurrent change may invalidate it
after the query snapshot. The result must not be cached or accepted from a client
as evidence for admission.

[Playback admission, renewal and progress](core-playback.md) call this policy afresh
while holding account and catalog/ancestor locks, coordinating with revocation and
profile/history lifecycle. Sessions bind the exact returned asset version and
published revision. Authentication-session and Premium expiry cap signed tickets.
The policy remains distinct from ticket signing and Media enforcement on every
playlist and segment; actual Media delivery is separate work.

## Verification

`EntitlementIntegrationTest` uses PostgreSQL 18/Testcontainers, production Flyway,
the real catalog/publication and Media projection services, HTTP/Spring Security,
and a fixed clock for exact boundary assertions. Media events are Core contract
fixtures, not real transcoding/storage evidence.

Coverage includes Free/Premium, shared profiles, email/admin gates, revoked/expired/
suspended/mismatched sessions, foreign/deleted profiles, hidden ancestors, drafts,
containers, active-asset failure, replacement/stale events, input/privacy, and a
concurrent revision/asset publication check.

```powershell
.\mvnw.cmd -pl services/core test '-Dtest=SubscriptionIntegrationTest,EntitlementIntegrationTest'
.\mvnw.cmd clean verify
```

Verified locally on 2026-09-17: the focused entitlement suite passed 11/11.
The fresh root `clean verify` passed 79/79 tests with no skips and packaged all
three services. OpenAPI 3.1 validation passed for the new contracts and shared
errors using `openapi-spec-validator` 0.9.0. This verifies Core behavior and
local integration boundaries; it does not prove deployed playback or HLS delivery.
