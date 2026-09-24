package com.libra.streaming.media.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class MediaPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_upload_test").withUsername("media_upload_test").withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired MediaPersistenceService persistence;
    @Autowired MediaUploadStore store;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    @Test
    void emptyMediaSchemaMigratesAndSessionIsIdempotent() {
        flyway.validate();
        var command = command();
        var first = persistence.ensure(command);
        var retry = persistence.ensure(command);
        assertThat(retry).isEqualTo(first);
        assertThat(persistence.read(command.uploadId())).isEqualTo(first);
        assertThat(first.uploadState()).isEqualTo("OPEN");
        assertThat(first.assetState()).isEqualTo("UPLOADING");
        assertThat(first.jobId()).isNull();
        assertThat(count("media_assets")).isEqualTo(1);
        assertThat(count("media_uploads")).isEqualTo(1);
        assertThatThrownBy(() -> persistence.ensure(new MediaPersistenceService.Ensure(command.uploadId(),
                command.requestId(), command.contentId(), command.bindingId(), command.assetId(),
                command.assetVersion(), 2048, command.sha256(), command.expiresAt())))
                .isInstanceOf(MediaPersistenceException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> jdbc.update("UPDATE media_uploads SET binding_id = ? WHERE id = ?",
                UUID.randomUUID(), command.uploadId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("media_assets")).isEqualTo(1);
    }

    @Test
    void jobReservationRollsBackAndConstraintsProtectReadyMetadataAndOutbox() {
        var command = command();
        persistence.ensure(command);
        assertThatThrownBy(() -> store.queue(command.uploadId(), command.assetId(), command.assetVersion(),
                UUID.randomUUID(), Instant.now())).isInstanceOf(IllegalTransactionStateException.class);
        var template = new TransactionTemplate(transactions);
        template.executeWithoutResult(status -> {
            store.queue(command.uploadId(), command.assetId(), command.assetVersion(), UUID.randomUUID(), Instant.now());
            status.setRollbackOnly();
        });
        assertThat(persistence.read(command.uploadId()).uploadState()).isEqualTo("OPEN");
        assertThat(count("media_jobs")).isZero();
        UUID jobId = UUID.randomUUID();
        template.executeWithoutResult(status ->
                store.queue(command.uploadId(), command.assetId(), command.assetVersion(), jobId, Instant.now()));
        assertThat(persistence.read(command.uploadId()).jobId()).isEqualTo(jobId);
        assertThat(persistence.read(command.uploadId()).assetState()).isEqualTo("QUEUED");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO media_jobs(id, upload_id, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), command.uploadId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE media_assets SET state = 'READY' WHERE asset_id = ? AND asset_version = 1",
                command.assetId())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE media_assets SET state = 'READY', selected_source_key = ?, output_prefix = ?,
                    master_manifest_key = ?, duration_seconds = 60, aggregate_version = 1
                WHERE asset_id = ? AND asset_version = 1
                """, "sources/" + command.assetId(), "hls/" + command.assetId(),
                "hls/" + command.assetId() + "/master.m3u8", command.assetId());
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_outbox_events(event_id, asset_id, asset_version, aggregate_version,
                    correlation_id, payload, next_attempt_at, created_at)
                VALUES (?, ?, 1, 1, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, event, command.assetId(), command.requestId(), "{\"state\":\"READY\"}");
        assertThat(count("media_outbox_events")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO media_outbox_events(event_id, asset_id, asset_version, aggregate_version,
                    correlation_id, payload, next_attempt_at, created_at)
                VALUES (?, ?, 1, 1, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), command.assetId(), command.requestId(), "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("media_outbox_events")).isEqualTo(1);
    }

    @Test
    void malformedAndExpiredEnsureCannotInsertRows() {
        var command = command();
        assertThatThrownBy(() -> persistence.ensure(new MediaPersistenceService.Ensure(command.uploadId(),
                command.requestId(), command.contentId(), command.bindingId(), command.assetId(),
                0, command.byteLength(), command.sha256(), command.expiresAt())))
                .isInstanceOf(MediaPersistenceException.class).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> persistence.ensure(new MediaPersistenceService.Ensure(command.uploadId(),
                command.requestId(), command.contentId(), command.bindingId(), command.assetId(),
                1, command.byteLength(), command.sha256(), Instant.now().minusSeconds(1))))
                .isInstanceOf(MediaPersistenceException.class).hasMessage("UPLOAD_EXPIRED");
        assertThat(count("media_uploads")).isZero();
        assertThat(count("media_assets")).isZero();
    }

    @Test
    void duplicateEnsureAndQueueRaceAcrossIndependentTransactions() throws Exception {
        var command = command();
        var ensured = race(() -> persistence.ensure(command));
        assertThat(ensured.get(0)).isEqualTo(ensured.get(1));
        assertThat(count("media_uploads")).isEqualTo(1);
        assertThat(count("media_assets")).isEqualTo(1);

        var template = new TransactionTemplate(transactions);
        var jobIds = race(() -> template.execute(status -> store.queue(command.uploadId(),
                command.assetId(), command.assetVersion(), UUID.randomUUID(), Instant.now())));
        assertThat(jobIds.get(0)).isEqualTo(jobIds.get(1));
        assertThat(count("media_jobs")).isEqualTo(1);
        assertThat(persistence.read(command.uploadId()).uploadState()).isEqualTo("SUBMITTED");
    }

    @Test
    void conflictsAreSanitizedAndDoNotLeaveAnOrphanAsset() {
        var first = command();
        persistence.ensure(first);
        var colliding = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(),
                first.contentId(), first.bindingId(), UUID.randomUUID(), 1, first.byteLength(),
                first.sha256(), first.expiresAt());
        assertThatThrownBy(() -> persistence.ensure(colliding))
                .isInstanceOf(MediaPersistenceException.class).hasMessage("UPLOAD_STATE_CONFLICT");
        assertThat(count("media_assets")).isEqualTo(1);
        assertThat(count("media_uploads")).isEqualTo(1);
    }

    @Test
    void staleTupleAndVersionCannotChangeStateOrLeaveUnpairedEvent() {
        var command = command();
        persistence.ensure(command);
        var template = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> template.execute(status -> store.queue(command.uploadId(),
                UUID.randomUUID(), command.assetVersion(), UUID.randomUUID(), Instant.now())))
                .isInstanceOf(MediaPersistenceException.class).hasMessage("UPLOAD_STATE_CONFLICT");
        assertThat(persistence.read(command.uploadId()).uploadState()).isEqualTo("OPEN");

        template.executeWithoutResult(status -> store.queue(command.uploadId(), command.assetId(),
                command.assetVersion(), UUID.randomUUID(), Instant.now()));
        assertThatThrownBy(() -> jdbc.update("UPDATE media_jobs SET stage = 'CLAIMED' WHERE upload_id = ?",
                command.uploadId())).isInstanceOf(DataIntegrityViolationException.class);
        UUID correlation = command.requestId();
        template.executeWithoutResult(status -> {
            assertThat(jdbc.update("""
                    UPDATE media_assets SET state = 'PROCESSING', aggregate_version = 1
                    WHERE asset_id = ? AND asset_version = 1 AND state = 'QUEUED' AND aggregate_version = 0
                    """, command.assetId())).isEqualTo(1);
            insertOutbox(command.assetId(), correlation, 1);
        });
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE media_outbox_events SET delivery_state = 'LEASED'
                WHERE asset_id = ? AND aggregate_version = 1
                """, command.assetId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("""
                UPDATE media_assets SET state = 'FAILED', aggregate_version = 2
                WHERE asset_id = ? AND asset_version = 1 AND state = 'QUEUED' AND aggregate_version = 0
                """, command.assetId())).isZero();
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE media_assets SET state = 'FAILED', aggregate_version = 2
                    WHERE asset_id = ? AND asset_version = 1 AND state = 'PROCESSING' AND aggregate_version = 1
                    """, command.assetId());
            insertOutbox(command.assetId(), correlation, 1);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                command.assetId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM media_assets WHERE asset_id = ?", Long.class,
                command.assetId())).isEqualTo(1);
        assertThat(count("media_outbox_events")).isEqualTo(1);
    }

    @Test
    void mediaVersionOneUpgradesToTwoAndRetainsRows() {
        Flyway before = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_media_m04").defaultSchema("upgrade_media_m04").target("1").load();
        before.migrate();
        UUID asset = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        UUID upload = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upgrade_media_m04.media_assets(asset_id, asset_version, content_id, binding_id,
                    aggregate_version, state, created_at, updated_at)
                VALUES (?, 1, ?, ?, 1, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, asset, content, binding);
        jdbc.update("""
                INSERT INTO upgrade_media_m04.media_uploads(id, request_id, content_id, binding_id,
                    asset_id, asset_version, byte_length, expected_sha256, request_fingerprint,
                    staging_key, state, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 1, 1024, ?, ?, ?, 'SUBMITTED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, upload, UUID.randomUUID(), content, binding, asset, "a".repeat(64), "b".repeat(64),
                "staging/" + upload + "/source.mp4");
        jdbc.update("""
                INSERT INTO upgrade_media_m04.media_jobs(id, upload_id, stage, attempt_count,
                    lease_token, lease_until, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, 'CLAIMED', 1, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, job, upload, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO upgrade_media_m04.media_outbox_events(event_id, asset_id, asset_version,
                    aggregate_version, correlation_id, payload, next_attempt_at, created_at)
                VALUES (?, ?, 1, 1, ?, '{"state":"PROCESSING"}'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), asset, UUID.randomUUID());
        Flyway after = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_media_m04").defaultSchema("upgrade_media_m04").load();
        after.migrate();
        after.migrate();
        after.validate();
        assertThat(jdbc.queryForObject("SELECT binding_id FROM upgrade_media_m04.media_assets WHERE asset_id = ?",
                UUID.class, asset)).isEqualTo(binding);
        assertThat(jdbc.queryForObject("SELECT id FROM upgrade_media_m04.media_uploads WHERE asset_id = ?",
                UUID.class, asset)).isEqualTo(upload);
        assertThat(jdbc.queryForObject("SELECT id FROM upgrade_media_m04.media_jobs WHERE upload_id = ?",
                UUID.class, upload)).isEqualTo(job);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_media_m04.media_outbox_events WHERE asset_id = ?",
                Integer.class, asset)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE upgrade_media_m04.media_assets
                SET master_manifest_key = 'elsewhere/master.m3u8', output_prefix = 'hls/expected'
                WHERE asset_id = ?
                """, asset)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertOutbox(UUID asset, UUID correlation, long version) {
        jdbc.update("""
                INSERT INTO media_outbox_events(event_id, asset_id, asset_version, aggregate_version,
                    correlation_id, payload, next_attempt_at, created_at)
                VALUES (?, ?, 1, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), asset, version, correlation, "{\"state\":\"PROCESSING\"}");
    }

    private <T> java.util.List<T> race(Callable<T> attempt) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> waiting = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
            return attempt.call();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(waiting);
            var second = pool.submit(waiting);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }

    private MediaPersistenceService.Ensure command() {
        return new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64),
                Instant.now().plus(Duration.ofMinutes(30)));
    }

    private int count(String table) {
        return switch (table) {
            case "media_assets", "media_uploads", "media_jobs", "media_outbox_events" ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            default -> throw new IllegalArgumentException();
        };
    }
}
