# Core: Verification evidence (Milestone 08)

Verification date: 2026-09-21.

Local evidence for milestone 8 on `features/core-features`, based on commit
`fa92582` plus the milestone 8 working-tree changes. This is a dated snapshot,
not a standing claim about future changes or a successful GitHub Actions run.

Environment: Windows/PowerShell 7, JDK 21.0.10, Docker Engine 29.7.2, Python 3.12.
No application Java sources, migrations or dependency versions changed in this
milestone. CI/tooling, isolated smoke infrastructure and documentation were added.

## Executed gates

| Check | Result |
| --- | --- |
| Root `mvnw.cmd -B -ntp clean verify` | BUILD SUCCESS for all three services; completed 16:14:11 +07:00. |
| Surefire/Failsafe report gate | 17 suites, 143 tests, zero failures/errors/skips. |
| Report gate regression | Pass; rejects missing, empty, incomplete, skipped and failed suites. |
| Contract validation | All 11 OpenAPI documents and five event JSON Schemas pass, including local reference/pointer resolution. |
| Main Compose template | `docker compose --env-file .env.example -f infra/compose.yaml config --quiet` passes. |
| GitHub Actions workflow | `actionlint` 1.7.12 passes; release archive SHA-256 matched official checksum. |
| Packaged Core smoke | 11 assertion groups pass against fresh PostgreSQL/Kafka/Mailpit and the repository Nginx configuration. |
| Ingress configuration | `nginx -t` passes in `nginx:1.29.3-alpine` during smoke. |
| Patch whitespace | `git diff --check` passes. |

The JAR used in smoke has SHA-256
`888450409c928e86b072ca6ede2202a69c6edfa01ba61877c25f9967afbddf18`.
The smoke exercises the freshly built JAR in the non-root JRE layout; it does not
rebuild the production multi-stage Dockerfile. Its stack is removed after the run.

## Behavior evidence

| Boundary | Suite(s) / tests |
| --- | --- |
| Runtime, PostgreSQL migrations, Kafka, local SMTP, Problem Details | `FoundationIntegrationTest` — 12 |
| Identity/security filter chain, sessions, verification/recovery, concurrency | `IdentitySecurityIntegrationTest` — 24; `IdentityPolicyTest` — 4 |
| Profile limits, catalog revisions, visibility, Media projections | `CatalogProfilesIntegrationTest` — 13 |
| Simulated Premium idempotency/concurrency and entitlement | `SubscriptionIntegrationTest` — 10; `EntitlementIntegrationTest` — 11 |
| Playback/history correctness and media-ticket contract | `PlaybackIntegrationTest` — 17; `PlaybackTicketContractTest` — 6 |
| Watchlist, qualified reviews and moderation | `CommunityIntegrationTest` — 14 |
| Scoped Media service HTTP identity | `InternalServiceSecurityIntegrationTest` — 3 |
| Analytics fixture reads/fallback and outbound JWTs | `AnalyticsReadIntegrationTest` — 10; `AnalyticsServiceTokensTest` — 3 |
| Kafka delivery/recovery, leases, DLT/redrive, audit and migration | `IntegrationOperationsIntegrationTest` — 11; `EventEnvelopeTest` — 2 |
| Core, Media and Analytics wiring | Three application context tests — 3 total |

The packaged smoke additionally proves actual HTTP ingress cookie/CSRF behavior,
scheduled mail/outbox workers, fresh schema setup, ownership/role denials, Premium
replay, synthetic Media event consumption/publication, playback/history and
unpublication renewal denial. See [the exact smoke scope](core-delivery.md#packaged-smoke-coverage-and-limits).
Generated machine-readable evidence is in `target/verification/` (ignored by Git).

## Explicit remaining boundaries

- GitHub Actions has been authored and locally linted; no remote run or branch
  protection change is claimed. The Linux workflow still needs its first remote run.
- Analytics HTTP tests use a local fixture and smoke leaves Analytics disabled.
  Actual Analytics consumption/projections are not implemented by this milestone.
- Media READY in smoke is a synthetic Kafka event. No FFmpeg, private object
  storage or playlist/segment delivery authorization is proved.
- SMTP acceptance is demonstrated in local Mailpit; no real inbox delivery.
- No browser/frontend flow, production TLS/SASL, deployment, live backup/restore,
  dependency vulnerability scan or production release acceptance is claimed.

Reproduce with [Core delivery gates](core-delivery.md#run-the-same-gates-locally).
Deployments also need the [configuration reference](core-configuration.md) and
[operator runbook](core-delivery.md#operator-runbook).
