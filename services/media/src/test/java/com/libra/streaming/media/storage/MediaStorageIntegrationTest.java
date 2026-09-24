package com.libra.streaming.media.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
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

    @Container static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:4.46"))
            .withCommand("mini", "-dir=/data")
            .withExposedPorts(8333)
            .withEnv("AWS_ACCESS_KEY_ID", ACCESS)
            .withEnv("AWS_SECRET_ACCESS_KEY", SECRET)
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
