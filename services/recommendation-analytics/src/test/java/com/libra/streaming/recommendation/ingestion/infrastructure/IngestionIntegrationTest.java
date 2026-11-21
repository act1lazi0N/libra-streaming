package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.catalog.application.CatalogStore;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.playback.application.PlaybackStore;
import com.libra.streaming.recommendation.profiles.application.ProfileStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures.*;
import static com.libra.streaming.recommendation.ingestion.application.EventIntake.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real PostgreSQL, receipts and projections through EventIntake; Kafka is not involved or mocked. */
@Testcontainers
@SpringBootTest(properties = "libra.analytics.consumers.enabled=false")
@DirtiesContext
class IngestionIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_ingestion").withUsername("analytics_ingestion").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired EventIntake intake;
    @Autowired CatalogStore catalog;
    @Autowired ProfileStore profiles;
    @Autowired PlaybackStore playback;
    @Autowired JdbcTemplate jdbc;
    IngestionFixtures f;
    @BeforeEach void fixtures() { f = new IngestionFixtures(); }
    EventIntake.Outcome accept(Event event) { return intake.accept(event.topic(), event.key(), event.json()); }
    void conflict(Event event) {
        assertThat(assertThrows(EventRejection.class, () -> accept(event)).reason()).isEqualTo(EventRejection.Reason.EVENT_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, event.id())).isZero();
    }

    @Test void deadLettersDeduplicateSourceAcrossDifferentDltPositions() {
        var letters = new JdbcDeadLetters(jdbc, java.time.Clock.systemUTC());
        long offset = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE - 1);
        String key = UUID.randomUUID().toString();
        letters.capture(new com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Captured(
                0, offset, "core.playback.v1", 0, offset, key, "{}", "SEMANTIC_INVALID", "HELD"));
        letters.capture(new com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Captured(
                1, offset + 1, "core.playback.v1", 0, offset, key, "{}", "SEMANTIC_INVALID", "HELD"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test void deadLettersWithoutSourceTopicAreNotDeduplicated() {
        var letters = new JdbcDeadLetters(jdbc, java.time.Clock.systemUTC());
        long offset = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE - 1);
        String key = UUID.randomUUID().toString();
        for (int partition = 0; partition < 2; partition++) {
            letters.capture(new com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Captured(
                    partition, offset, null, 0, offset, key, "{}", "ORIGIN_UNAVAILABLE", "UNREDRIVABLE"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE event_key = ?", Integer.class, key)).isEqualTo(2);
    }

    @Test void catalogLatestVersionWinsAndEqualVersionIsANoOp() {
        accept(f.movie(5, "CatalogPublished")); var stored = catalog.find(f.content).orElseThrow();
        accept(f.movie(3, "CatalogPublished"));
        var equal = f.movie(5, "CatalogPublished");
        ((tools.jackson.databind.node.ObjectNode) equal.payload().path("published").path("metadata")).put("title", "Ignored");
        accept(equal); assertThat(catalog.find(f.content)).contains(stored);
    }
    @Test void catalogKindAndParentConflictsLeaveTheRowUnchanged() {
        accept(f.movie(5, "CatalogPublished")); var stored = catalog.find(f.content).orElseThrow();
        conflict(f.catalog(f.content, "SERIES", null, 6, "CatalogPublished", BASE));
        assertThat(catalog.find(f.content)).contains(stored);
        UUID episode = UUID.randomUUID(), season = UUID.randomUUID();
        accept(f.catalog(episode, "EPISODE", season, 1, "CatalogPublished", BASE));
        var child = catalog.find(episode).orElseThrow();
        conflict(f.catalog(episode, "EPISODE", UUID.randomUUID(), 2, "CatalogPublished", BASE));
        assertThat(catalog.find(episode)).contains(child);
    }
    @Test void availabilityPreservesPublicationTimeAndUnpublicationClearsIt() {
        accept(f.movie(2, "CatalogPublished"));
        accept(f.movie(3, "CatalogAvailabilityChanged"));
        assertThat(catalog.find(f.content).orElseThrow().publishedAt()).isEqualTo(BASE.plusSeconds(2));
        accept(f.movie(4, "CatalogUnpublished"));
        var node = catalog.find(f.content).orElseThrow();
        assertThat(node.publishedAt()).isNull(); assertThat(node.published()).isNull(); assertThat(node.active()).isNull();
        assertThat(jdbc.queryForObject("SELECT published_at_version FROM catalog_nodes WHERE content_id = ?", Long.class, f.content)).isNull();
    }
    @Test void childPlacementResolvesLateAncestorsAndTheirVisibility() {
        UUID season = UUID.randomUUID(), series = UUID.randomUUID();
        accept(f.catalog(f.content, "EPISODE", season, 1, "CatalogPublished", BASE));
        var incomplete = catalog.placement(f.content).orElseThrow();
        assertThat(incomplete.complete()).isFalse(); assertThat(incomplete.visible()).isFalse();
        accept(f.catalog(season, "SEASON", series, 1, "CatalogPublished", BASE));
        assertThat(catalog.placement(f.content).orElseThrow().complete()).isFalse();
        accept(f.catalog(series, "SERIES", null, 1, "CatalogPublished", BASE));
        var complete = catalog.placement(f.content).orElseThrow();
        assertThat(complete.complete()).isTrue(); assertThat(complete.visible()).isTrue(); assertThat(complete.rootId()).isEqualTo(series);
        accept(f.catalog(series, "SERIES", null, 2, "CatalogUnpublished", BASE));
        assertThat(catalog.placement(f.content).orElseThrow().visible()).isFalse();
    }
    @Test void latePublicationFillsTimeWithoutReplacingNewerAvailabilityState() {
        var availability = f.movie(3, "CatalogAvailabilityChanged");
        ((tools.jackson.databind.node.ObjectNode) availability.payload().path("active")).put("state", "FAILED");
        accept(availability); var state = catalog.find(f.content).orElseThrow();
        assertThat(state.publishedAt()).isNull();
        accept(f.movie(2, "CatalogPublished")); var node = catalog.find(f.content).orElseThrow();
        assertThat(node.version()).isEqualTo(3); assertThat(node.published()).isEqualTo(state.published());
        assertThat(node.active()).isEqualTo(state.active()); assertThat(node.publishedAt()).isEqualTo(BASE.plusSeconds(2));
    }
    @Test void latePublicationsConvergeOnHighestPublicationVersionInBothOrders() {
        for (List<Integer> order : List.of(List.of(5, 2, 4), List.of(5, 4, 2))) {
            UUID content = UUID.randomUUID();
            for (int version : order) { accept(f.catalog(content, "MOVIE", null, version,
                    version == 5 ? "CatalogAvailabilityChanged" : "CatalogPublished", BASE.plusSeconds(version))); }
            var node = catalog.find(content).orElseThrow();
            assertThat(node.version()).isEqualTo(5); assertThat(node.publishedAt()).isEqualTo(BASE.plusSeconds(4));
            assertThat(jdbc.queryForObject("SELECT published_at_version FROM catalog_nodes WHERE content_id = ?", Long.class, content)).isEqualTo(4);
        }
    }
    @Test void latePublicationDoesNotTouchUnpublishedState() {
        accept(f.movie(5, "CatalogUnpublished")); var stored = catalog.find(f.content).orElseThrow();
        accept(f.movie(4, "CatalogPublished")); assertThat(catalog.find(f.content)).contains(stored);
    }
    @Test void profileDeletionIsTerminalEvenForHigherVersionLiveEvents() {
        accept(f.profile(1, "ProfileCreated")); accept(f.profile(2, "ProfileUpdated"));
        assertThat(profiles.find(f.profile).orElseThrow().version()).isEqualTo(2);
        accept(f.profile(3, "ProfileDeleted")); var tombstone = profiles.find(f.profile).orElseThrow();
        accept(f.profile(4, "ProfileCreated")); accept(f.profile(5, "ProfileUpdated"));
        assertThat(tombstone.deleted()).isTrue(); assertThat(profiles.find(f.profile)).contains(tombstone);
    }
    @Test void unknownProfileDeletionBlocksLaterCreation() {
        accept(f.profile(2, "ProfileDeleted")); accept(f.profile(3, "ProfileCreated"));
        assertThat(profiles.find(f.profile).orElseThrow().deleted()).isTrue();
        assertThat(profiles.find(f.profile).orElseThrow().version()).isEqualTo(2);
    }
    @Test void profileAccountChangeIsAConflict() {
        accept(f.profile(1, "ProfileCreated")); var stored = profiles.find(f.profile).orElseThrow();
        var changed = f.profile(2, "ProfileUpdated"); changed.payload().put("accountId", UUID.randomUUID().toString());
        conflict(changed); assertThat(profiles.find(f.profile)).contains(stored);
    }
    @Test void receiptsDeduplicateAndRejectReusedEventIdentity() {
        var event = f.movie(5, "CatalogPublished"); assertThat(accept(event)).isEqualTo(APPLIED);
        var stored = catalog.find(f.content).orElseThrow(); assertThat(accept(event)).isEqualTo(DUPLICATE);
        var changed = event.copy(); changed.root().put("aggregateVersion", 6);
        assertThat(assertThrows(EventRejection.class, () -> accept(changed)).reason()).isEqualTo(EventRejection.Reason.EVENT_CONFLICT);
        assertThat(catalog.find(f.content)).contains(stored);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE aggregate_id = ?", Integer.class, f.content)).isEqualTo(1);
    }
    @Test void reversePlaybackReplaysPreserveCanonicalTimeAndCompleteness() {
        var events = List.of(f.playback(1, 0, 0), f.playback(2, 10000, 10000), f.playback(3, 25000, 35000), f.playback(4, 0, 35000));
        for (int repeat = 0; repeat < 2; repeat++) {
            for (Event event : events.reversed()) { assertThat(accept(event)).isEqualTo(repeat == 0 ? APPLIED : DUPLICATE); }
        }
        var total = playback.session(f.session).orElseThrow();
        assertThat(total.acceptedWatchedMs()).isEqualTo(35000); assertThat(total.maxTotalWatchedMs()).isEqualTo(35000);
        assertThat(total.maxSequence()).isEqualTo(4); assertThat(total.factCount()).isEqualTo(4); assertThat(total.complete()).isTrue();
    }
    @Test void earliestQualifyingSequenceWinsEvenWhenDeliveredLast() {
        accept(f.playback(4, 0, 35000)); assertThat(playback.session(f.session).orElseThrow().qualifiedSequence()).isEqualTo(4);
        accept(f.playback(3, 25000, 35000)); var total = playback.session(f.session).orElseThrow();
        assertThat(total.qualifiedSequence()).isEqualTo(3); assertThat(total.qualifiedAt()).isEqualTo(BASE.plusSeconds(3));
    }
    @Test void occupiedPlaybackSequenceRollsBackFactAndReceipt() {
        var event = f.playback(1, 10000, 10000); accept(event);
        var stored = playback.session(f.session).orElseThrow(); conflict(event.fresh());
        assertThat(playback.session(f.session)).contains(stored);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ?", Integer.class, f.session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE aggregate_id = ?", Integer.class, f.session)).isEqualTo(1);
    }
    @Test void playbackSessionAccountAndContentChangesRollBack() {
        accept(f.playback(1, 10000, 10000)); var stored = playback.session(f.session).orElseThrow();
        for (String field : List.of("accountId", "contentId")) {
            var changed = f.playback(2, 10000, 20000); changed.payload().put(field, UUID.randomUUID().toString()); conflict(changed);
            assertThat(playback.session(f.session)).contains(stored);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ?", Integer.class, f.session)).isEqualTo(1);
    }
    @Test void unknownProfileAndContentDoNotBlockFactsAndPlacementResolvesLater() {
        accept(f.playback(1, 10000, 10000)); var stored = playback.session(f.session).orElseThrow();
        assertThat(profiles.find(f.profile)).isEmpty(); assertThat(catalog.placement(f.content)).isEmpty();
        var facts = jdbc.queryForList("SELECT * FROM playback_facts WHERE session_id = ?", f.session);
        accept(f.movie(1, "CatalogPublished")); assertThat(catalog.placement(f.content).orElseThrow().visible()).isTrue();
        assertThat(playback.session(f.session)).contains(stored);
        assertThat(jdbc.queryForList("SELECT * FROM playback_facts WHERE session_id = ?", f.session)).isEqualTo(facts);
    }
    @Test void profileDeletionErasesLinksButPreservesOldAndNewWatchedTime() {
        accept(f.profile(1, "ProfileCreated")); accept(f.playback(1, 10000, 10000)); accept(f.profile(2, "ProfileDeleted"));
        assertThat(playback.session(f.session).orElseThrow().profileId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ? AND profile_id IS NOT NULL", Integer.class, f.session)).isZero();
        accept(f.playback(2, 10000, 20000)); var total = playback.session(f.session).orElseThrow();
        assertThat(total.profileId()).isNull(); assertThat(total.acceptedWatchedMs()).isEqualTo(20000); assertThat(total.factCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ? AND profile_id IS NULL", Integer.class, f.session)).isEqualTo(2);
    }
    @Test void concurrentDuplicatePlaybackIsAppliedExactlyOnce() throws Exception {
        var event = f.playback(1, 10000, 10000); var outcomes = concurrently(i -> event);
        assertThat(outcomes.stream().filter(APPLIED::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(DUPLICATE::equals).count()).isEqualTo(7);
        assertThat(playback.session(f.session).orElseThrow().acceptedWatchedMs()).isEqualTo(10000);
        assertThat(playback.session(f.session).orElseThrow().factCount()).isEqualTo(1);
    }
    @Test void concurrentCatalogVersionsConvergeOnVersionEight() throws Exception {
        var versions = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8)); Collections.shuffle(versions);
        var events = versions.stream().map(v -> f.movie(v, "CatalogPublished")).toList();
        concurrently(events::get); assertThat(catalog.find(f.content).orElseThrow().version()).isEqualTo(8);
    }
    private List<EventIntake.Outcome> concurrently(IntFunction<Event> events) throws Exception {
        var ready = new CountDownLatch(8); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<EventIntake.Outcome>>();
            for (int i = 0; i < 8; i++) {
                Event event = events.apply(i);
                futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timed out"); } return accept(event); }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var results = new ArrayList<EventIntake.Outcome>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        }
    }
}
