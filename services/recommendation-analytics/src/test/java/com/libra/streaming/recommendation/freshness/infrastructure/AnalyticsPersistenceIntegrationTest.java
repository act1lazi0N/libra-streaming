package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL through Testcontainers: migration, checkpoint store, transactions and health. */
@Testcontainers
@SpringBootTest(properties = "libra.analytics.consumers.enabled=false")
@DirtiesContext
class AnalyticsPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_persistence").withUsername("analytics_persistence")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired CheckpointStore store;
    @Autowired ProjectionFreshness freshness;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;

    @BeforeEach
    void reset() { jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL"); }

    @Test
    void emptyDatabaseMigratesToExactlyTheVersionedSchema() {
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version || ':' || success FROM flyway_schema_history ORDER BY installed_rank",
                String.class)).containsExactly("1:true", "2:true", "3:true", "4:true", "5:true", "6:true", "7:true", "8:true");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "ORDER BY table_name", String.class)).containsExactly("bootstrap_progress", "catalog_nodes", "dead_letters", "flyway_schema_history", "input_checkpoints", "playback_facts", "playback_sessions", "processed_events", "profile_watch_days", "profiles", "statistics_account_days");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("analytics_persistence");
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL 18");
        assertThat(jdbc.queryForList("SELECT topic FROM input_checkpoints WHERE confirmed_at IS NULL ORDER BY topic",
                String.class)).containsExactly("core.catalog.v1", "core.playback.v1", "core.profiles.v1");
    }

    @Test
    void nothingIsReadyBeforeTheFirstConfirmedCheckpoint() {
        assertThat(store.confirmed()).isEmpty();
        assertThat(freshness.updatedAt(Projection.STATISTICS)).isEmpty();
        assertThat(freshness.updatedAt(Projection.RECOMMENDATIONS)).isEmpty();
    }

    @Test
    void checkpointsMoveForwardOnlyAndSurviveARoundTrip() {
        Instant first = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        assertThat(store.advance(InputTopic.PLAYBACK, first)).isTrue();
        assertThat(store.advance(InputTopic.PLAYBACK, first)).isFalse();
        assertThat(store.advance(InputTopic.PLAYBACK, first.minusSeconds(1))).isFalse();
        assertThat(store.confirmed()).containsOnlyKeys(InputTopic.PLAYBACK).containsEntry(InputTopic.PLAYBACK, first);
        // Sub-microsecond input is truncated, so it can never compare as newer than what was stored.
        assertThat(store.advance(InputTopic.PLAYBACK, first.plusNanos(999))).isFalse();
        assertThat(store.advance(InputTopic.PLAYBACK, first.plusNanos(1000))).isTrue();
        assertThat(freshness.updatedAt(Projection.STATISTICS)).contains(first.plusNanos(1000));
        assertThat(freshness.updatedAt(Projection.RECOMMENDATIONS)).isEmpty();
    }

    @Test
    void theSchemaRefusesAnUnknownTopic() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO input_checkpoints (topic) VALUES ('media.assets.v1')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO input_checkpoints (topic) VALUES ('core.playback.v1')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM input_checkpoints", Integer.class)).isEqualTo(3);
    }

    @Test
    void theStoreJoinsTheCallerTransaction() {
        var template = new TransactionTemplate(transactions);
        Instant at = Instant.now().minusSeconds(60);
        template.executeWithoutResult(status -> {
            assertThat(store.advance(InputTopic.CATALOG, at)).isTrue();
            assertThat(store.confirmed()).containsKey(InputTopic.CATALOG);
            status.setRollbackOnly();
        });
        assertThat(store.confirmed()).isEmpty();
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            store.advance(InputTopic.CATALOG, at);
            throw new IllegalStateException("fail after the write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.confirmed()).isEmpty();
        template.executeWithoutResult(status -> store.advance(InputTopic.CATALOG, at));
        assertThat(store.confirmed()).containsOnlyKeys(InputTopic.CATALOG);
    }

    @Test
    void concurrentAdvancesConvergeOnTheLatestInstant() throws Exception {
        Instant base = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        int writers = 16;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 1; i <= writers; i++) {
                Instant at = base.plusSeconds(i);
                tasks.add(() -> { start.await(); return store.advance(InputTopic.PROFILES, at); });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            int moved = 0;
            for (var future : futures) { if (future.get(30, TimeUnit.SECONDS)) { moved++; } }
            assertThat(moved).isBetween(1, writers);
        } finally { pool.shutdownNow(); }
        assertThat(store.confirmed()).containsEntry(InputTopic.PROFILES, base.plusSeconds(writers));
    }

    @Test
    void healthIsPublicWithoutDetailsAndEverythingElseIsDenied() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String probe : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            mvc.perform(get(probe)).andExpect(status().isOk()).andExpect(jsonPath("status").value("UP"))
                    .andExpect(jsonPath("components").doesNotExist()).andExpect(jsonPath("details").doesNotExist());
        }
        for (String denied : List.of("/", "/actuator", "/actuator/env", "/actuator/metrics", "/v1/statistics", "/error")) {
            mvc.perform(get(denied)).andExpect(status().is4xxClientError()).andExpect(content().string(""));
        }
        // Service authentication is disabled by default: the internal API accepts no credential at all.
        mvc.perform(post("/internal/v1/statistics/query")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/statistics/query").header("Authorization",
                "Bearer " + ServiceTokens.token("statistics:read"))).andExpect(status().isUnauthorized());
    }
}
