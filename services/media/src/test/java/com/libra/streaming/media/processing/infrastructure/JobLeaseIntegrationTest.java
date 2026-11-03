package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class JobLeaseIntegrationTest {
    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_worker_test").withUsername("media_worker_test")
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
    void independentConnectionsAdmitOnlyOneOwnerAndRenewThatOwner() throws Exception {
        var upload = queued();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<java.util.Optional<JobLease>> attempt = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return leases.claim(Duration.ofSeconds(30));
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .flatMap(java.util.Optional::stream).toList();
            assertThat(results).hasSize(1);
            var owner = results.getFirst();
            assertThat(owner.attempt()).isEqualTo(1);
            assertThat(leases.renew(owner, Duration.ofMinutes(1))).isTrue();
            assertThat(expiry(owner)).isAfter(owner.expiresAt());
            assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("PROCESSING");
            assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(1);
            assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
        }
    }

    @Test
    void restartRecoversSameDurableJobAndRejectsExpiredAndStaleWriters() {
        var upload = queued();
        var old = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        expire(old);
        assertThat(leases.renew(old, Duration.ofSeconds(30))).isFalse();
        assertThat(leases.retry(old, ProcessingFailure.PROCESSING_FAILED, Duration.ofSeconds(1))).isFalse();
        assertThat(leases.fail(old, ProcessingFailure.CORRUPT_INPUT)).isFalse();
        // A new adapter with an independent transaction manager models a replacement process.
        var restarted = independentLeases();
        var replacement = restarted.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(replacement.jobId()).isEqualTo(old.jobId());
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(replacement.attempt()).isEqualTo(2);
        assertThat(leases.release(old)).isFalse();
        assertThat(leases.fail(old, ProcessingFailure.CORRUPT_INPUT)).isFalse();
        assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("PROCESSING");
        assertThat(restarted.renew(replacement, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void retryTimingSurvivesNewAdapterAndThreeFailuresExhaustWithoutFourthAttempt() {
        var upload = queued();
        var first = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(leases.retry(first, ProcessingFailure.PROCESSING_FAILED, Duration.ofMinutes(1))).isTrue();
        var restarted = independentLeases();
        assertThat(restarted.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > clock_timestamp() FROM media_jobs WHERE id = ?",
                Boolean.class, first.jobId())).isTrue();
        due(first);
        var second = restarted.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(restarted.retry(second, ProcessingFailure.PROCESSING_FAILED, Duration.ofMinutes(1))).isTrue();
        due(second);
        var third = restarted.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(third.attempt()).isEqualTo(3);
        assertThat(restarted.retry(third, ProcessingFailure.PROCESSING_FAILED, Duration.ofMinutes(1))).isTrue();
        assertThat(stage(third)).isEqualTo("EXHAUSTED");
        assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("FAILED");
        assertThat(restarted.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM media_jobs WHERE id = ?", String.class,
                third.jobId())).isEqualTo("RETRY_EXHAUSTED");
        assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(3);
    }

    @Test
    void expiredThirdAttemptConvergesToFailureAndPermanentRejectionDoesNotRetry() {
        var upload = queued();
        var first = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        expire(first);
        var second = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        expire(second);
        var third = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        expire(third);
        assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(stage(third)).isEqualTo("EXHAUSTED");
        assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("FAILED");
        var rejected = queued();
        var claimed = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(leases.fail(claimed, ProcessingFailure.CHECKSUM_MISMATCH)).isTrue();
        assertThat(stage(claimed)).isEqualTo("FAILED_PERMANENT");
        assertThat(uploads.read(rejected.uploadId()).attemptCount()).isEqualTo(1);
        assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void cancellationReleasesCurrentLeaseWithoutResettingBudgetOrMarkingReady() {
        var upload = queued();
        var owner = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(leases.release(owner)).isTrue();
        assertThat(stage(owner)).isEqualTo("QUEUED");
        assertThat(leases.release(owner)).isFalse();
        var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(successor.attempt()).isEqualTo(2);
        assertThat(uploads.read(upload.uploadId()).assetState()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE state = 'READY'", Integer.class)).isZero();
    }

    @Test
    void wrongAssetTupleCannotFinalizeAndInvalidInputCannotConsumeRetries() {
        var upload = queued();
        var owner = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        var wrongAsset = new JobLease(owner.jobId(), owner.uploadId(), UUID.randomUUID(), 1,
                owner.attempt(), owner.token(), owner.expiresAt());
        assertThat(leases.renew(wrongAsset, Duration.ofSeconds(30))).isFalse();
        assertThat(leases.fail(wrongAsset, ProcessingFailure.CORRUPT_INPUT)).isFalse();
        assertThat(leases.release(wrongAsset)).isFalse();
        assertThat(stage(owner)).isEqualTo("CLAIMED");
        assertThat(leases.retry(owner, ProcessingFailure.CHECKSUM_MISMATCH, Duration.ofSeconds(10))).isTrue();
        assertThat(stage(owner)).isEqualTo("FAILED_PERMANENT");
        assertThat(uploads.read(upload.uploadId()).failureCode()).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(uploads.read(upload.uploadId()).attemptCount()).isEqualTo(1);
    }

    @Test
    void terminalUpdateRollsBackWithAssetWriteFailure() {
        queued();
        var owner = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_worker_failure() RETURNS trigger AS $$
                BEGIN IF NEW.state = 'FAILED' THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER reject_worker_failure BEFORE UPDATE ON media_assets FOR EACH ROW EXECUTE FUNCTION reject_worker_failure()");
        try {
            assertThatThrownBy(() -> leases.fail(owner, ProcessingFailure.CORRUPT_INPUT))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(stage(owner)).isEqualTo("CLAIMED");
            assertThat(leases.renew(owner, Duration.ofSeconds(30))).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER reject_worker_failure ON media_assets");
            jdbc.execute("DROP FUNCTION reject_worker_failure()");
        }
    }

    private JobLeases independentLeases() {
        var target = new JdbcJobLeases(jdbc);
        var template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        return new JobLeases() {
            public java.util.Optional<JobLease> claim(Duration d) { return template.execute(s -> target.claim(d)); }
            public boolean renew(JobLease l, Duration d) { return template.execute(s -> target.renew(l, d)); }
            public boolean retry(JobLease l, ProcessingFailure f, Duration d) { return template.execute(s -> target.retry(l, f, d)); }
            public boolean fail(JobLease l, ProcessingFailure f) { return template.execute(s -> target.fail(l, f)); }
            public boolean release(JobLease l) { return template.execute(s -> target.release(l)); }
        };
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
    private void due(JobLease lease) {
        jdbc.update("UPDATE media_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", lease.jobId());
    }
    private Instant expiry(JobLease lease) {
        return jdbc.queryForObject("SELECT lease_until FROM media_jobs WHERE id = ?", java.sql.Timestamp.class,
                lease.jobId()).toInstant();
    }
    private String stage(JobLease lease) {
        return jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, lease.jobId());
    }
}
