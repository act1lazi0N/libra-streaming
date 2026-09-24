# Media runtime and private storage checkpoint (M05)

M05 adds an internal SeaweedFS S3 adapter and a packaged runtime probe. It does not enable an upload API, browser PUT URL, protected HLS route, FFmpeg job, or Kafka publication. The existing Media HTTP security chain still denies non-actuator requests.

## Runtime configuration

Media now requires explicit `MEDIA_DB_URL`, `MEDIA_DB_USERNAME`, and `MEDIA_DB_PASSWORD`. The storage configuration is enabled by default and requires an internal S3 endpoint, a browser-visible endpoint, a browser origin for future PUT CORS, region, access/secret key, two distinct buckets, staging/frozen/HLS prefixes, connect/request timeouts, maximum object bytes, and an absolute scratch directory. Missing credentials, malformed origins/prefixes, excess size limits, and HTTP endpoints without explicit `MEDIA_S3_ALLOW_HTTP=true` fail startup. Storage can be disabled explicitly for isolated persistence tests; this does not enable a fallback object store.

The full local Compose stack maps SeaweedFS credentials to Media through runtime environment variables, never Docker build arguments. Its Media root filesystem is read-only, with a bounded `/tmp` tmpfs for scratch files, a non-root process, no added Linux capabilities, and no published Media port. SeaweedFS host ports bind to loopback. These are local development settings; production needs its own credential issuance, private network policy, and HTTPS endpoints.

`MEDIA_STORAGE_INITIALIZE_BUCKETS=true` is an explicit one-shot path to create missing source/HLS buckets and set source-bucket CORS for the configured browser origin, `PUT`/`HEAD`, and `Content-Type`. Turn it off after initialization. SeaweedFS `weed mini` also creates the fixture buckets from `S3_BUCKET`; the explicit path remains necessary to set CORS. `MEDIA_STORAGE_PROBE_ON_STARTUP=true` writes a random synthetic object under the frozen-source prefix, checks metadata, downloads and compares its bytes, then deletes the remote object and scratch files. Both flags default to false. The browser endpoint is validated but is not used to issue signed URLs yet.

The package-private adapter accepts only configured bucket/prefix areas and rejects traversal, empty segments, keys over 512 characters, unsupported content types, files outside the scratch directory, and objects over the configured byte limit. Reads stream into a generated scratch file with a per-byte cap and length check. Object deletion is scoped to the same area. An authorized caller still needs its own binding and state checks before using this adapter in later milestones.

## Verified local inventory and evidence

- Java 21, Spring Boot 4.1.1, Maven wrapper, AWS SDK for Java 2.54.17 BOM with `s3` and `url-connection-client`; unused Apache/Netty SDK clients are excluded. The resolved Maven dependency tree kept the AWS modules at 2.54.17.
- SeaweedFS `chrislusf/seaweedfs:4.46`, local image digest `sha256:08d516132314207d10c8e37cbffc1f32b147d870169688734cc61c6231625b62`; PostgreSQL `18.6-alpine`. The Dockerfile uses Maven `3.9.16-eclipse-temurin-21` to build and `eclipse-temurin:21-jre` to run. Image tags other than the observed local SeaweedFS digest are version tags, not immutable digest pins.
- `mvnw.cmd -B -ntp -pl services/media verify` passed one context test and eight integration tests: seven PostgreSQL persistence cases plus one real SeaweedFS round trip with bucket/CORS initialization and scoped-key rejections.
- `infra/smoke/check-media-m05.ps1` built the packaged Media image in a random disposable Compose project with real PostgreSQL and SeaweedFS, generated fresh fixture credentials, observed initialization and write/read/delete probe success, confirmed a second packaged process rejects a missing S3 secret, and removed only that project. Its sanitized result is `target/verification/media-m05.json`.
- `docker compose --env-file .env.example -f infra/compose.yaml config --quiet` passed. `.env.example` contains only local example values and should not be used as production credentials.

The AWS SDK client sets optional request/response checksum handling to `WHEN_REQUIRED` for compatibility with the pinned SeaweedFS S3 implementation. The first real probe exposed a `Content-Md5` rejection with the SDK default; the focused configuration change and both the Testcontainers and packaged probes passed afterward. [AWS documents this client setting](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html); [SeaweedFS documents `weed mini` S3 credentials and bucket setup](https://github.com/seaweedfs/seaweedfs/tree/4.46).

M06 still needs explicit denial and exposure checks for anonymous requests, incorrect credentials, alternate filer/master/volume routes, and unavailable storage. No browser, transcoding, or production deployment evidence is claimed here.
