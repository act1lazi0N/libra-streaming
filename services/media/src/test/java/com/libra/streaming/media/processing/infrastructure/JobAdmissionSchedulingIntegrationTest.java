package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class JobAdmissionSchedulingIntegrationTest {
    @Container static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")
                    .withDatabaseName("media_schedule_test").withUsername("media_schedule_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MediaPersistenceService uploads;
    @Autowired JobLeases leases;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;

    @Test
    void firstAdmissionIsDueOnDatabaseClockEvenIfApiClockRunsAhead() {
        Instant apiNow = Instant.now().plusSeconds(300);
        when(clock.instant()).thenReturn(apiNow);
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), apiNow.plusSeconds(1800));
        uploads.ensure(upload);
        var queued = uploads.queue(upload.uploadId(), upload.assetId(), 1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at <= clock_timestamp() FROM media_jobs WHERE id = ?",
                Boolean.class, queued.jobId())).isTrue();
        assertThat(leases.claim(Duration.ofSeconds(30))).isPresent();
    }
}
