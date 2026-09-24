# Media: Core service authentication (Milestone 07)

M07 adds Core's private Media client and Media's isolated service authentication
chain. It does not activate the upload workflow. The production upload handlers,
Core administrator upload controller, candidate verification and durable
provisioning are still scheduled for M09 and later delivery milestones.

## Identity and permissions

Core signs a fresh RS256 JWT for each call with a dedicated RSA key pair of at
least 2048 bits. Only Core receives the private key. Media pins the public key
and key ID locally; it does not discover keys from caller-controlled URLs.
Startup rejects malformed or mismatched key material. Core also rejects reuse
of its playback key or an enabled Analytics/reverse Media-service key.
Core's browser identity uses its existing separate HMAC key.

The verifier requires issuer `libra-core-services`, subject `libra-core`, sole
audience `libra-media-internal`, purpose `service-access`, the configured `kid`,
a nonblank `jti` of at most 128 characters, and `iat`/`exp` with a positive
lifetime of at most 60 seconds. Expiry and future issuance have zero leeway.
Each token carries exactly one of these scopes:

| Scope | Method and path allowed by the internal filter chain |
| --- | --- |
| `core.media.uploads:read` | `GET /internal/v1/uploads/{uploadId}` |
| `core.media.uploads:write` | `PUT /internal/v1/uploads/{uploadId}` |
| `core.media.uploads:write` | `POST /internal/v1/uploads/{uploadId}/upload-url` |
| `core.media.uploads:write` | `POST /internal/v1/uploads/{uploadId}/complete` |

These are authorization rules for the frozen contract, not implemented business
handlers. All other internal operations remain denied. The isolated stateless
chain accepts Authorization Bearer only; cookies, query credentials and role/user
headers provide no authority. CSRF is disabled only in this bearer-only chain.
The existing Core ADMIN session/role/CSRF checks remain in Core. No browser
operation has been added and no browser token or user role is forwarded to Media.

The public Nginx rule continues to return 404 for `/api/media/internal/`.
An internal service token does not make that path publicly accessible. Public
Media routes retain the existing deny-by-default policy and health endpoints
retain their existing access rules.

## Core client

`MediaControlClient` exposes typed `read` and `ensure` calls against a configured
origin and a server-owned UUID path. It selects the scope itself. Future Core
upload orchestration must construct `EnsureUpload` from the committed Core intent
after current ADMIN, CSRF and ownership checks; it must not forward browser JSON.
Call Media after the local transaction commits. The client is not a replacement
for those application-level checks or Media's future candidate-binding lookup.

The client uses a 500 ms connection timeout, a two-second total request/response
budget, at most 16 concurrent calls and at most 65,536 response bytes, including
chunked bodies. Redirects, ambient proxies and automatic application retries are
disabled. HTTPS is required unless Core explicitly enables local development.
No caller-selected headers, paths or destination URLs are accepted.

Successful responses require JSON, identity encoding, the contract's typed fields
and valid state combinations. Unknown fields, scalar coercion, trailing JSON,
missing constructor fields, wrong upload identity and mismatched ensured binding
tuples are rejected. Failures return a bounded result enum, never raw remote
bodies, URLs or exceptions. A timeout/unavailable result after `ensure` is an
uncertain outcome; the future orchestrator must retry the same committed upload
identity. It must not allocate a new candidate on that basis.

## Configuration

Both directions remain disabled by default. Enabling them without valid key
configuration fails startup; disabled mode never accepts or issues a credential.

| Runtime | Variables |
| --- | --- |
| Core outbound | `CORE_MEDIA_CONTROL_ENABLED`, `CORE_MEDIA_CONTROL_BASE_URL`, `CORE_MEDIA_CONTROL_PRIVATE_KEY`, `CORE_MEDIA_CONTROL_PUBLIC_KEY`, `CORE_MEDIA_CONTROL_KEY_ID` |
| Media inbound | `MEDIA_CORE_SERVICE_AUTH_ENABLED`, `MEDIA_CORE_SERVICE_PUBLIC_KEY`, `MEDIA_CORE_SERVICE_KEY_ID` |

Keys are Base64 DER: PKCS#8 private and X.509 public. Inject stable, independently
generated runtime keys outside Git and logs. Main Compose passes only the public
key and key ID from the Core control configuration to Media. It does not pass
Core login secrets or the service private key to Media.

The existing `CORE_MEDIA_SERVICE_*` settings have the opposite direction:
Media-to-Core binding reads. They are not renamed or reused by this milestone.
Signed PUT issuance, playback-ticket verification and key rotation are outside
this implementation.

## Verification boundaries

Local verification on 2026-09-24: root Java 21 `clean verify` passed all three
modules; the fail-closed report checker confirmed 24 suites and 180 tests with
zero failures, errors or skips. The focused M07 tests account for 16 tests.
All 18 contracts, eight CI tooling tests and the 12-case Nginx ingress matrix
passed. The first broad run exposed a pre-existing foundation assertion pinned
to Flyway V7; it was updated to the already implemented V8, and the complete
gate then passed. No migration or production persistence behavior changed.

`MediaControlTokensTest` verifies signatures, claims, lifetime, disabled mode,
sanitized invalid configuration and cross-purpose key separation.
`MediaControlClientTest` exercises the actual Java HTTP client against a local
HTTP fixture, including read/write credentials, errors, slow headers/bodies,
oversized chunked bodies, redirects and invalid responses. Its downstream
responses are synthetic; they are not Media persistence evidence.

`CoreServiceSecurityTest` runs the real Media application configuration, Spring
MVC and production security filters. Test-only operation probes demonstrate
authorized handler dispatch and zero handler calls for denied requests. H2 is
used only for unrelated application wiring, with storage and migrations disabled.
This test does not claim PostgreSQL mutation, candidate verification or a live
Core-to-Media upload workflow.

Run the focused gate with Java 21:

```powershell
.\mvnw.cmd -B -ntp -pl services/core,services/media '-Dtest=MediaControl*Test,CoreServiceSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
pwsh -NoProfile -File infra/smoke/check-media-m07.ps1
```

The ingress script runs real Nginx with explicit HTTP upstream stubs. It proves
that the upstream control probe is reachable but 12 private-route method/path
variants return 404, including encoded and repeated-slash paths. It also checks
that main Compose gives Media only the public service key and that credential
canaries do not appear in ingress logs. It cleans its own randomly named Compose
project and exports only sanitized results to
`target/verification/media-m07-ingress.json`.

Use root `clean verify` plus `infra/ci/check_test_reports.py` for the full current
regression evidence. Contract validation uses `infra/ci/check_contracts.py` with
the packages in `infra/ci/requirements.txt`. Current dated counts and remaining
boundaries are recorded in the private Media roadmap completion entry.

M08 remains the next dedicated hardening gate. Upload handlers, end-to-end admin
revocation/CSRF side-effect tests, storage grants, workers, HLS, Kafka publication,
production TLS and deployment are not delivered by M07.
