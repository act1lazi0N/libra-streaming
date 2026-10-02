package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
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
class JobRecoveryIntegrationTest {
    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_recovery_test").withUsername("media_recovery_test")
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

    @BeforeEach void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    @Test
    void renewalChecksDatabaseTimeAfterWaitingForRowLock() throws Exception {
        queued();
        var old = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        try (var holder = lock(old); var pool = Executors.newSingleThreadExecutor()) {
            try {
                var renewal = pool.submit(() -> leases.renew(old, Duration.ofSeconds(30)));
                awaitBlockedRenewal();
                try (var statement = holder.prepareStatement(
                        "UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?")) {
                    statement.setObject(1, old.jobId());
                    statement.executeUpdate();
                }
                holder.commit();
                assertThat(renewal.get(5, TimeUnit.SECONDS)).isFalse();
                var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
                assertThat(successor.attempt()).isEqualTo(2);
                assertThat(leases.renew(old, Duration.ofSeconds(30))).isFalse();
                assertThat(leases.renew(successor, Duration.ofSeconds(30))).isTrue();
            } finally { holder.rollback(); }
        }
    }

    @Test
    void blockedRenewalHasBoundedDatabaseWait() throws Exception {
        queued();
        var owner = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        // Release the lock even when the assertion fails, so a pre-fix run cannot hang its executor.
        try (var pool = Executors.newSingleThreadExecutor(); var holder = lock(owner)) {
            try {
                var renewal = pool.submit(() -> leases.renew(owner, Duration.ofSeconds(30)));
                awaitBlockedRenewal();
                assertThatThrownBy(() -> renewal.get(7, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(org.springframework.dao.QueryTimeoutException.class);
            } finally { holder.rollback(); }
        }
        assertThat(leases.renew(owner, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void databaseConnectionLossDuringRenewalLeavesRecoverableLease() throws Exception {
        var upload = queued();
        var old = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        try (var pool = Executors.newSingleThreadExecutor(); var holder = lock(old)) {
            try {
                var renewal = pool.submit(() -> leases.renew(old, Duration.ofSeconds(30)));
                int backend = awaitBlockedRenewal();
                assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, backend)).isTrue();
                assertThatThrownBy(() -> renewal.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally { holder.rollback(); }
        }
        assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(1);
        expire(old);
        var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(successor.attempt()).isEqualTo(2);
        assertStaleDenied(old, successor);
    }

    @Test
    void realWorkerCancelsOnLostRenewalConnectionAndRecoveryKeepsBudget() throws Exception {
        var upload = queued();
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var reported = new CountDownLatch(1);
        var owner = new AtomicReference<JobLease>();
        try (var worker = new BoundedMediaWorker(leases, (lease, stop) -> {
            owner.set(lease);
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) {
                assertThat(stop.getAsBoolean()).isTrue();
                cancelled.countDown();
                throw exception;
            }
            throw new AssertionError();
        }, new BoundedMediaWorker.Settings(Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofSeconds(1)), code -> {
            if (code.equals("WORKER_LEASE_UNAVAILABLE")) { reported.countDown(); }
        })) {
            worker.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var holder = lock(owner.get())) {
                try {
                    int backend = awaitBlockedRenewal();
                    assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, backend)).isTrue();
                    assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
                } finally { holder.rollback(); }
            }
        }
        assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(1);
        expire(owner.get());
        var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(successor.attempt()).isEqualTo(2);
        assertStaleDenied(owner.get(), successor);
    }

    @Test
    void everyActiveStageRejectsOldWritersWithoutSelectingObjectsOrSpendingSuccessorBudget() {
        for (String stage : new String[] {"CLAIMED", "SOURCE_SELECTED", "TRANSCODING", "FINALIZING"}) {
            queued();
            var old = leases.claim(Duration.ofSeconds(30)).orElseThrow();
            jdbc.update("UPDATE media_jobs SET stage = ? WHERE id = ?", stage, old.jobId());
            expire(old);
            var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
            assertStaleDenied(old, successor);
            assertThat(leases.fail(successor, ProcessingFailure.CORRUPT_INPUT)).isTrue();
        }
    }

    @Test
    void shutdownIgnoringHandlerAllowsSuccessorAndLateResultCannotOverwriteIt() throws Exception {
        var upload = queued();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var observed = new JobLeases() {
            public java.util.Optional<JobLease> claim(Duration lifetime) { return leases.claim(lifetime); }
            public boolean renew(JobLease lease, Duration lifetime) { return leases.renew(lease, lifetime); }
            public boolean retry(JobLease lease, ProcessingFailure failure, Duration delay) { return leases.retry(lease, failure, delay); }
            public boolean fail(JobLease lease, ProcessingFailure failure) { return leases.fail(lease, failure); }
            public boolean release(JobLease lease) {
                try {
                    assertThat(leases.release(lease)).isFalse();
                    return false;
                } finally { released.countDown(); }
            }
        };
        var worker = new BoundedMediaWorker(observed, (lease, cancelled) -> {
            entered.countDown();
            boolean resumed = false;
            while (!resumed) {
                try { resumed = resume.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Deliberately uncooperative. */ }
            }
            assertThat(cancelled.getAsBoolean()).isTrue();
            return MediaJobHandler.Outcome.reject(ProcessingFailure.CORRUPT_INPUT);
        }, new BoundedMediaWorker.Settings(Duration.ofMillis(20), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(100)), ignored -> {});
        try {
            worker.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.close();
            jdbc.update("UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second'");
            var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
            assertThat(successor.attempt()).isEqualTo(2);
            resume.countDown();
            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(leases.renew(successor, Duration.ofSeconds(30))).isTrue();
            assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("PROCESSING");
            assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(2);
        } finally {
            resume.countDown();
            worker.close();
        }
    }

    @Test
    void realWorkerRepeatedTransientFailureAdmitsExactlyThreeAttempts() throws Exception {
        var upload = queued();
        var attempts = new AtomicInteger();
        try (var worker = worker((lease, cancelled) -> {
            assertThat(lease.attempt()).isEqualTo(attempts.incrementAndGet());
            return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
        })) {
            worker.start();
            await(() -> uploads.read(upload.uploadId()).assetState().equals("FAILED"));
            assertThat(attempts).hasValue(3);
            assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(3);
            assertThat(uploads.read(upload.uploadId()).failureCode()).isEqualTo("RETRY_EXHAUSTED");
            assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
        }
    }

    @Test
    void repeatedGracefulCancellationDoesNotResetBudgetOrLeaveImmortalProcessing() {
        var upload = queued();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var owner = leases.claim(Duration.ofSeconds(30)).orElseThrow();
            assertThat(owner.attempt()).isEqualTo(attempt);
            assertThat(leases.release(owner)).isTrue();
            assertThat(leases.retry(owner, ProcessingFailure.PROCESSING_FAILED, Duration.ofSeconds(1))).isFalse();
        }
        assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("FAILED");
        assertThat(uploads.read(upload.uploadId()).failureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(3);
    }

    private void assertStaleDenied(JobLease old, JobLease successor) {
        var before = jdbc.queryForMap("SELECT * FROM media_jobs WHERE id = ?", successor.jobId());
        assertThat(leases.renew(old, Duration.ofSeconds(30))).isFalse();
        assertThat(leases.retry(old, ProcessingFailure.PROCESSING_FAILED, Duration.ofSeconds(1))).isFalse();
        assertThat(leases.fail(old, ProcessingFailure.CORRUPT_INPUT)).isFalse();
        assertThat(leases.release(old)).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM media_jobs WHERE id = ?", successor.jobId())).isEqualTo(before);
        var asset = jdbc.queryForMap("SELECT state, selected_source_key, output_prefix, master_manifest_key FROM media_assets WHERE asset_id = ? AND asset_version = ?",
                successor.assetId(), successor.assetVersion());
        assertThat(asset.get("state")).isEqualTo("PROCESSING");
        assertThat(asset.get("selected_source_key")).isNull();
        assertThat(asset.get("output_prefix")).isNull();
        assertThat(asset.get("master_manifest_key")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
    }

    private Connection lock(JobLease lease) throws Exception {
        var connection = jdbc.getDataSource().getConnection();
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM media_jobs WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, lease.jobId());
            statement.executeQuery().close();
        }
        return connection;
    }

    private int awaitBlockedRenewal() throws Exception {
        var backend = new AtomicInteger();
        await(() -> {
            var pids = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT j.lease_until%'", Integer.class);
            if (pids.isEmpty()) { return false; }
            backend.set(pids.getFirst());
            return true;
        });
        return backend.get();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(20);
        }
    }

    private BoundedMediaWorker worker(MediaJobHandler handler) {
        return new BoundedMediaWorker(leases, handler, new BoundedMediaWorker.Settings(Duration.ofMillis(20),
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(100)), ignored -> {});
    }

    private MediaPersistenceService.Ensure queued() {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
        return upload;
    }

    private void expire(JobLease lease) {
        jdbc.update("UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", lease.jobId());
    }
}
