package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.application.SourceFreezer;
import com.libra.streaming.media.processing.application.SourceStorage;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.storage.MediaStorageProperties;
import com.libra.streaming.media.storage.StagingUploadSigner;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.*;

/** Real SeaweedFS and PostgreSQL: bytes are proven, copied privately, and selected only by the lease owner. */
@Testcontainers
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
class SourceFreezingIntegrationTest {
    private static final String ACCESS = "fixture-" + UUID.randomUUID();
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String BUCKET = "libra-source";

    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_freeze_test").withUsername("media_freeze_test")
                    .withPassword(UUID.randomUUID().toString());

    @Container static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
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

    @Test
    void freezesTheExactStagedBytesIntoAnAttemptKeyAndSelectsItUnderTheLease() throws Exception {
        byte[] content = bytes(4096, 1);
        var upload = queued(content);
        stage(upload, content);
        var lease = claim();
        long before = scratchFiles();

        var result = freezer.freeze(lease, () -> false);

        String key = "sources/" + upload.uploadId() + "/attempt-1/source.mp4";
        assertThat(result).isEqualTo(new SourceFreezer.Result.Frozen(key));
        assertThat(frozen(key)).isEqualTo(content);
        assertThat(selectedKey(upload)).isEqualTo(key);
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, lease.jobId()))
                .isEqualTo("SOURCE_SELECTED");
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                upload.assetId())).isEqualTo("PROCESSING"); // frozen is not READY
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        assertThat(scratchFiles()).isEqualTo(before);
        assertThat(client.getObjectAsBytes(b -> b.bucket(BUCKET).key(stagingKey(upload))).asByteArray())
                .isEqualTo(content); // freezing copies; staging is left for later reconciliation
    }

    @Test
    void stagingChangesAfterSelectionNeverReachTheFrozenSourceAndRetryReusesIt() throws Exception {
        byte[] content = bytes(4096, 2);
        var upload = queued(content);
        stage(upload, content);
        var first = claim();
        var frozen = (SourceFreezer.Result.Frozen) freezer.freeze(first, () -> false);

        stage(upload, bytes(4096, 3)); // a reused upload URL could do this: same length, different bytes
        assertThat(leases.release(first)).isTrue();
        client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload)).build());
        var second = claim();
        assertThat(second.attempt()).isEqualTo(2);

        var retried = freezer.freeze(second, () -> false);

        assertThat(retried).isEqualTo(frozen);
        assertThat(selectedKey(upload)).isEqualTo(frozen.key());
        assertThat(frozen(frozen.key())).isEqualTo(content);
        assertThat(objects("sources/" + upload.uploadId() + "/")).containsExactly(frozen.key());
    }

    @Test
    void rejectedInputIsPermanentAndLeavesNoFrozenObjectSelectionOrScratchFile() throws Exception {
        record Case(String name, byte[] declared, byte[] staged, ProcessingFailure expected) {}
        byte[] content = bytes(4096, 4);
        var cases = java.util.List.of(
                new Case("missing", content, null, ProcessingFailure.SOURCE_MISSING),
                new Case("undersized", content, bytes(4000, 4), ProcessingFailure.SIZE_MISMATCH),
                new Case("oversized", content, bytes(4096 + 1000, 4), ProcessingFailure.SIZE_MISMATCH),
                new Case("beyond the object limit", content, bytes(2 * 1024 * 1024, 4), ProcessingFailure.SIZE_MISMATCH),
                new Case("checksum", content, bytes(4096, 5), ProcessingFailure.CHECKSUM_MISMATCH));
        long before = scratchFiles();
        for (var rejected : cases) {
            var upload = queued(rejected.declared());
            if (rejected.staged() != null) { stage(upload, rejected.staged()); }
            var lease = claim();

            var result = freezer.freeze(lease, () -> false);

            assertThat(result).as(rejected.name()).isInstanceOf(SourceFreezer.Result.Rejected.class);
            var outcome = ((SourceFreezer.Result.Rejected) result).outcome();
            assertThat(outcome.failure()).as(rejected.name()).isEqualTo(rejected.expected());
            assertThat(outcome.permanent()).as(rejected.name()).isTrue();
            assertThat(selectedKey(upload)).as(rejected.name()).isNull();
            assertThat(objects("sources/" + upload.uploadId() + "/")).as(rejected.name()).isEmpty();
            assertThat(leases.fail(lease, outcome.failure())).isTrue();
        }
        assertThat(scratchFiles()).isEqualTo(before);
    }

    @Test
    void theIssuedUploadGrantCanRewriteStagingButNeverTheFrozenObject() throws Exception {
        byte[] content = bytes(2048, 6);
        var upload = queued(content);
        var grant = signer.sign(upload.uploadId(), stagingKey(upload), content.length, sha(content),
                Instant.now().plusSeconds(600), Instant.now());
        try (var http = HttpClient.newHttpClient()) {
            assertThat(put(http, URI.create(grant.url()), grant.requiredHeaders(), content)).isEqualTo(200);
            var lease = claim();
            var frozen = (SourceFreezer.Result.Frozen) freezer.freeze(lease, () -> false);

            // The grant is reusable until it expires, and staging is mutable: that is why a snapshot exists.
            assertThat(put(http, URI.create(grant.url()), grant.requiredHeaders(), content)).isEqualTo(200);
            URI frozenUri = URI.create(grant.url().replace("/" + stagingKey(upload), "/" + frozen.key()));
            assertThat(frozenUri.getPath()).endsWith(frozen.key());
            assertThat(put(http, frozenUri, grant.requiredHeaders(), content)).isEqualTo(403);
            URI otherAttempt = URI.create(grant.url().replace("/" + stagingKey(upload),
                    "/sources/" + upload.uploadId() + "/attempt-2/source.mp4"));
            assertThat(put(http, otherAttempt, grant.requiredHeaders(), content)).isEqualTo(403);

            assertThat(frozen(frozen.key())).isEqualTo(content);
            assertThat(objects("sources/" + upload.uploadId() + "/")).containsExactly(frozen.key());
        }
    }

    @Test
    void anAttemptThatLosesItsLeaseBeforeCommitLeavesAnOrphanAndCannotReplaceTheSuccessorsSource() throws Exception {
        byte[] content = bytes(4096, 7);
        var upload = queued(content);
        stage(upload, content);
        var old = claim();
        var expiring = new SourceStorage() {
            @Override public Optional<StagedCopy> stage(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException { return sourceStorage.stage(key, bytes, cancelled); }
            @Override public String frozenKey(JobLease lease) { return sourceStorage.frozenKey(lease); }
            @Override public Optional<Digest> digest(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException { return sourceStorage.digest(key, bytes, cancelled); }
            @Override public void store(String key, StagedCopy copy) {
                sourceStorage.store(key, copy);
                // The object is durable, then the heartbeat is lost before the selection can commit.
                jdbc.update("UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                        old.jobId());
            }
        };

        var abandoned = new SourceFreezer(sources, expiring).freeze(old, () -> false);

        String orphan = "sources/" + upload.uploadId() + "/attempt-1/source.mp4";
        assertThat(abandoned).isInstanceOf(SourceFreezer.Result.LeaseLost.class);
        assertThat(selectedKey(upload)).isNull();
        assertThat(objects("sources/" + upload.uploadId() + "/")).containsExactly(orphan);

        var successor = claim();
        var frozen = (SourceFreezer.Result.Frozen) freezer.freeze(successor, () -> false);
        String selected = "sources/" + upload.uploadId() + "/attempt-2/source.mp4";
        assertThat(frozen.key()).isEqualTo(selected);
        assertThat(sources.select(old, orphan)).isFalse();
        assertThat(selectedKey(upload)).isEqualTo(selected);
        assertThat(objects("sources/" + upload.uploadId() + "/")).containsExactlyInAnyOrder(orphan, selected);
        assertThat(frozen(selected)).isEqualTo(content);
    }

    @Test
    void aRealWorkerRetryReusesTheCommittedSourceEvenWhenStagingIsGone() throws Exception {
        byte[] content = bytes(4096, 8);
        var upload = queued(content);
        stage(upload, content);
        var keys = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var calls = new AtomicInteger();
        var failed = new AtomicReference<Throwable>();
        MediaJobHandler handler = (lease, cancelled) -> {
            try {
                var result = (SourceFreezer.Result.Frozen) freezer.freeze(lease, cancelled);
                keys.add(result.key());
                if (calls.incrementAndGet() == 1) {
                    client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload)).build());
                    return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
                }
                // No probe or transcode stage exists yet; the fixture ends the job so the test can finish.
                return MediaJobHandler.Outcome.reject(ProcessingFailure.UNSUPPORTED_MEDIA);
            } catch (RuntimeException | Error exception) {
                failed.set(exception);
                throw exception;
            }
        };
        try (var worker = new BoundedMediaWorker(leases, handler, new BoundedMediaWorker.Settings(
                Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(20),
                Duration.ofSeconds(2)), code -> {})) {
            worker.start();
            await(() -> "FAILED_PERMANENT".equals(jdbc.queryForObject(
                    "SELECT stage FROM media_jobs WHERE upload_id = ?", String.class, upload.uploadId())));
        }

        assertThat(failed.get()).isNull();
        String key = "sources/" + upload.uploadId() + "/attempt-1/source.mp4";
        assertThat(keys).containsExactly(key, key);
        assertThat(selectedKey(upload)).isEqualTo(key);
        assertThat(frozen(key)).isEqualTo(content);
        assertThat(objects("sources/" + upload.uploadId() + "/")).containsExactly(key);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs WHERE upload_id = ?", Integer.class,
                upload.uploadId())).isEqualTo(2);
    }

    private JobLease claim() { return leases.claim(Duration.ofSeconds(30)).orElseThrow(); }

    private MediaPersistenceService.Ensure queued(byte[] content) throws Exception {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, content.length, sha(content), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
        return upload;
    }

    private static String stagingKey(MediaPersistenceService.Ensure upload) {
        return "staging/" + upload.uploadId() + "/source.mp4";
    }

    private void stage(MediaPersistenceService.Ensure upload, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload))
                .contentType("video/mp4").build(), RequestBody.fromBytes(content));
    }

    private byte[] frozen(String key) {
        return client.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray();
    }

    private java.util.List<String> objects(String prefix) {
        return client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
                .contents().stream().map(object -> object.key()).toList();
    }

    private String selectedKey(MediaPersistenceService.Ensure upload) {
        return jdbc.queryForObject("SELECT selected_source_key FROM media_assets WHERE asset_id = ?",
                String.class, upload.assetId());
    }

    private long scratchFiles() throws Exception {
        try (var files = Files.list(configured.scratchDirectory())) { return files.count(); }
    }

    private static int put(HttpClient http, URI uri, java.util.Map<String, String> headers, byte[] body)
            throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        return http.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] bytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(50);
        }
        throw new AssertionError("Condition was not reached");
    }
}
