package com.libra.streaming.media.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        return new MediaStorageProperties(endpoint.toString(), configured.browserEndpoint(), configured.browserOrigin(),
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
}
