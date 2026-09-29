# Core: CI, smoke and operational acceptance (Milestone 08)

This milestone packages the implemented Core V1 verification into repeatable
checks. It does not expand Media, Analytics or frontend implementation. No new
business migration is required; the latest Core schema is V7.

The [dated local evidence](core-verification.md) records executed checks, suite
counts and verification boundaries for this milestone.

## Run the same gates locally

Prerequisites: JDK 21 in `JAVA_HOME`, Docker Engine with Compose, Python 3.12,
PowerShell 7 (`pwsh`), and access to Maven, Python package and container registries.
The first run downloads dependencies/images. Integration checks fail when Docker
is absent; do not use `-DskipTests`, `-DskipITs`, or disabled Testcontainers checks
as acceptance evidence.

From the repository root on Windows:

```powershell
python -m venv tmp/core-ci-tools
./tmp/core-ci-tools/Scripts/python.exe -m pip install -r infra/ci/requirements.txt
./tmp/core-ci-tools/Scripts/python.exe -m unittest discover -s infra/ci -p 'test_*.py'
./mvnw.cmd -B -ntp clean verify
./tmp/core-ci-tools/Scripts/python.exe infra/ci/check_test_reports.py
./tmp/core-ci-tools/Scripts/python.exe infra/ci/check_contracts.py
docker compose --env-file .env.example -f infra/compose.yaml config --quiet
pwsh -File infra/smoke/check-core.ps1 -Python ./tmp/core-ci-tools/Scripts/python.exe
```

On Linux, use `python3 -m venv tmp/core-ci-tools`,
`tmp/core-ci-tools/bin/python`, and `bash ./mvnw -B -ntp clean verify`; the
PowerShell smoke entry point is the same. Check the exit code after each command.
Run contract/report checks **after** Maven clean, which removes root `target`.

The existing `infra/smoke/check-foundation.ps1` remains a separate Windows check
for an already-running workstation Compose stack, including Kafka's host and
container listeners. The new acceptance smoke needs no workstation `.env` or
running stack and uses dynamically assigned loopback ports.

## CI behavior

[Core CI](../.github/workflows/core-ci.yml) runs on pushes, pull requests and manual
dispatch. Its `Core verification` job performs the full three-module backend
reactor build, all Core integration suites, contract validation and packaged Core
smoke. Media/Analytics context tests prove scaffold wiring only. Frontend lint,
build and browser tests remain separate work.

The job uses a GitHub-hosted Ubuntu runner, Java 21, read-only repository
permissions, SHA-pinned official actions and checkout without persisted Git
credentials. It uses `pull_request`, not privileged `pull_request_target`, and
requires no repository secrets. It neither deploys nor publishes application
images. Branch protection must separately require `Core verification` in GitHub;
adding the workflow does not change remote repository settings.

The report gate derives expected suites from the current `*Test.java` sources and
requires their Surefire/Failsafe XML reports, nonempty execution, and zero
failures/errors/skips. Its negative test verifies missing, empty, skipped and
failing reports are rejected. `clean verify` prevents old reports being counted.
The contract gate validates every OpenAPI document and event JSON Schema and
checks every `$ref` target/JSON pointer within the repository before resolution.
It does not silently fetch a remote contract reference.

Only summaries under `target/verification/*.json` are uploaded, retained for seven
days. `tests.json` lists suite counts; `contracts.json` lists validated files;
`smoke.json` lists completed assertions. Missing summaries are not a pass. Raw
Maven output, XML properties, container environment, mail bodies, cookies and keys
are deliberately excluded. Maven failure diagnostics identify the first
missing/failed suite; reproduce locally to inspect detailed private reports.

Action references were resolved from the official repositories. Maintenance
guidance: [secure GitHub Actions use](https://docs.github.com/en/actions/reference/security/secure-use),
[setup-java](https://github.com/actions/setup-java), and
[artifact action](https://github.com/actions/upload-artifact).

## Packaged smoke coverage and limits

`check-core.ps1` creates a randomly named `libra-smoke-*` Compose project with fresh
PostgreSQL, Kafka, Mailpit, Core and Nginx. It generates temporary independent
identity/mail keys, an RSA playback pair and an administrator password in process
environment, restoring any prior values on exit. The image uses the exact JAR
from the preceding Maven build and the production JRE/non-root runtime layout.
Its dedicated Docker ignore file permits only that JAR into the build context.

The smoke mounts the repository's real Nginx configuration, tests its syntax,
then drives HTTP through `/api/core`. Unused Media/Analytics/web upstream names
resolve locally only so Nginx can load; those upstreams are not exercised.

Assertions cover:

- Fresh Flyway migration through the current schema and three database owners.
- Public health/catalog; anonymous API, CSRF, metrics and internal-route denial.
- Registration/login, HttpOnly cookies, no-store and the unverified Premium gate.
- Scheduled durable mail delivery to local Mailpit and one-use verification.
- Simulated Premium activation/replay without duplicate purchase; profile creation.
- USER/ADMIN roles, cross-account ownership, truthful recommendation/statistics fallback.
- Non-READY publication denial, synthetic Media READY consumed over Kafka, explicit publication.
- Watchlist replay, playback ticket cookie/path, duplicate progress, persisted history,
  and unpublication blocking renewal.
- Scheduled outbox broker ACK with a matching event actually read from Kafka.
- Refresh and logout revocation through ingress.

Cleanup removes only that invocation's project containers/network/volumes in
`finally`, including after assertion failure. It never runs `down` against the
workstation stack. An external forced process termination can prevent cleanup;
inspect `docker compose ls`, then remove only the printed `libra-smoke-*` project.
Never run broad Docker prune as a recovery step. Temporary smoke images/build
cache may remain available for subsequent runs.

These checks prove real Core runtime/ingress/PG/Kafka/local SMTP behavior. The Media
event is a synthetic contract fixture. They do not prove FFmpeg, storage privacy,
playlist/segment authorization, real Analytics projections, browser rendering,
production TLS, external inbox delivery or remote CI execution. A broker ACK is
not downstream processing success. Detailed concurrency, replay and moderation
cases remain in the integration suites, rather than being duplicated in smoke.

## API and contract map

External browser prefix is `/api/core/v1`; paths in contracts use Core's `/v1`.
Fetch `/auth/csrf`, keep the cookie jar and send the returned header/token on unsafe
methods. Refresh CSRF after login/refresh/logout. UUID ownership and live session
checks remain authoritative in Core. Never put credentials in URLs.

| Area | Versioned contract | Guide |
| --- | --- | --- |
| Errors, health, correlation | [Foundation](../contracts/http/core-foundation.v1.yaml) | [Foundation](core-foundation.md) |
| Accounts, cookies, sessions, verification/recovery | [Identity](../contracts/http/core-identity.v1.json) | [Identity](core-identity.md) |
| Profiles, catalog, publication, bindings | [Catalog/profiles](../contracts/http/core-catalog-profiles.v1.json) | [Catalog](core-catalog-profiles.md) |
| Simulated purchases | [Subscriptions](../contracts/http/core-subscriptions.v1.yaml) | [Premium](core-subscriptions.md) |
| Central admission rules | [Entitlement](../contracts/http/core-entitlements.v1.yaml) | [Entitlement](core-entitlements.md) |
| Tickets, progress, history | [Playback](../contracts/http/core-playback.v1.yaml) | [Playback](core-playback.md) |
| Watchlist, reviews, moderation | [Community](../contracts/http/core-community.v1.json) | [Community](core-community.md) |
| Recommendation/statistics reads | [Core Analytics](../contracts/http/core-analytics.v1.json), [downstream fixture](../contracts/http/analytics-internal.v1.json) | [Adapters](core-analytics-adapter.md) |
| Queues, audit, retry/redrive | [Operations](../contracts/http/core-operations.v1.json) | [Integration operations](core-integration-operations.md) |
| Private Media binding read | [Internal Media](../contracts/http/core-internal-media.v1.json) | [Service identity](core-integration-operations.md#scoped-media-http-identity) |

Event schemas live in [contracts/events](../contracts/events). Contract validation
proves schema validity/reference resolution, not that every HTTP response has been
validated against every schema. API behavior is additionally exercised by the
real filter-chain integration suites and smoke.

## Operator runbook

1. Provision the service-owned database/role, supported Kafka topics/ACLs, SMTP,
   separate persistent keys and private listeners. Follow the
   [configuration reference](core-configuration.md); keep optional integrations
   disabled until their peer implementations exist.
2. Before an upgrade, take an access-controlled PostgreSQL backup and prove restore
   into an isolated environment. Retain matching mail encryption material and
   identity/session data. Record deployed artifact and schema versions. Backups
   contain personal data and token hashes and must not be CI artifacts.
3. Run the verification gates on the candidate tree. Start the candidate against
   the restored database in isolation, verify Flyway validation, HTTP health,
   authentication and queue behavior. Empty-database tests do not replace a
   deployment-specific backup/restore rehearsal.
4. For an incident, check health, current ADMIN queue summary and metrics. Rising
   oldest age or parked rows requires diagnosis even if HTTP health is UP. Check
   broker/DB connectivity and permissions without dumping event payloads or secrets.
5. For a broker outage, optionally pause `CORE_OUTBOX_PUBLISHER_ENABLED`; committed
   rows remain durable. Repair connectivity, resume, then watch pending rows drain.
   An expired lease is reclaimed; duplicate event delivery remains possible.
6. Inspect held DLT/parked entries through the ADMIN API. Repair the owning cause,
   then retry/redrive with current `expectedVersion`, fresh UUID `requestId`, and
   a safe explanatory `reason`. Reuse the request ID for an uncertain command
   result. Do not edit raw payloads, erase receipts or blindly replay a whole queue.
7. For mail failures, inspect queue state/error codes with restricted operator
   access. Restore SMTP/configuration; preserve one-use token semantics and
   encrypted pending payloads. SMTP/Mailpit acceptance does not prove inbox delivery.
8. Roll back an application only when its compatibility with the current schema
   is established. There are no automatic down migrations. Restore backups only
   through the deployment's explicit recovery procedure with understood data loss;
   do not delete volumes, run Flyway clean, or rewrite applied migration checksums.

Production alert routing, TLS/SASL, backup schedules, retention, release approval
and remote branch protection remain deployment responsibilities. No deployment
or backup/restore exercise is claimed by this milestone.
