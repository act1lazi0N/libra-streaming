package com.libra.streaming.recommendation.convergence;

import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {"libra.analytics.consumers.enabled=false", "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"})
class OrderIndependenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_order").withUsername("analytics_order").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired EventIntake intake;
    @Autowired JdbcTemplate jdbc;
    CanonicalEvents canonical;
    ProjectionDump reference;

    @BeforeEach void reference() {
        canonical = CanonicalEvents.create();
        clear(); canonical.ordered.forEach(this::apply);
        ProjectionDump.assertTotals(jdbc, canonical);
        reference = ProjectionDump.read(jdbc);
        clear();
    }

    @Test void fortySeededPermutationsConvergeIncludingNamedInversions() {
        for (int run = 0; run < 40; run++) {
            long seed = CanonicalEvents.SEED + run;
            clear();
            var stream = canonical.shuffled(seed);
            if (run == 0) {
                // Move every delivery of each older fact to the end, guaranteeing all three named inversions.
                var older = List.of(find(canonical.movie, 2), find(CanonicalEvents.id("session-0"), 1),
                        find(CanonicalEvents.id("P3"), 1));
                var tail = stream.stream().filter(older::contains).toList();
                stream.removeAll(older); stream.addAll(tail);
                assertThat(stream.indexOf(find(canonical.movie, 6))).isLessThan(stream.indexOf(older.get(0)));
                assertThat(stream.indexOf(find(CanonicalEvents.id("session-0"), 5))).isLessThan(stream.indexOf(older.get(1)));
                assertThat(stream.indexOf(find(CanonicalEvents.id("P3"), 3))).isLessThan(stream.indexOf(older.get(2)));
            }
            stream.forEach(this::apply);
            reference.assertMatches(ProjectionDump.read(jdbc), "permutation seed=" + seed);
            ProjectionDump.assertTotals(jdbc, canonical);
        }
    }

    @Test void playbackFactDeliveredLastStillAddsItsIncrement() {
        var late = find(CanonicalEvents.id("session-0"), 2);
        canonical.ordered.stream().filter(event -> !event.equals(late)).forEach(this::apply);
        assertThat(jdbc.queryForObject("SELECT accepted_watched_ms FROM playback_sessions WHERE session_id = ?",
                Long.class, UUID.fromString(late.key()))).isEqualTo(canonical.accepted.get(UUID.fromString(late.key())) - 10000);
        apply(late);
        ProjectionDump.assertTotals(jdbc, canonical);
        reference.assertMatches(ProjectionDump.read(jdbc), "late additive fact");
    }

    @Test void latePublicationOnlySuppliesMissingFactAndCannotReplaceNewestState() {
        var late = find(canonical.movie, 2);
        canonical.ordered.stream().filter(event -> !event.equals(late)).forEach(this::apply);
        var before = jdbc.queryForMap("SELECT version, active_state, published_at FROM catalog_nodes WHERE content_id = ?", canonical.movie);
        apply(late);
        var after = jdbc.queryForMap("SELECT version, active_state, published_at FROM catalog_nodes WHERE content_id = ?", canonical.movie);
        assertThat(after).isEqualTo(before);
        assertThat(after.get("version")).isEqualTo(6L);
        assertThat(after.get("active_state")).isEqualTo("READY");
        assertThat(((java.sql.Timestamp) after.get("published_at")).toInstant()).isEqualTo(CanonicalEvents.BASE.plusSeconds(15));
        reference.assertMatches(ProjectionDump.read(jdbc), "late v2 publication");
    }

    @Test void invalidFixturesInterleavedWithValidEventsChangeNothing() {
        var invalid = CanonicalEvents.invalid();
        var stream = canonical.shuffled(CanonicalEvents.SEED + 40);
        for (int i = 0; i < stream.size(); i++) {
            apply(stream.get(i));
            if (i < invalid.size()) {
                var event = invalid.get(i);
                var before = ProjectionDump.read(jdbc);
                EventRejection.Reason reason = i < 3 ? EventRejection.Reason.SCHEMA_INVALID : EventRejection.Reason.SEMANTIC_INVALID;
                assertThatThrownBy(() -> intake.accept(event.topic(), event.key(), event.json()))
                        .isInstanceOfSatisfying(EventRejection.class, rejection -> assertThat(rejection.reason()).isEqualTo(reason));
                before.assertMatches(ProjectionDump.read(jdbc), "rejection must be inert, fixture=" + i);
            }
        }
        reference.assertMatches(ProjectionDump.read(jdbc), "valid and invalid deliveries interleaved");
    }

    private CanonicalEvents.Delivery find(UUID aggregate, long version) {
        return canonical.ordered.stream().filter(event -> event.key().equals(aggregate.toString())
                && IngestionFixtures.JSON.readTree(event.json()).path("aggregateVersion").longValue() == version).findFirst().orElseThrow();
    }
    private void apply(CanonicalEvents.Delivery event) { intake.accept(event.topic(), event.key(), event.json()); }
    private void clear() { jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, catalog_nodes, profiles, playback_facts, playback_sessions, processed_events"); }
}
