# Media: Storage security (Milestone 06)

M06 hardens the existing storage runtime. It does not enable upload, service authentication, FFmpeg, Kafka publication, or HLS delivery.

## Configuration and failure behavior

Media rejects missing credentials, whitespace/control characters, unsupported credential characters, invalid lengths, identical access/secret keys, endpoint userinfo/query/fragment/path, invalid ports, and HTTP without the explicit development allowance. The local credential alphabet is `A-Z a-z 0-9 _ + = / -`; access keys are 3-128 characters and secrets 16-256 characters. Generate independent random values, rather than choosing a memorable password.

At startup Media checks both configured buckets with its configured identity, even when initialization and the synthetic probe are disabled. A rejected identity, missing bucket, incorrect endpoint, or unavailable dependency prevents startup. No default credential provider or fallback storage is used. Readiness includes database and storage availability; an S3 outage returns HTTP 503 while liveness remains HTTP 200, and readiness recovers when storage returns. The health check is read-only: it proves bucket access, not write permission or object durability. Write/delete denial still fails the operation, and only a genuine object HEAD 404 becomes an empty lookup.

SDK failures become `MEDIA_STORAGE_ACCESS_DENIED` or `MEDIA_STORAGE_UNAVAILABLE` without a raw SDK cause. Startup diagnostics expose only HTTP status or `transport`; health returns no remote details. SDK logging is disabled. Nginx request/error logs are disabled on these local proxy configurations because signed query strings and authorization headers must not enter logs. Use readiness and sanitized fixture results for this checkpoint; it does not establish production telemetry.

## Local storage boundary

SeaweedFS belongs only to the internal `storage-private` Docker network and has no published host ports. The S3 gateway is the only bridge to the normal application network. It forwards only the two configured local bucket paths to SeaweedFS port 8333; filer/master/volume ports are not forwarded. Its browser-visible port binds to `127.0.0.1:8333`. Media uses `http://s3-gateway:8333`; the browser endpoint remains `http://localhost:8333`. CORS is not an authorization control.

Public Nginx rejects the private Media control prefix and storage/admin path prefixes. Unrelated upstreams have no storage-network attachment. The trusted gateway can reach the private network; this boundary does not protect against host/Docker administrators or a compromised gateway.

`start-private.sh` requires explicit credentials, writes a mode-0600 configuration inside a bounded tmpfs, unsets credential variables before starting SeaweedFS, and disables WebDAV/admin UI. The local identity has administrative rights only on `libra-source` and `libra-hls`, supporting explicit bucket/CORS initialization. Production should provision separate initialization and runtime identities. This local setup makes no production IAM, network policy, or TLS claim.

The configuration-file path avoids the environment-identity branch that logs access keys in [SeaweedFS 4.46 source](https://github.com/seaweedfs/seaweedfs/blob/4.46/weed/s3api/auth_credentials.go). Both the generated configuration and credentials remain outside tracked files and image build arguments.

## Local setup

Copy `.env.example` to the ignored `.env` if needed. The storage values intentionally start empty and Compose rejects them. In PowerShell 7, the following fills only empty storage placeholders without printing generated values or replacing existing credentials:

```powershell
$mediaEnvPath = Join-Path (Get-Location) '.env'
$mediaEnvText = [IO.File]::ReadAllText($mediaEnvPath)
foreach ($mediaSetting in @('SEAWEEDFS_ACCESS_KEY', 'SEAWEEDFS_SECRET_KEY')) {
    $mediaPattern = '(?m)^' + $mediaSetting + '=\r?$'
    if ([regex]::IsMatch($mediaEnvText, $mediaPattern)) {
        $mediaRandom = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $mediaEnvText = [regex]::Replace($mediaEnvText, $mediaPattern, $mediaSetting + '=' + $mediaRandom)
    }
}
[IO.File]::WriteAllText($mediaEnvPath, $mediaEnvText)
Remove-Variable mediaEnvText, mediaRandom -ErrorAction SilentlyContinue
docker compose --env-file .env -f infra/compose.yaml config --quiet
```

Keep the same credentials across restarts. Initialize buckets/CORS explicitly with `MEDIA_STORAGE_INITIALIZE_BUCKETS=true` when required, then disable the flag. Existing independently provisioned storage needs an operator-managed credential migration; no persistent workstation volume is changed by the smoke test.

## Verification

```powershell
.\mvnw.cmd -B -ntp -pl services/media clean verify
pwsh -NoProfile -File infra/smoke/check-media-m06.ps1
```

The Media lane contains four unit/wiring tests, seven real PostgreSQL persistence tests, and six real SeaweedFS tests. The storage tests use H2 only for unrelated application wiring. They cover authenticated round trips, both buckets' anonymous GET/LIST/PUT denial with unchanged object bytes, incorrect access key, mismatched secret, a read-only identity denied writes/deletes and HLS access, unavailable endpoint, and credential-free storage logs.

The packaged smoke builds Media, uses real PostgreSQL/SeaweedFS and the actual repository Nginx/gateway configuration, and creates a random disposable Compose project. Core/Web/Analytics upstreams are explicit HTTP stubs. It checks main Compose isolation and missing-credential rejection, signed storage operations, HTTP denial through the gateway and public ingress, host-loopback denial, private-network port isolation, invalid packaged configuration, unavailable/wrong storage endpoints, readiness outage/recovery, and credential/query/header canaries in captured logs. Only aggregate results are written to `target/verification/media-m06.json`; captured raw logs stay in memory. Cleanup removes only the generated fixture project and its disposable volumes.

Passing this gate is local storage evidence. It does not prove browser CORS or presigned upload issuance, Core-to-Media authentication, FFmpeg/HLS, remote CI, or a production deployment. M07 remains the next separately authorized milestone.
