package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.domain.JobLease;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL: source selection obeys the same single-owner fence as every other lease write. */
@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class JobSourceSelectionIntegrationTest {
    private static final String KEY = "sources/%s/attempt-%d/source.mp4";

    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_source_test").withUsername("media_source_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JobLeases leases;
    @Autowired JobSources sources;
    @Autowired MediaPersistenceService uploads;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    @Test
    void currentOwnerReadsTheDeclaredSourceAndCommitsOneSelection() {
        var upload = queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        var target = sources.target(lease).orElseThrow();
        assertThat(target.stagingKey()).isEqualTo("staging/" + upload.uploadId() + "/source.mp4");
        assertThat(target.byteLength()).isEqualTo(1024);
        assertThat(target.sha256()).isEqualTo("a".repeat(64));
        assertThat(target.selectedKey()).isNull();
        long aggregate = aggregateVersion(lease);

        assertThat(sources.select(lease, key(lease))).isTrue();

        assertThat(sources.target(lease).orElseThrow().selectedKey()).isEqualTo(key(lease));
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, lease.jobId()))
                .isEqualTo("SOURCE_SELECTED");
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                lease.assetId())).isEqualTo("PROCESSING");
        // A source selection is neither readiness nor an editorial event.
        assertThat(aggregateVersion(lease)).isEqualTo(aggregate);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        assertThat(leases.renew(lease, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void selectingTheSameKeyAgainIsIdempotentButAnotherKeyIsRefused() {
        queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(sources.select(lease, key(lease))).isTrue();
        assertThat(sources.select(lease, key(lease))).isTrue();
        assertThatThrownBy(() -> sources.select(lease, "sources/" + UUID.randomUUID() + "/attempt-1/source.mp4"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessage("Selected source is immutable")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(selectedKey(lease)).isEqualTo(key(lease));
    }

    @Test
    void expiredOldOwnerCannotSelectAndLeavesTheSuccessorUntouched() {
        queued();
        var old = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        expire(old);
        assertThat(sources.target(old)).isEmpty();
        var successor = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(successor.attempt()).isEqualTo(2);

        assertThat(sources.select(old, key(old))).isFalse();
        assertThat(selectedKey(old)).isNull();
        assertThat(sources.target(old)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, successor.jobId()))
                .isEqualTo("CLAIMED");

        assertThat(sources.select(successor, key(successor))).isTrue();
        assertThat(sources.select(old, key(old))).isFalse();
        assertThat(selectedKey(successor)).isEqualTo(key(successor));
    }

    @Test
    void anExpiredButNotYetReclaimedLeaseIsAlsoDenied() {
        queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        expire(lease);
        assertThat(sources.select(lease, key(lease))).isFalse();
        assertThat(selectedKey(lease)).isNull();
    }

    @Test
    void aSelectionCommittedByAnEarlierClaimSurvivesReleaseAndIsVisibleToTheNextClaim() {
        queued();
        var first = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(sources.select(first, key(first))).isTrue();
        assertThat(leases.release(first)).isTrue();

        var second = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(sources.target(second).orElseThrow().selectedKey()).isEqualTo(key(first));
        // A new claim starts at CLAIMED; re-selecting the committed key restores the progress marker only.
        assertThat(sources.select(second, key(first))).isTrue();
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, second.jobId()))
                .isEqualTo("SOURCE_SELECTED");
        assertThatThrownBy(() -> sources.select(second, key(second)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(selectedKey(second)).isEqualTo(key(first));
    }

    @Test
    void anObjectKeyCanBelongToOnlyOneAsset() {
        queued();
        var first = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        queued();
        var second = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(sources.select(first, key(first))).isTrue();
        assertThatThrownBy(() -> sources.select(second, key(first)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(selectedKey(second)).isNull();
    }

    @Test
    void invalidKeysAreRejectedBeforeAnyWrite() {
        queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        for (String invalid : new String[] {null, "", "  ", "x".repeat(513)}) {
            assertThatThrownBy(() -> sources.select(lease, invalid))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(selectedKey(lease)).isNull();
    }

    @Test
    void aSelectionRacingTheLeaseExpiryIsJudgedAfterTheLockWait() throws Exception {
        queued();
        var lease = leases.claim(Duration.ofSeconds(30)).orElseThrow();
        try (var holder = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM media_jobs WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, lease.jobId());
                statement.executeQuery();
            }
            var started = new CountDownLatch(1);
            var selection = pool.submit(() -> { started.countDown(); return sources.select(lease, key(lease)); });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked();
            try (var statement = holder.prepareStatement(
                    "UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?")) {
                statement.setObject(1, lease.jobId());
                statement.executeUpdate();
            }
            holder.commit();
            assertThat(selection.get(5, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(selectedKey(lease)).isNull();
    }

    private void awaitBlocked() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class) > 0) { return; }
            Thread.sleep(20);
        }
        throw new AssertionError("Selection never waited for the job row lock");
    }

    private static String key(JobLease lease) { return KEY.formatted(lease.uploadId(), lease.attempt()); }

    private String selectedKey(JobLease lease) {
        return jdbc.queryForObject("SELECT selected_source_key FROM media_assets WHERE asset_id = ?",
                String.class, lease.assetId());
    }

    private long aggregateVersion(JobLease lease) {
        return jdbc.queryForObject("SELECT aggregate_version FROM media_assets WHERE asset_id = ?", Long.class,
                lease.assetId());
    }

    private MediaPersistenceService.Ensure queued() {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
        return upload;
    }

    private void expire(JobLease lease) {
        jdbc.update("UPDATE media_jobs SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                lease.jobId());
    }
}
