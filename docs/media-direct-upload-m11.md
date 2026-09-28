# Media: Direct signed upload (Milestone 11)

M11 adds `POST /v1/admin/uploads/{uploadId}/upload-url` in Core and the matching
private Media command. Core requires a current ADMIN session, CSRF token, the
upload creator, and the current candidate binding. Media independently checks
the candidate with Core before signing. The response is `no-store` and contains
only the PUT URL, required browser-settable headers and grant expiry. Neither
service receives the video bytes.

Media signs the existing server-generated `staging/{uploadId}/source.mp4` key in
the private source bucket. The PUT signature binds `video/mp4`, the declared
content length and the expected SHA-256 checksum header. Browsers set
`Content-Length` from the Blob; JavaScript must send the returned
`Content-Type` and `x-amz-checksum-sha256` headers exactly. A signed URL is a
credential: keep it out of logs, traces and persistent client storage.

Grant expiry is the earlier of 15 minutes after issuance and the original
one-hour upload-session deadline. Reissuing a URL never changes the database
expiry, upload ID or staging key. The source bucket's CORS rule allows only the
configured browser origin and PUT/HEAD with the required upload headers.
Bucket/CORS initialization remains an explicit local setup option; operators
must apply the equivalent rule when that option is disabled. Media uses its
internal S3 endpoint for bucket management while signatures use the separately
configured browser-reachable endpoint. The S3 gateway preserves the browser
Host header for signature validation and does not log signed queries.

For disposable local verification, build both jars and run:

    pwsh -NoProfile -File infra/smoke/check-media-m11.ps1

The runner starts packaged Core and Media, separate PostgreSQL databases,
SeaweedFS 4.46 and the S3 gateway. It serves a test-only page from the allowed
origin and uses headless Chrome to execute the CORS preflight and direct PUT of
the small H.264 MP4 fixture. The sanitized result is
`target/verification/media-m11.json`; no URL, cookie, key or browser trace is
saved. The runner removes its disposable containers and browser profile.

An accepted PUT still leaves the upload `OPEN`, asset `UPLOADING`, and content
unpublished with no processing job. M12 owns negative signature/size/expiry
cases and verification of storage enforcement limits. M13 owns completion and
queue admission. This local check does not prove FFmpeg, HLS, production TLS,
remote CI or deployment.
