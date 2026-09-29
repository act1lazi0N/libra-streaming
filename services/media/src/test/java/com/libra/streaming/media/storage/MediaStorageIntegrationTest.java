package com.libra.streaming.media.storage;

import com.libra.streaming.media.upload.application.UploadPersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.testcontainers.images.builder.Transferable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-storage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "libra.media.storage.enabled=true",
        "libra.media.storage.region=us-east-1",
        "libra.media.storage.source-bucket=libra-source",
        "libra.media.storage.hls-bucket=libra-hls",
        "libra.media.storage.staging-prefix=staging/",
        "libra.media.storage.source-prefix=sources/",
        "libra.media.storage.hls-prefix=hls/",
        "libra.media.storage.connect-timeout=3s",
        "libra.media.storage.request-timeout=15s",
        "libra.media.storage.max-object-bytes=1048576",
        "libra.media.storage.allow-http=true",
        "libra.media.storage.initialize-buckets=true",
        "libra.media.storage.probe-on-startup=true"
})
class MediaStorageIntegrationTest {
    private static final String ACCESS = "fixture-" + UUID.randomUUID();
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String READ_ACCESS = "reader-" + UUID.randomUUID();
    private static final String READ_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

    @Container static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:4.46"))
            .withCommand("mini", "-dir=/data", "-s3.config=/tmp/s3.json", "-webdav=false", "-admin.ui=false")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/libra-source").forStatusCode(403))
            .withCopyToContainer(Transferable.of(("""
                    {"identities":[
                      {"name":"fixture","credentials":[{"accessKey":"%s","secretKey":"%s"}],
                       "actions":["Admin:libra-source","Admin:libra-hls"]},
                      {"name":"reader","credentials":[{"accessKey":"%s","secretKey":"%s"}],
                       "actions":["Read:libra-source","List:libra-source"]}
                    ]}
                    """).formatted(ACCESS, SECRET, READ_ACCESS, READ_SECRET), 0444), "/tmp/s3.json")
            .withEnv("S3_BUCKET", "libra-source,libra-hls");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("libra.media.storage.internal-endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
        registry.add("libra.media.storage.browser-endpoint", () -> "http://localhost:8333");
        registry.add("libra.media.storage.browser-origin", () -> "http://localhost:8080");
        registry.add("libra.media.storage.access-key", () -> ACCESS);
        registry.add("libra.media.storage.secret-key", () -> SECRET);
        registry.add("libra.media.storage.scratch-directory",
                () -> Path.of("target", "media-storage-fixture").toAbsolutePath().toString());
    }

    @Autowired S3MediaStorage storage;
    @Autowired S3Client client;
    @Autowired MediaStorageProperties configured;
    @Autowired StagingObjectInspector inspector;

    @Test
    void completionInspectsActualStagingLengthAndTypeButDoesNotFreezeBytes() throws Exception {
        UUID id = UUID.randomUUID();
        String key = "staging/" + id + "/source.mp4";
        var source = completionSource(id, key);
        Path file = storage.scratchFile();
        try {
            assertThatThrownBy(() -> inspector.requireComplete(source)).hasMessage("SOURCE_MISSING");
            Files.write(file, new byte[1023]);
            storage.put(S3MediaStorage.Area.STAGING, key, file, "video/mp4");
            assertThatThrownBy(() -> inspector.requireComplete(source)).hasMessage("SOURCE_MISSING");
            Files.write(file, new byte[1024]);
            // SeaweedFS infers video/mp4 for octet-stream at an .mp4 key; use an explicit wrong MIME.
            client.putObject(PutObjectRequest.builder().bucket(configured.sourceBucket()).key(key)
                    .contentType("text/plain").build(), RequestBody.fromFile(file));
            assertThat(storage.head(S3MediaStorage.Area.STAGING, key).orElseThrow().contentType()).isEqualTo("text/plain");
            assertThatThrownBy(() -> inspector.requireComplete(source)).hasMessage("SOURCE_MISSING");
            storage.put(S3MediaStorage.Area.STAGING, key, file, "video/mp4");
            // Matching HEAD metadata admits the job; declared SHA-256 is verified by the later worker.
            inspector.requireComplete(source);
            assertThat(storage.head(S3MediaStorage.Area.SOURCE, "sources/" + id + "/source.mp4")).isEmpty();
        } finally {
            Files.deleteIfExists(file);
            storage.delete(S3MediaStorage.Area.STAGING, key);
        }
    }

    private static UploadPersistence.UploadSource completionSource(UUID id, String key) {
        return new UploadPersistence.UploadSource(
                new UploadPersistence.UploadSnapshot(id,
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                        1024, "a".repeat(64), "OPEN", "UPLOADING", null, 0, Instant.now().plusSeconds(3600)), key);
    }

    @Test
    void anonymousGetListAndPutAreDeniedForBothBucketsWithoutSideEffects() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (var area : new S3MediaStorage.Area[] {S3MediaStorage.Area.SOURCE, S3MediaStorage.Area.HLS}) {
                String bucket = area == S3MediaStorage.Area.SOURCE ? "libra-source" : "libra-hls";
                String key = (area == S3MediaStorage.Area.SOURCE ? "sources/" : "hls/") + UUID.randomUUID();
                Path source = storage.scratchFile();
                Files.writeString(source, "private-fixture");
                try {
                    storage.put(area, key, source, "application/octet-stream");
                    for (var request : new HttpRequest[] {
                            HttpRequest.newBuilder(configured.internalEndpointUri().resolve("/" + bucket + "/" + key)).GET().build(),
                            HttpRequest.newBuilder(configured.internalEndpointUri().resolve("/" + bucket + "?list-type=2")).GET().build(),
                            HttpRequest.newBuilder(configured.internalEndpointUri().resolve("/" + bucket + "/" + key))
                                    .PUT(HttpRequest.BodyPublishers.ofString("overwritten")).build()}) {
                        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
                    }
                    Path downloaded = storage.download(area, key);
                    try { assertThat(Files.readString(downloaded)).isEqualTo("private-fixture"); }
                    finally { Files.delete(downloaded); }
                } finally { storage.delete(area, key); Files.delete(source); }
            }
        }
    }

    @Test
    void incorrectKeyAndMismatchedSecretFailClosedWithSanitizedErrors() throws Exception {
        for (var credentials : new String[][] {{"unknown-" + UUID.randomUUID(), SECRET}, {ACCESS, READ_SECRET}}) {
            var properties = settings(configured.internalEndpointUri(), credentials[0], credentials[1]);
            try (var deniedClient = new MediaStorageConfiguration().mediaS3Client(properties)) {
                var denied = new S3MediaStorage(deniedClient, properties);
                assertThatThrownBy(() -> denied.head(S3MediaStorage.Area.SOURCE, "sources/private"))
                        .isInstanceOf(MediaStorageException.class).hasMessage("MEDIA_STORAGE_ACCESS_DENIED").hasNoCause();
                assertThatThrownBy(() -> new MediaStorageStartup(denied, deniedClient, properties).run(null))
                        .isInstanceOf(MediaStorageException.class).hasMessage("MEDIA_STORAGE_ACCESS_DENIED").hasNoCause();
                assertThat(new MediaStorageHealthIndicator(deniedClient, properties).health().getStatus().getCode())
                        .isEqualTo("DOWN");
            }
        }
    }

    @Test
    void insufficientBucketAndWritePermissionsNeverReportSuccessfulPersistence() throws Exception {
        var properties = settings(configured.internalEndpointUri(), READ_ACCESS, READ_SECRET);
        String key = "sources/permissions/" + UUID.randomUUID();
        Path source = storage.scratchFile();
        Files.writeString(source, "permitted-reader-fixture");
        try (var readClient = new MediaStorageConfiguration().mediaS3Client(properties)) {
            var readStorage = new S3MediaStorage(readClient, properties);
            storage.put(S3MediaStorage.Area.SOURCE, key, source, "application/octet-stream");
            assertThat(readStorage.head(S3MediaStorage.Area.SOURCE, key)).isPresent();
            assertThatThrownBy(() -> readStorage.put(S3MediaStorage.Area.SOURCE, key + "-denied", source,
                    "application/octet-stream")).hasMessage("MEDIA_STORAGE_ACCESS_DENIED").hasNoCause();
            assertThat(storage.head(S3MediaStorage.Area.SOURCE, key + "-denied")).isEmpty();
            assertThatThrownBy(() -> readStorage.delete(S3MediaStorage.Area.SOURCE, key))
                    .hasMessage("MEDIA_STORAGE_ACCESS_DENIED").hasNoCause();
            assertThat(storage.head(S3MediaStorage.Area.SOURCE, key)).isPresent();
            assertThatThrownBy(() -> readStorage.head(S3MediaStorage.Area.HLS, "hls/private"))
                    .hasMessage("MEDIA_STORAGE_ACCESS_DENIED").hasNoCause();
            assertThatThrownBy(() -> new MediaStorageStartup(readStorage, readClient, properties).run(null))
                    .hasMessage("MEDIA_STORAGE_ACCESS_DENIED").hasNoCause();
        } finally { storage.delete(S3MediaStorage.Area.SOURCE, key); Files.delete(source); }
    }

    @Test
    void unavailableEndpointIsNotReportedAsMissingObjectOrHealthyStorage() throws Exception {
        try (var unavailable = new java.net.ServerSocket(0)) {
            var properties = settings(URI.create("http://127.0.0.1:" + unavailable.getLocalPort()), ACCESS, SECRET);
            try (var offlineClient = new MediaStorageConfiguration().mediaS3Client(properties)) {
                var offline = new S3MediaStorage(offlineClient, properties);
                assertThatThrownBy(() -> offline.head(S3MediaStorage.Area.SOURCE, "sources/private"))
                        .isInstanceOf(MediaStorageException.class).hasMessage("MEDIA_STORAGE_UNAVAILABLE").hasNoCause();
                var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
                beans.registerSingleton("storage", offline);
                var unavailableInspector = new StagingObjectInspector(beans.getBeanProvider(S3MediaStorage.class));
                UUID id = UUID.randomUUID();
                assertThatThrownBy(() -> unavailableInspector.requireComplete(
                        completionSource(id, "staging/" + id + "/source.mp4")))
                        .hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
                assertThat(new MediaStorageHealthIndicator(offlineClient, properties).health().getStatus().getCode())
                        .isEqualTo("DOWN");
            }
        }
    }

    @Test
    void storageLogsDoNotContainFixtureCredentials() {
        String logs = SEAWEEDFS.getLogs();
        // Boolean assertions deliberately avoid dumping the raw log on failure.
        assertThat(logs.contains(ACCESS) || logs.contains(SECRET)
                || logs.contains(READ_ACCESS) || logs.contains(READ_SECRET)).isFalse();
    }

    private MediaStorageProperties settings(URI endpoint, String access, String secret) {
        return new MediaStorageProperties(endpoint.toString(), endpoint.toString(), configured.browserOrigin(),
                configured.region(), access, secret, configured.sourceBucket(), configured.hlsBucket(),
                configured.stagingPrefix(), configured.sourcePrefix(), configured.hlsPrefix(),
                Duration.ofSeconds(1), Duration.ofSeconds(2), configured.maxObjectBytes(),
                configured.scratchDirectory(), true, false, false);
    }

    @Test
    void signedIdentityCanRoundTripOnlyScopedPrivateObjects() throws Exception {
        byte[] content = "synthetic-private-media-object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String key = "sources/m05/" + UUID.randomUUID() + ".bin";
        Path source = storage.scratchFile();
        Path downloaded = null;
        try {
            Files.write(source, content);
            storage.put(S3MediaStorage.Area.SOURCE, key, source, "application/octet-stream");
            assertThat(storage.head(S3MediaStorage.Area.SOURCE, key).orElseThrow().length())
                    .isEqualTo(content.length);
            downloaded = storage.download(S3MediaStorage.Area.SOURCE, key);
            assertThat(Files.readAllBytes(downloaded)).isEqualTo(content);
            assertThatThrownBy(() -> storage.head(S3MediaStorage.Area.HLS, key))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(S3MediaStorage.Area.SOURCE, "sources/../other"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(client.getBucketCors(GetBucketCorsRequest.builder().bucket("libra-source").build())
                    .corsRules()).hasSize(1);
        } finally {
            storage.delete(S3MediaStorage.Area.SOURCE, key);
            Files.deleteIfExists(source);
            if (downloaded != null) { Files.deleteIfExists(downloaded); }
        }
        assertThat(storage.head(S3MediaStorage.Area.SOURCE, key)).isEmpty();
    }

    @Test
    void signedBrowserPutWritesOnlyTheStagingObject() throws Exception {
        byte[] clip = "synthetic-mp4-upload-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clip));
        UUID uploadId = UUID.randomUUID();
        String key = "staging/" + uploadId + "/source.mp4";
        var properties = new MediaStorageProperties(configured.internalEndpoint(),
                configured.internalEndpoint(), configured.browserOrigin(), configured.region(),
                configured.accessKey(), configured.secretKey(), configured.sourceBucket(), configured.hlsBucket(),
                configured.stagingPrefix(), configured.sourcePrefix(), configured.hlsPrefix(),
                configured.connectTimeout(), configured.requestTimeout(), configured.maxObjectBytes(),
                configured.scratchDirectory(), true, false, false);
        try (var presigner = new MediaStorageConfiguration().mediaS3Presigner(properties);
                var http = HttpClient.newHttpClient()) {
            var grant = new StagingUploadSigner(presigner, properties).sign(uploadId, key, clip.length,
                    digest, Instant.now().plusSeconds(3600), Instant.now());
            assertThat(grant.expiresAt()).isBefore(Instant.now().plusSeconds(901));
            assertThat(grant.requiredHeaders()).containsEntry("Content-Type", "video/mp4")
                    .containsEntry("x-amz-checksum-sha256",
                            java.util.Base64.getEncoder().encodeToString(HexFormat.of().parseHex(digest)))
                    .doesNotContainKey("content-length");
            var builder = HttpRequest.newBuilder(URI.create(grant.url()));
            grant.requiredHeaders().forEach(builder::header);
            var put = http.send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(clip)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(put.statusCode()).isEqualTo(200);
            assertThat(storage.head(S3MediaStorage.Area.STAGING, key).orElseThrow().length())
                    .isEqualTo((long) clip.length);
            storage.delete(S3MediaStorage.Area.STAGING, key);
        }
    }

    @Test
    void signedCapabilityRejectsTamperingAndAllowsOnlyIdenticalStagingReplay() throws Exception {
        byte[] bytes = "bounded-staging-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID id = UUID.randomUUID();
        String key = "staging/" + id + "/source.mp4";
        var properties = settings(configured.internalEndpointUri(), ACCESS, SECRET);
        try (var presigner = new MediaStorageConfiguration().mediaS3Presigner(properties);
                var http = HttpClient.newHttpClient()) {
            var grant = new StagingUploadSigner(presigner, properties).sign(id, key, bytes.length,
                    digest(bytes), Instant.now().plusSeconds(3600), Instant.now());
            URI uri = URI.create(grant.url());
            for (String target : new String[] {grant.url().replace("/staging/", "/sources/"),
                    grant.url().replace("/libra-source/staging/", "/libra-hls/hls/"),
                    grant.url().replace(id.toString(), UUID.randomUUID().toString())}) {
                assertThat(send(http, URI.create(target), "PUT", grant.requiredHeaders(), bytes)).isEqualTo(403);
            }
            for (String method : new String[] {"GET", "HEAD", "DELETE"}) {
                assertThat(send(http, uri, method, grant.requiredHeaders(), bytes)).isEqualTo(403);
            }
            for (String header : grant.requiredHeaders().keySet()) {
                var missing = new java.util.HashMap<>(grant.requiredHeaders());
                missing.remove(header);
                assertThat(send(http, uri, "PUT", missing, bytes)).isEqualTo(403);
                var changed = new java.util.HashMap<>(grant.requiredHeaders());
                changed.put(header, header.equals("Content-Type") ? "text/plain"
                        : java.util.Base64.getEncoder().encodeToString(new byte[32]));
                assertThat(send(http, uri, "PUT", changed, bytes)).isEqualTo(403);
            }
            assertThat(send(http, uri, "PUT", grant.requiredHeaders(), new byte[bytes.length + 1])).isEqualTo(403);
            assertThat(send(http, uri, "PUT", grant.requiredHeaders(), new byte[bytes.length - 1])).isEqualTo(403);
            assertThat(send(http, uri, "PUT", grant.requiredHeaders(), new byte[bytes.length])).isEqualTo(400);
            var chunked = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
            grant.requiredHeaders().forEach(chunked::header);
            assertThat(http.send(chunked.PUT(HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(bytes))).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            assertThat(storage.head(S3MediaStorage.Area.STAGING, key)).isEmpty();
            for (int replay = 0; replay < 2; replay++) {
                assertThat(send(http, uri, "PUT", grant.requiredHeaders(), bytes)).isEqualTo(200);
                assertThat(send(http, uri, "PUT", grant.requiredHeaders(), new byte[bytes.length])).isEqualTo(400);
                Path downloaded = storage.download(S3MediaStorage.Area.STAGING, key);
                try { assertThat(Files.readAllBytes(downloaded)).isEqualTo(bytes); }
                finally { Files.delete(downloaded); }
            }
            assertThat(storage.head(S3MediaStorage.Area.SOURCE, "sources/" + id + "/source.mp4")).isEmpty();
            assertThat(storage.head(S3MediaStorage.Area.HLS, "hls/" + id + "/source.mp4")).isEmpty();
        } finally { storage.delete(S3MediaStorage.Area.STAGING, key); }
    }

    @Test
    void expiredUrlRejectsNewRequestsButDoesNotCancelAnInflightWrite() throws Exception {
        byte[] bytes = "expiry-boundary-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID id = UUID.randomUUID();
        String key = "staging/" + id + "/source.mp4";
        var properties = settings(configured.internalEndpointUri(), ACCESS, SECRET);
        try (var presigner = new MediaStorageConfiguration().mediaS3Presigner(properties);
                var http = HttpClient.newHttpClient()) {
            Instant now = Instant.now();
            var grant = new StagingUploadSigner(presigner, properties).sign(id, key, bytes.length,
                    digest(bytes), now.plusSeconds(3), now);
            URI uri = URI.create(grant.url());
            try (var socket = new java.net.Socket(uri.getHost(), uri.getPort())) {
                socket.setSoTimeout(10000);
                StringBuilder headers = new StringBuilder("PUT " + uri.getRawPath() + "?" + uri.getRawQuery()
                        + " HTTP/1.1\r\nHost: " + uri.getRawAuthority() + "\r\nContent-Length: " + bytes.length
                        + "\r\nExpect: 100-continue\r\nConnection: close\r\n");
                grant.requiredHeaders().forEach((name, value) -> headers.append(name).append(": ").append(value).append("\r\n"));
                var output = socket.getOutputStream();
                output.write(headers.append("\r\n").toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                output.flush();
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                        java.nio.charset.StandardCharsets.US_ASCII));
                assertThat(reader.readLine()).isEqualTo("HTTP/1.1 100 Continue");
                assertThat(reader.readLine()).isEmpty();
                Thread.sleep(4200);
                assertThat(send(http, uri, "PUT", grant.requiredHeaders(), bytes)).isEqualTo(403);
                output.write(bytes);
                output.flush();
                assertThat(reader.readLine()).isEqualTo("HTTP/1.1 200 OK");
            }
            assertThat(storage.head(S3MediaStorage.Area.STAGING, key).orElseThrow().length()).isEqualTo(bytes.length);
        } finally { storage.delete(S3MediaStorage.Area.STAGING, key); }
    }

    @Test
    void issuanceCapsExpiryAndSizeAndNeverSignsFrozenOrOutputKeys() throws Exception {
        var properties = settings(configured.internalEndpointUri(), ACCESS, SECRET);
        UUID id = UUID.randomUUID();
        String key = "staging/" + id + "/source.mp4";
        Instant now = Instant.now();
        try (var presigner = new MediaStorageConfiguration().mediaS3Presigner(properties)) {
            var signer = new StagingUploadSigner(presigner, properties);
            for (long size : new long[] {0, -1, properties.maxObjectBytes() + 1, 268435457}) {
                assertThatThrownBy(() -> signer.sign(id, key, size, "a".repeat(64), now.plusSeconds(3600), now))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            for (String invalid : new String[] {"sources/" + id + "/source.mp4", "hls/" + id + "/source.mp4",
                    "staging/" + UUID.randomUUID() + "/source.mp4", "staging/../source.mp4"}) {
                assertThatThrownBy(() -> signer.sign(id, invalid, 1, "a".repeat(64), now.plusSeconds(3600), now))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            for (Instant expired : new Instant[] {now, now.minusNanos(1)}) {
                assertThatThrownBy(() -> signer.sign(id, key, 1, "a".repeat(64), expired, now))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            var maximum = signer.sign(id, key, properties.maxObjectBytes(), "a".repeat(64), now.plusSeconds(3600), now);
            assertThat(maximum.expiresAt()).isEqualTo(now.plusSeconds(900));
            assertThat(URI.create(maximum.url()).getRawQuery().contains("X-Amz-Expires=900")).isTrue();
            var nearExpiry = signer.sign(id, key, 1, "a".repeat(64), now.plusSeconds(7), now);
            assertThat(nearExpiry.expiresAt()).isEqualTo(now.plusSeconds(7));
            assertThat(URI.create(nearExpiry.url()).getRawQuery().contains("X-Amz-Expires=7")).isTrue();
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static int send(HttpClient http, URI uri, String method, java.util.Map<String, String> headers,
            byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        return http.send(request.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
