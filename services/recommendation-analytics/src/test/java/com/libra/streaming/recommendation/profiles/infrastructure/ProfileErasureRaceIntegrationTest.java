package com.libra.streaming.recommendation.profiles.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import com.libra.streaming.recommendation.ingestion.infrastructure.JsonCoreEventDecoder;
import com.libra.streaming.recommendation.playback.application.PlaybackProjection;
import com.libra.streaming.recommendation.playback.application.PlaybackStore;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(properties = "libra.analytics.consumers.enabled=false")
@DirtiesContext
class ProfileErasureRaceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_erasure").withUsername("analytics_erasure")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PlaybackProjection playbackProjection;
    @Autowired ProfileProjection profiles;
    @Autowired PlaybackStore playback;
    @Autowired EventIntake intake;

    @Test void appendCommitsBeforeDeletionErasesEveryProfileLink() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) { race(false); }
    }

    @Test void deletionCommitsBeforeAppendStoresAnAnonymousCountedFact() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) { race(true); }
    }

    private void race(boolean deleteFirst) throws Exception {
        var fixtures = new IngestionFixtures();
        var event = fixtures.playback(1, 10_000, 10_000);
        var fact = (PlaybackProgress) new JsonCoreEventDecoder().decode(event.topic(), event.key(), event.json());
        UUID profile = fact.profileId();
        var deletion = fixtures.template(InputTopic.PROFILES, "profile-created");
        deletion.root().put("eventType", "ProfileDeleted").put("aggregateVersion", 2);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contenderStarted = new CountDownLatch(1);
        var blockerPid = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        Future<?> first = null;
        Future<?> second = null;
        try {
            first = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                if (deleteFirst) { profiles.apply(profile, fact.accountId(), 2, true, Instant.now()); }
                else { playbackProjection.apply(fact); }
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).as("first transaction holds profile lock").isTrue();
            second = pool.submit(() -> {
                contenderStarted.countDown();
                if (deleteFirst) {
                    new TransactionTemplate(manager).executeWithoutResult(status -> playbackProjection.apply(fact));
                } else {
                    // EventIntake owns its transaction; wrapping it would test a different commit boundary.
                    assertThat(intake.accept(deletion.topic(), deletion.key(), deletion.json()))
                            .isEqualTo(EventIntake.Outcome.APPLIED);
                }
            });
            assertThat(contenderStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> contender = second;
            assertThrows(TimeoutException.class, () -> contender.get(200, TimeUnit.MILLISECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                            AND wait_event = 'advisory' AND ? = ANY(pg_blocking_pids(pid)))
                        """, Boolean.class, blockerPid.get()));
                if (waiting) { break; }
                assertThat(contender.isDone()).as("contender must still be blocked").isFalse();
                Thread.yield();
            }
            assertThat(waiting).as("PostgreSQL observes advisory lock blocked by the held transaction").isTrue();
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM profile_watch_days WHERE profile_id = ?",
                    Integer.class, profile)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE profile_id = ?", Integer.class, profile)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_sessions WHERE profile_id = ?", Integer.class, profile)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ?", Integer.class, fact.sessionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ? AND profile_id IS NULL",
                    Integer.class, fact.sessionId())).isEqualTo(1);
            var total = playback.session(fact.sessionId()).orElseThrow();
            assertThat(total.profileId()).isNull();
            assertThat(total.factCount()).isEqualTo(1);
            assertThat(total.acceptedWatchedMs()).isEqualTo(10_000);
        } finally {
            release.countDown();
            held.countDown();
            contenderStarted.countDown();
            if (first != null) { first.cancel(true); }
            if (second != null) { second.cancel(true); }
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) { throw new AssertionError("Transaction release timed out"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Transaction interrupted", failure);
        }
    }
}
