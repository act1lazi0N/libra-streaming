# Media: Authentication and transport hardening (Milestone 08)

M08 hardens the M07 service boundary. Production upload orchestration and private
upload handlers remain scheduled for M09 and later. No public operation, schema,
scope, migration, storage capability or worker is enabled by this milestone.

## Corrections verified by regression tests

- Media rejects multiple Authorization header values before bearer resolution.
  Previously, a valid first header let an ambiguous request dispatch a handler.
  Denials use the existing sanitized 401 Problem response and a plain Bearer
  challenge, without reflecting the rejected credentials.
- Core rejects duplicate JSON member names in Media responses. Previously, the
  last value could replace an earlier contradictory value and produce a successful
  result. Strict duplicate detection is local to the private client's mapper;
  it does not change Core's shared API mapper.

Both cases were reproduced as failing tests before the production corrections.

## Evidence matrix

| Boundary | Positive control | Denial/failure assertions |
| --- | --- | --- |
| Core administrator | Valid signed identity cookie, current PostgreSQL ADMIN session and real CSRF exchange create one upload intent and candidate binding | Anonymous, USER, forged role/user headers, missing/invalid CSRF, revoked/expired session, suspended account and demoted ADMIN never dispatch the probe; intent/binding counts and catalog version/candidate remain unchanged |
| Media internal authentication | Dedicated RS256 read/write credentials pass the production filters; a write probe persists an upload and a completion probe creates one job in PostgreSQL | Wrong key/algorithm/kid/issuer/subject/audience/purpose/scope, malformed claims, tampering, unsigned/HMAC credentials, future issuance/not-before, expiry, excessive lifetime, oversized tokens and ambiguous headers fail closed |
| Media persistence after denial | Authorized PUT creates a real OPEN/UPLOADING fixture | Rejected complete leaves that asset UPLOADING with no job/outbox; other denied requests leave upload/asset/job/outbox tables empty |
| Core transport | Actual Java HTTP client reads typed synthetic JSON over loopback | Chunked/fixed responses above 64 KiB, duplicate fields, malformed JSON, coercion, trailing JSON, mismatched identity, wrong content type and encoded responses cannot become success |
| Capacity and failure | All 16 admitted calls complete, then another call succeeds | Call 17 fails immediately without dispatch; interruption cancels the request and preserves the interrupt flag; connection refusal and slow headers/bodies are bounded; a truncated response after PUT yields UNAVAILABLE with no application replay |
| Public ingress | Real Nginx reaches an explicit upstream control stub | Existing M07 smoke rechecks 12 method/path variants, key distribution and credential-log canaries |

Deterministic clock tests additionally check exact expiry, issuance one second in
the future, zero lifetime and the 60/61-second lifetime boundary. Production JWT
validation still uses the configured public key, RS256 and zero time leeway.

## Test boundaries

`UploadAdminSecurityIntegrationTest` and `CoreServiceSecurityIntegrationTest` use
the actual Spring application configuration, MVC and security filters, plus real
PostgreSQL migrations and persistence services. Their upload controllers are
**test-only adapters**, not shipped endpoints. Positive database writes make the
absence-of-side-effects checks meaningful. These tests prove the existing
authorization boundary; they do not prove M09 orchestration, cross-service
candidate verification or future completion/storage checks.

The Media security suite replaces M07's H2-backed `CoreServiceSecurityTest` and
runs in Failsafe. The Core client tests use real loopback HTTP with synthetic
downstream responses. Nginx uses upstream stubs. These are separate evidence
lanes, not a live browser-to-Core-to-Media upload workflow.

An UNAVAILABLE result after a write remains an uncertain outcome. Future
orchestration must reuse the committed upload identity when recovering; transport
failure never grants authority or establishes READY. The client has no
application retry loop. Its connect timeout is 500 ms and total exchange budget
is two seconds; local refusal/slow-response tests do not simulate every network
blackhole or production TLS failure.

## Reproduction

Use JDK 21 and a running Docker engine:

```powershell
.\mvnw.cmd -B -ntp -pl services/core,services/media '-Dtest=MediaControl*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=UploadAdminSecurityIntegrationTest,CoreServiceSecurityIntegrationTest' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
pwsh -NoProfile -File infra/smoke/check-media-m07.ps1
.\mvnw.cmd -B -ntp clean verify
```

Run `infra/ci/check_test_reports.py`, `infra/ci/check_contracts.py` and CI Python
unit tests using a virtual environment with `infra/ci/requirements.txt`.
The report checker requires every current suite, including both PostgreSQL
security suites, and rejects missing or skipped reports.

## GitNexus review provenance

The review is scoped to the M08 authentication/transport boundary on
`features/media`, based on `2ed4048456c7844783c0e26edf0e96a8c7f48684` and its local
changes. GitNexus 1.6.12 is indexed with `--pdg --index-only`; source inspection
and runtime tests supplement query/context/impact, diff, taint and dependence
results. New untracked tests are also reviewed directly.

Static graph coverage has limits: the analyzer reports truncated process walks
and cross-language field-resolution gaps; Spring callbacks and dynamic dispatch
cannot be inferred safe from an empty caller/taint result. No whole-codebase
security certification or production deployment claim is made.
