# Core identity (Milestone 2)

Core implements registration, login, rotating refresh sessions, session revocation,
email verification, password recovery/change, USER/ADMIN authorization, and one-time
administrator bootstrap. The versioned HTTP contract is
[core-identity.v1.json](../contracts/http/core-identity.v1.json); shared errors remain
in [core-foundation.v1.yaml](../contracts/http/core-foundation.v1.yaml).

## Local startup

Core requires two different, random 32-byte keys encoded as Base64:
`CORE_JWT_KEY` and `CORE_MAIL_ENCRYPTION_KEY`. Missing or invalid keys intentionally
fail startup. Preserve both keys across restarts; replacing the mail key makes
previously queued encrypted messages unreadable. Never commit or print these keys.

From the repository root, create `.env` only if it does not exist. The following
PowerShell fills only blank key placeholders and preserves existing values:

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
$coreEnvLines = Get-Content -LiteralPath .env
$coreKeyGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $coreEnvLines = @($coreEnvLines | ForEach-Object {
        if ($_ -match '^(CORE_JWT_KEY|CORE_MAIL_ENCRYPTION_KEY)=$') {
            $coreKeyName = $Matches[1]
            $coreKeyBytes = New-Object byte[] 32
            $coreKeyGenerator.GetBytes($coreKeyBytes)
            $coreKeyName + '=' + [Convert]::ToBase64String($coreKeyBytes)
        } else { $_ }
    })
} finally { $coreKeyGenerator.Dispose() }
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) '.env'), $coreEnvLines)
```

An older `.env` without these placeholders needs the two blank entries added first.
Use `.env.example` for the explicit loopback-only flags:
`CORE_LOCAL_DEVELOPMENT=true`, `CORE_COOKIE_SECURE=false`,
`CORE_PUBLIC_BASE_URL=http://localhost:8080`, and `CORE_MAIL_STARTTLS=false`.
Use HTTPS and Secure cookies outside this local environment.

Compose consumes this file explicitly:

```powershell
docker compose --env-file .env -f infra/compose.yaml config --quiet
docker compose --env-file .env -f infra/compose.yaml --profile full up -d --build
```

For a host Maven process, Spring does not automatically read the repository `.env`.
Load the unquoted `CORE_*` values from the example format into the current terminal,
then start the infrastructure and Core:

```powershell
Get-Content -LiteralPath .env | ForEach-Object {
    if ($_ -match '^(CORE_[A-Z0-9_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
docker compose --env-file .env -f infra/compose.yaml up -d postgres kafka mailpit
.\mvnw.cmd -pl services/core spring-boot:run
```

This loader reads data only; it does not execute `.env` contents. Quoted/multiline
dotenv values are not supported by this short host example. Core uses port 8081;
ingress uses `http://localhost:8080/api/core`. Mailpit is at `http://localhost:8025`.
Frontend verification/reset pages are not implemented by this milestone; callback
URLs specify future client routes, not a completed browser recovery flow.

## Browser and HTTP workflow

1. `GET /v1/auth/csrf` with a cookie jar returns `headerName` and a masked `token`,
   and sets an HttpOnly CSRF cookie. Do not read the cookie through JavaScript.
2. Send that cookie and the returned token in `X-CSRF-TOKEN` on every unsafe method,
   including registration, login, refresh, logout, and public password recovery.
3. Login and refresh return only `accessExpiresAt` and `sessionExpiresAt`. The
   access/refresh credentials are HttpOnly cookies, never JSON bearer tokens.
4. Whenever authentication cookies are issued or cleared, fetch a new CSRF token
   before the next unsafe request. Serialize refresh calls across the client:
   concurrent reuse of a consumed token revokes that session family.
5. After password change/reset or global logout, reauthenticate. All account
   sessions are revoked; cached account/session views must be discarded.

Production names are `__Host-LIBRA_ACCESS`, `__Host-LIBRA_REFRESH`, and
`__Host-LIBRA_CSRF`: Secure, HttpOnly, SameSite=Lax, Path=/, no Domain. Explicit
local HTTP mode omits `__Host-` and Secure. Access JWTs last ten minutes; a refresh
session has an absolute thirty-day expiry that rotation does not extend.
Every authenticated request checks current account/session database state.
Revocation and suspension therefore invalidate existing access JWTs immediately.
JWT role claims do not grant authority.

## Endpoint behavior

Paths below are internal; ingress prepends `/api/core`. Exact fields, constraints,
responses, and security requirements are in the versioned OpenAPI contract.

| Method and path | Request fields | Success |
| --- | --- | --- |
| GET `/v1/auth/csrf` | none | 200 masked token and header name |
| POST `/v1/auth/register` | email, displayName, password | 202 generic ACCEPTED |
| POST `/v1/auth/login` | email, password | 200 expiry timestamps and cookies |
| POST `/v1/auth/refresh` | refresh cookie | 200 expiry timestamps and cookies |
| POST `/v1/auth/logout` | optional refresh cookie | 204; clears cookies |
| POST `/v1/auth/logout-all` | authenticated session | 204; revokes all sessions |
| POST `/v1/auth/forgot-password` | email | 202 generic ACCEPTED |
| POST `/v1/auth/resend-verification` | email | 202 generic ACCEPTED |
| POST `/v1/auth/verify-email` | token | 204 |
| POST `/v1/auth/reset-password` | token, newPassword | 204; revokes all sessions |
| POST `/v1/auth/change-password` | currentPassword, newPassword | 204; revokes all sessions |
| GET `/v1/me` | authenticated session | 200 account DTO |
| GET `/v1/me/sessions` | limit=20, offset=0 | 200 owned active sessions |
| DELETE `/v1/me/sessions/{id}` | owned session UUID | 204; foreign/missing ID is 404 |
| POST `/v1/admin/users/{id}/suspension` | ADMIN session, target UUID | 204; self-suspension is 403 |

Emails are stripped/lowercased for lookup. Public registration cannot set a role;
unknown JSON fields are rejected. New passwords require 12..128 Unicode code points
and at most 512 UTF-8 bytes; request DTOs additionally cap strings at 256 UTF-16 code
units. Argon2id stores password hashes. Session lists have deterministic ordering,
limit 1..100 and offset 0..10000; token hashes and credentials are never projected.

Verification links expire after 24 hours; reset links after 30 minutes. Tokens are
random, hashed in the token table, single-use, and purpose-bound. Resending replaces
earlier links of that purpose. Unverified active users can log in and recover a
password. Reset neither verifies email nor reactivates suspended accounts.
Playback/Premium verification gates remain later entitlement work.

Stable identity errors include `INVALID_CREDENTIALS`, `INVALID_SESSION`,
`INVALID_OR_EXPIRED_TOKEN`, and `PASSWORD_POLICY_VIOLATION`, alongside foundation
errors. `RATE_LIMITED` includes remaining-window seconds in `Retry-After`. Rate
limits persist in PostgreSQL even when the protected transaction fails; bucket keys
are HMAC fingerprints. Cleanup rechecks age after waiting for concurrent updates.
Forwarded IP headers are not trusted; an ingress needs its own client IP limits.

## Migration, bootstrap, and mail operations

Flyway V2 creates accounts, sessions, refresh/email token hashes, the encrypted mail
queue, rate limits, audit records, and the bootstrap marker in the Core database.
Registration/token creation and mail enqueue share one database transaction.

Set both `CORE_BOOTSTRAP_ADMIN_EMAIL` and `CORE_BOOTSTRAP_ADMIN_PASSWORD`, or neither.
First successful bootstrap creates an unverified ADMIN and queues verification.
An advisory transaction lock and persistent marker enforce one-time creation.
An existing user's email causes startup failure instead of automatic promotion.
After success, remove bootstrap credentials from configuration. Subsequent starts
with a marker do not reset the administrator's password. Never delete that marker
to promote or recreate an administrator.

AES-256-GCM encrypts recipient, subject, and body with the queue row UUID as associated
data. Workers claim at most ten messages with five-minute leases, retry with bounded
backoff up to five attempts, recover abandoned leases, and reject stale acknowledgments.
Expired/replaced messages lose their payload; corrupt payloads are quarantined.
`SENT` means SMTP acceptance, not inbox delivery. SMTP failure may have an unknown
delivery outcome, so retries can duplicate the same one-use link.

SMTP defaults require STARTTLS and server-identity checking. Configure
`CORE_MAIL_HOST`, `CORE_MAIL_PORT`, `CORE_MAIL_USERNAME`, `CORE_MAIL_PASSWORD`,
`CORE_MAIL_AUTH=true`, and `CORE_MAIL_FROM` for a real STARTTLS server. Local Compose
explicitly disables STARTTLS for Mailpit. `CORE_MAIL_WORKER_ENABLED=false` pauses the
worker while durable queue records remain. Do not log payloads, cookies, keys, or tokens.

Production key rotation, dead-mail administrative redrive, and general session/token
retention are not implemented. Queue polling purges expired payloads and at most 500
old rate-limit buckets per claim; it is not a general retention policy.

## Verification

On 2026-09-14, root `mvnw.cmd -B clean verify` passed on JDK 21.0.12 with all three
services packaged: 45 tests, zero failures/errors/skips. Core includes 24 identity
integration tests and 12 foundation integration tests with real PostgreSQL, Kafka,
Mailpit, and the HTTP security filter chain; nine other tests cover units/context
wiring across the three services. The cleanup race failed its PostgreSQL regression
before the fix and passed afterward, including the 500-row batch bound.

The earlier denied Core verification command never started. This fresh result
supersedes that missing evidence. The suspected rollback-fixture SQL error did not
reproduce: its PostgreSQL test passes. This is local backend verification and JAR
packaging, not CI, deployment, real inbox delivery, or a frontend identity flow.

Compose configuration passed with `.env.example` and the full profile. Contract
JSON/YAML parsing, all references, operation IDs/security scheme references, and
the 15 controller route mappings passed structural checks; full OpenAPI semantic
validation was not run. The documented PowerShell blocks parse, and isolated setup
verification confirmed distinct 32-byte keys with existing values preserved on rerun.
