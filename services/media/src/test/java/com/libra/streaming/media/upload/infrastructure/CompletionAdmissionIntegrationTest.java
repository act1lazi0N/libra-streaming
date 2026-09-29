package com.libra.streaming.media.upload.infrastructure;

import com.libra.streaming.media.upload.application.UploadFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class CompletionAdmissionIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("completion_admission").withUsername("completion_admission")
            .withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MediaPersistenceService persistence;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    final AtomicReference<Instant> now = new AtomicReference<>();
    MediaPersistenceService.Ensure command;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE media_assets CASCADE");
        now.set(Instant.parse("2026-09-29T00:00:00Z"));
        when(clock.instant()).thenAnswer(ignored -> now.get());
        command = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), now.get().plusSeconds(10));
        persistence.ensure(command);
    }

    @Test void expiryIsCheckedAfterWaitingForTheDatabaseLock() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = new AtomicReference<java.util.concurrent.Future<MediaPersistenceService.Snapshot>>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM media_uploads WHERE id = ? FOR UPDATE", UUID.class,
                        command.uploadId());
                future.set(pool.submit(() -> persistence.queue(command.uploadId(), command.assetId(), 1)));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                                + "AND wait_event_type = 'Lock' AND query LIKE '%media_uploads%FOR UPDATE%'",
                        Integer.class)).isPositive());
                now.set(command.expiresAt());
            });
            assertThatThrownBy(() -> future.get().get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(UploadFailure.class).hasRootCauseMessage("UPLOAD_EXPIRED");
        }
        assertThat(persistence.read(command.uploadId()).uploadState()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).isZero();
    }

    @Test void aCommittedJobIsReturnedAfterTheOriginalExpiryWithoutNewAttempts() {
        var accepted = persistence.queue(command.uploadId(), command.assetId(), 1);
        now.set(command.expiresAt().plusSeconds(1));
        var repeated = persistence.queue(command.uploadId(), command.assetId(), 1);
        assertThat(repeated).isEqualTo(accepted);
        assertThat(repeated.attemptCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).isEqualTo(1);
    }
}
