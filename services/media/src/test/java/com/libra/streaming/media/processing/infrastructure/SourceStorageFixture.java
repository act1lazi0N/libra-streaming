package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.SourceFreezer;
import com.libra.streaming.media.processing.application.SourceStorage;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.storage.MediaStorageProperties;
import com.libra.streaming.media.storage.StagingUploadSigner;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Real PostgreSQL and SeaweedFS shared by the source-freezing suites. The containers are started once per JVM
 * (not per class) so the cached Spring context never points at a stopped container.
 */
@SpringBootTest(properties = {
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
        "libra.media.storage.probe-on-startup=false"
})
abstract class SourceStorageFixture {
    static final String BUCKET = "libra-source";
    private static final String ACCESS = "fixture-" + UUID.randomUUID();
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();

    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_freeze_test").withUsername("media_freeze_test")
                    .withPassword(UUID.randomUUID().toString());

    static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:4.46"))
            .withCommand("mini", "-dir=/data", "-s3.config=/tmp/s3.json", "-webdav=false", "-admin.ui=false")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/libra-source").forStatusCode(403))
            .withCopyToContainer(Transferable.of(("""
                    {"identities":[
                      {"name":"fixture","credentials":[{"accessKey":"%s","secretKey":"%s"}],
                       "actions":["Admin:libra-source","Admin:libra-hls"]}
                    ]}
                    """).formatted(ACCESS, SECRET), 0444), "/tmp/s3.json")
            .withEnv("S3_BUCKET", "libra-source,libra-hls");

    static {
        POSTGRES.start();
        SEAWEEDFS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        String endpoint = "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
        registry.add("libra.media.storage.internal-endpoint", () -> endpoint);
        // Signed URLs include their host, so the browser endpoint must be the one this test can reach.
        registry.add("libra.media.storage.browser-endpoint", () -> endpoint);
        registry.add("libra.media.storage.browser-origin", () -> "http://localhost:8080");
        registry.add("libra.media.storage.access-key", () -> ACCESS);
        registry.add("libra.media.storage.secret-key", () -> SECRET);
        registry.add("libra.media.storage.scratch-directory",
                () -> Path.of("target", "source-freezing-fixture").toAbsolutePath().toString());
    }

    @Autowired SourceFreezer freezer;
    @Autowired SourceStorage sourceStorage;
    @Autowired JobLeases leases;
    @Autowired JobSources sources;
    @Autowired MediaPersistenceService uploads;
    @Autowired JdbcTemplate jdbc;
    @Autowired S3Client client;
    @Autowired StagingUploadSigner signer;
    @Autowired MediaStorageProperties configured;

    @BeforeEach void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    JobLease claim() { return leases.claim(Duration.ofSeconds(30)).orElseThrow(); }

    /** What a killed worker leaves behind: an active stage whose lease simply runs out. */
    void expire(JobLease lease) {
        jdbc.update("UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                lease.jobId());
    }

    MediaPersistenceService.Ensure queued(byte[] content) {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, content.length, sha(content), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
        return upload;
    }

    static String stagingKey(MediaPersistenceService.Ensure upload) {
        return "staging/" + upload.uploadId() + "/source.mp4";
    }

    static String attemptKey(MediaPersistenceService.Ensure upload, int attempt) {
        return "sources/" + upload.uploadId() + "/attempt-" + attempt + "/source.mp4";
    }

    void stage(MediaPersistenceService.Ensure upload, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload))
                .contentType("video/mp4").build(), RequestBody.fromBytes(content));
    }

    void deleteStaging(MediaPersistenceService.Ensure upload) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload)).build());
    }

    byte[] frozen(String key) {
        return client.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray();
    }

    List<String> objects(String prefix) {
        return client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
                .contents().stream().map(object -> object.key()).toList();
    }

    List<String> frozenObjects(MediaPersistenceService.Ensure upload) {
        return objects("sources/" + upload.uploadId() + "/");
    }

    String selectedKey(MediaPersistenceService.Ensure upload) {
        return jdbc.queryForObject("SELECT selected_source_key FROM media_assets WHERE asset_id = ?",
                String.class, upload.assetId());
    }

    /** Partial copies anywhere under the scratch root, whichever instance directory owns them. */
    long scratchFiles() throws Exception {
        try (var files = Files.walk(configured.scratchDirectory())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".part")).count();
        }
    }

    static byte[] bytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition was not reached");
    }
}
