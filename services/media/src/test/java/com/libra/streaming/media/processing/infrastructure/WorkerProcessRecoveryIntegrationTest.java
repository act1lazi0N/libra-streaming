package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.fixture.ProcessWorkerInitializer;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class WorkerProcessRecoveryIntegrationTest {
    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_process_test").withUsername("media_process_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JobLeases leases;
    @Autowired MediaPersistenceService uploads;
    @Autowired JdbcTemplate jdbc;

    @Test
    void killedPackagedWorkerRecoversSameJobAndExhaustsWithoutFourthClaim() throws Exception {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        var admitted = uploads.queue(upload.uploadId(), upload.assetId(), 1);
        var evidence = Path.of("target", "m16-process").toAbsolutePath();
        Files.createDirectories(evidence);
        var fixture = fixtureJar(evidence);
        Process first = null;
        Process replacement = null;
        try {
            first = launch(fixture, evidence.resolve("first.log"), true);
            awaitClaim(first, admitted.jobId(), 1, evidence.resolve("first.log"));
            var old = current(admitted.jobId());
            first.destroyForcibly();
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
            // Use the real persisted expiry; no test-side lease edits in this process experiment.
            replacement = launch(fixture, evidence.resolve("replacement.log"), true);
            awaitClaim(replacement, admitted.jobId(), 2, evidence.resolve("replacement.log"));
            var successor = current(admitted.jobId());
            assertThat(successor.token()).isNotEqualTo(old.token());
            assertThat(leases.renew(old, Duration.ofSeconds(10))).isFalse();
            assertThat(leases.retry(old, ProcessingFailure.PROCESSING_FAILED, Duration.ofSeconds(1))).isFalse();
            assertThat(leases.fail(old, ProcessingFailure.CORRUPT_INPUT)).isFalse();
            assertThat(leases.release(old)).isFalse();
            assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(2);
            assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("PROCESSING");
            replacement.destroyForcibly();
            assertThat(replacement.waitFor(10, TimeUnit.SECONDS)).isTrue();
            replacement = launch(fixture, evidence.resolve("failures.log"), false);
            awaitClaim(replacement, admitted.jobId(), 3, evidence.resolve("failures.log"));
            await(() -> uploads.read(upload.uploadId()).assetState().equals("FAILED"), replacement);
            assertThat(uploads.read(upload.uploadId()).failureCode()).isEqualTo("RETRY_EXHAUSTED");
            assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(3);
            assertThat(leases.claim(Duration.ofSeconds(10))).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE selected_source_key IS NOT NULL OR output_prefix IS NOT NULL OR state = 'READY'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
            assertThat(Files.readString(evidence.resolve("failures.log"))).doesNotContain("M16_CLAIM " + admitted.jobId() + " 4");
            Files.writeString(evidence.resolve("result.txt"),
                    "PASS: packaged worker killed twice; same PostgreSQL job recovered; attempts 1,2,3; stale writes denied; FAILED/RETRY_EXHAUSTED; no source/output/READY/outbox.\n");
        } finally {
            stop(first);
            stop(replacement);
        }
    }

    private Process launch(Path fixture, Path output, boolean block) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path packaged = Path.of("target", "media-service-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        assertThat(packaged).isRegularFile();
        var builder = new ProcessBuilder(java.toString(), "-Dloader.path=" + fixture,
                "-Djdk.net.unixdomain.tmpdir=" + fixture.getParent().getParent(),
                "-cp", packaged.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher",
                "--server.address=127.0.0.1", "--server.port=0", "--libra.media.storage.enabled=false",
                "--libra.media.worker.enabled=true", "--libra.media.worker.poll-interval=100ms",
                "--libra.media.worker.lease-duration=2s", "--libra.media.worker.renew-interval=200ms",
                "--libra.media.worker.retry-delay=100ms", "--libra.media.worker.shutdown-timeout=200ms",
                "--m16.fixture.block=" + block, "--logging.level.root=ERROR");
        builder.environment().put("MEDIA_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("MEDIA_DB_USERNAME", POSTGRES.getUsername());
        builder.environment().put("MEDIA_DB_PASSWORD", POSTGRES.getPassword());
        return builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private Path fixtureJar(Path directory) throws Exception {
        Path fixture = directory.resolve("worker-fixture.jar");
        String resource = ProcessWorkerInitializer.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(fixture));
                var input = ProcessWorkerInitializer.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/spring.factories"));
            output.write(("org.springframework.context.ApplicationContextInitializer="
                    + ProcessWorkerInitializer.class.getName() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return fixture;
    }

    private JobLease current(UUID job) {
        return jdbc.queryForObject("""
                SELECT j.*, u.asset_id, u.asset_version FROM media_jobs j
                JOIN media_uploads u ON u.id = j.upload_id WHERE j.id = ?
                """, (rs, row) -> new JobLease(job, rs.getObject("upload_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"), rs.getInt("attempt_count"),
                rs.getObject("lease_token", UUID.class), rs.getTimestamp("lease_until").toInstant()), job);
    }

    private void awaitClaim(Process process, UUID job, int attempt, Path output) throws Exception {
        await(() -> {
            try { return Files.readString(output).contains("M16_CLAIM " + job + " " + attempt); }
            catch (java.io.IOException exception) { return false; }
        }, process);
    }

    private static void await(java.util.function.BooleanSupplier condition, Process process) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.getAsBoolean()) {
            assertThat(process.isAlive()).as("Packaged worker must remain alive; inspect target/m16-process logs").isTrue();
            assertThat(System.nanoTime()).as("Packaged worker progress deadline").isLessThan(end);
            Thread.sleep(50);
        }
    }

    private static void stop(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
