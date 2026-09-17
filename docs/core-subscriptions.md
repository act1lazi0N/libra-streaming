# Core simulated Premium (Milestone 4)

This slice implements account-wide simulated Premium status, activation, purchase
history, durable idempotency, and concurrent term extension. No money is collected
and no automatic renewal runs. Every status and receipt includes `simulated: true`.

The [centralized entitlement matrix](core-entitlements.md) combines current identity,
owned profile, effective publication, exact READY asset, and Premium expiry.
Subscription status alone does not authorize playback. Playback sessions and Media
tickets remain Milestone 5.

## HTTP contract

See [versioned OpenAPI](../contracts/http/core-subscriptions.v1.yaml).
Core paths use `/v1`; Nginx exposes `/api/core/v1`.

| Method/path | Behavior |
| --- | --- |
| `GET /v1/subscriptions` | Current account status: NONE, ACTIVE, or EXPIRED |
| `POST /v1/subscriptions/simulate` | Activate or extend simulated Premium |
| `GET /v1/subscriptions/purchases` | Owned receipts, default limit 20, maximum 100 |

All endpoints require a current active account/session and use the existing access
cookie. Activation also requires CSRF and currently verified email, including for
administrators. Unverified users may read their status/history. Account identity
comes exclusively from authenticated context. Responses are not cacheable.

Activation accepts `{"plan":"PREMIUM_30_DAYS"}` with a client-generated UUID in
`Idempotency-Key`. Unknown JSON fields are rejected, including account IDs, expiry,
duration, and payment claims. Both first success and replay return HTTP 200.

Reuse the same key and payload after a timeout or lost response. A new key represents
an intentional additional purchase. Same account/key with a changed plan returns
409 `IDEMPOTENCY_CONFLICT`; unsupported plans on new keys return 400. Keys remain
with receipts without time-based cleanup. Different accounts can use the same key.
An invalid or failed request does not reserve a key.

The returned receipt always describes its original purchase; replay after another
purchase still returns that original receipt. Read status for the latest expiry.
History orders by `purchasedAt DESC, id DESC`, with `offset` in 0..10000. Ordering
is deterministic; offset pages may shift during new purchases. Responses omit
account/email/session identifiers and idempotency keys.

## Persistence and time

Flyway V4 adds `subscriptions` and `subscription_purchases` in the Core database.
Existing accounts receive no implicit Premium. A unique `(account_id,
idempotency_key)` constraint backs replay identity; receipts are retained alongside
their keys. There is no new dependency, environment setting, or cross-service event.

Each operation locks the current account before reading subscription state,
consistent with identity revocation and profile operations. Activation rechecks live
session/account status and email verification under that lock. Receipt insertion
and expiry update commit in one transaction, so a receipt failure rolls both back.
Concurrent distinct keys accumulate terms; concurrent same-key requests share one
receipt. Lock semantics follow [PostgreSQL row locking](https://www.postgresql.org/docs/18/explicit-locking.html#LOCKING-ROWS).

Each intentional purchase adds 30 elapsed days (720 hours) from
`max(serverNow, currentExpiry)`. UTC instants use PostgreSQL microsecond precision,
so the initial receipt equals its persisted replay. Premium is active only when
`expiresAt > serverNow`; equality is expired. All account profiles share this state.
No background expiry task is needed.

## Verification

`SubscriptionIntegrationTest` uses real PostgreSQL 18 via Testcontainers, production
Flyway, real HTTP/Spring Security cookies and CSRF, and a fixed test clock. It covers
V3-to-V4 migration, stable retries, intentional extension, exact expiry boundary,
expired repurchase, concurrent same/distinct keys, rollback after a database failure,
live email verification, admin denial, revoked/expired/suspended identity, invalid
input, account isolation, pagination, and private receipt responses.

Run the focused suite:

```powershell
.\mvnw.cmd -pl services/core test '-Dtest=SubscriptionIntegrationTest'
```

Run the complete backend reactor with `.\mvnw.cmd clean verify`. These are local
backend checks, not frontend, deployed environment, payment, or HLS evidence.

Verified locally on 2026-09-17: the focused subscription suite passed 10/10.
Root `clean verify` finished at 23:11:13 +07:00 with 79 tests, zero failures,
errors, or skips (77 Core, one Media wiring, one Analytics wiring). Core includes
70 PostgreSQL/Kafka/Mailpit integration tests. OpenAPI 3.1 validation passed for
the subscription, entitlement, and shared foundation contracts using
`openapi-spec-validator` 0.9.0. `git diff --check` also passed.

### Observed results (2026-09-15, Docker 29.7.2)

Focused suite — `SubscriptionIntegrationTest` with PostgreSQL 18 Testcontainers:
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.79 s

Full reactor — all three services:
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0

Breakdown:
- Core unit tests (CoreApplicationTest + IdentityPolicyTest + EventEnvelopeTest): 7
- Core integration (FoundationIntegrationTest): 12
- Core integration (CatalogProfilesIntegrationTest): 13
- Core integration (IdentitySecurityIntegrationTest): 24
- Core integration (SubscriptionIntegrationTest): 10
- Media: 1
- Recommendation-Analytics: 1

Flyway V4 applied cleanly on both fresh schema and V3-upgrade schema.
