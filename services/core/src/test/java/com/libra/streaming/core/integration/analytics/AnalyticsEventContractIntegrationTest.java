package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.delivery.DeliveryStore;
import com.libra.streaming.core.playback.PlaybackService;
import com.libra.streaming.core.profiles.infrastructure.ProfileService;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static com.libra.streaming.core.playback.PlaybackModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * RA01.1 producer side of the Core-Analytics V1 contract: the Kafka records Core's outbox publisher would send for
 * catalog, profile and playback use cases equal the shared fixture templates exactly (after substituting identities
 * and timestamps). The fixtures themselves are schema-checked by {@code infra/ci/check_contracts.py}.
 */
@Testcontainers
@SpringBootTest
@Import(AnalyticsEventContractIntegrationTest.TimeConfiguration.class)
class AnalyticsEventContractIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_events_test").withUsername("analytics_events_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock eventContractClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        synchronized void reset() { now = Instant.now().truncatedTo(ChronoUnit.SECONDS); }
        synchronized void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public synchronized Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired MediaProjectionService media;
    @Autowired ProfileService profiles;
    @Autowired PlaybackService playback;
    @Autowired DeliveryStore delivery;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    List<AnalyticsContractFixtures.Fixture> fixtures;
    IdentityPrincipal viewer;
    IdentityPrincipal admin;
    final Set<String> checked = new HashSet<>();

    @BeforeEach void setup() {
        clock.reset();
        fixtures = AnalyticsContractFixtures.load(mapper);
        viewer = actor("USER"); admin = actor("ADMIN");
    }

    @Test void catalogAndProfileRecordsEqualTheContractFixtures() {
        // Every step runs at one clock instant, so every occurredAt must equal it.
        Instant at = clock.instant();
        var corr = new HashMap<String, UUID>();
        UUID kid = profiles.create(viewer, "Kid", correlation(corr, "profile-created")).id();
        long version = profiles.rename(viewer, kid, "Kid two", 1, correlation(corr, "profile-updated")).version();
        profiles.delete(viewer, kid, version, correlation(corr, "profile-deleted"));

        var movie = ready(create(Kind.MOVIE, null, null));
        var published = publish(movie, correlation(corr, "catalog-movie-published"));
        var unpublished = catalog.unpublish(admin, movie.id(), new Version(published.version()), correlation(corr, "catalog-movie-unpublished"));
        var failing = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        mediaState(failing.active(), 2, MediaState.FAILED, null, correlation(corr, "catalog-movie-availability-failed"));
        var series = publish(create(Kind.SERIES, null, null), correlation(corr, "catalog-series-published"));
        var season = publish(create(Kind.SEASON, series.id(), 1), UUID.randomUUID());
        var episode = publish(ready(create(Kind.EPISODE, season.id(), 1)), correlation(corr, "catalog-episode-published"));
        assertThat(unpublished.published()).isNull();

        var jobs = drain();
        var account = Map.of("accountId", viewer.accountId().toString(), "profileId", kid.toString());
        assertRecord(jobs, "profile-created", kid, "ProfileCreated", account, corr, at, null);
        assertRecord(jobs, "profile-updated", kid, "ProfileUpdated", account, corr, at, null);
        assertRecord(jobs, "profile-deleted", kid, "ProfileDeleted", account, corr, at, null);
        assertRecord(jobs, "catalog-movie-published", movie.id(), "CatalogPublished", content(published.active()), corr, at, null);
        assertRecord(jobs, "catalog-movie-unpublished", movie.id(), "CatalogUnpublished", Map.of("contentId", movie.id().toString()), corr, at, null);
        assertRecord(jobs, "catalog-movie-availability-failed", failing.id(), "CatalogAvailabilityChanged", content(failing.active()), corr, at, null);
        assertRecord(jobs, "catalog-series-published", series.id(), "CatalogPublished", Map.of("contentId", series.id().toString()), corr, at, null);
        var episodeValues = new HashMap<>(content(episode.active()));
        episodeValues.put("parentId", season.id().toString());
        assertRecord(jobs, "catalog-episode-published", episode.id(), "CatalogPublished", episodeValues, corr, at, null);
        assertThat(checked).containsExactlyInAnyOrderElementsOf(producers("catalog-", "profile-"));
        assertDistinctEventIds(jobs);
    }

    @Test void playbackRecordsEqualTheContractFixtures() {
        var movie = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        UUID profile = profiles.list(viewer).getFirst().id();
        UUID session = playback.admit(viewer, new Admission(profile, movie.id())).view().sessionId();
        var corr = new HashMap<String, UUID>();
        var at = new HashMap<Integer, Instant>();
        progress(session, 1, 0, 0, State.PLAYING, corr, at);             // from the initial PAUSED state: no time earned
        clock.advance(10);
        progress(session, 2, 10000, 10000, State.PLAYING, corr, at);
        clock.advance(25);
        progress(session, 3, 35000, 25000, State.PLAYING, corr, at);     // cumulative 35 s: the qualifying event
        clock.advance(5);
        progress(session, 4, 90000, 0, State.SEEKING, corr, at);         // position jumps, watched time does not

        var jobs = drain();
        var values = Map.of("accountId", viewer.accountId().toString(), "profileId", profile.toString(),
                "sessionId", session.toString(), "contentId", movie.id().toString(), "assetId", movie.active().assetId().toString());
        for (int sequence = 1; sequence <= 4; sequence++) {
            assertRecord(jobs, "playback-" + sequence, session, "PlaybackProgressAccepted", values, corr, at.get(sequence), sequence);
        }
        assertThat(checked).containsExactlyInAnyOrderElementsOf(producers("playback-"));
        assertDistinctEventIds(jobs);
    }

    /** Claims every pending row exactly as the outbox publisher does: same topic, key and serialized body. */
    private List<DeliveryStore.Job> drain() {
        clock.advance(3600); // rows become due by the database clock; the fixture clock may trail it by a second
        var jobs = new ArrayList<DeliveryStore.Job>();
        for (var job = delivery.claim(DeliveryStore.Kind.OUTBOX); job.isPresent(); job = delivery.claim(DeliveryStore.Kind.OUTBOX)) {
            assertThat(delivery.acknowledged(job.get())).isTrue();
            jobs.add(job.get());
        }
        return jobs;
    }

    /**
     * Identities, correlation and times are substituted from what the test itself supplied, never read back from the
     * record, so a dropped correlation ID or a wall-clock timestamp fails the comparison.
     */
    private void assertRecord(List<DeliveryStore.Job> jobs, String producer, UUID aggregate, String type,
            Map<String, String> known, Map<String, UUID> correlations, Instant occurredAt, Integer sequence) {
        var fixture = AnalyticsContractFixtures.byField(fixtures, "producer", producer);
        var matches = jobs.stream().filter(job -> job.key().equals(aggregate.toString()))
                .map(job -> Map.entry(job, json(job.body())))
                .filter(pair -> pair.getValue().path("eventType").asString().equals(type))
                .filter(pair -> sequence == null || pair.getValue().path("aggregateVersion").asLong() == sequence)
                .toList();
        assertThat(matches).as(producer).hasSize(1);
        var job = matches.getFirst().getKey();
        JsonNode actual = matches.getFirst().getValue();
        assertThat(job.topic()).as(producer).isEqualTo(fixture.text("topic"));
        var values = new HashMap<>(known);
        // eventId is the only value Core invents; it must be a canonical UUID (distinctness is checked per test).
        String eventId = actual.path("eventId").asString();
        assertThat(UUID.fromString(eventId).toString()).as(producer).isEqualTo(eventId);
        values.put("eventId", eventId);
        values.put("correlationId", correlations.get(producer).toString());
        values.put("occurredAt", text(actual.path("occurredAt"), occurredAt, producer));
        var createdAt = actual.path("payload").path("published").path("createdAt");
        // The revision timestamp is PostgreSQL's CURRENT_TIMESTAMP, not Core's Clock: only its form is contractual.
        if (!createdAt.isMissingNode() && !createdAt.isNull()) { values.put("createdAt", text(createdAt, null, producer)); }
        assertThat(actual).as(producer).isEqualTo(json(fixture.instantiate(values)));
        checked.add(producer);
    }

    /** The record's timestamp text, after proving it is the expected instant written as an RFC 3339 UTC string. */
    private static String text(JsonNode node, Instant expected, String producer) {
        assertThat(node.isString()).as(producer).isTrue();
        var parsed = OffsetDateTime.parse(node.asString());
        assertThat(parsed.getOffset()).as(producer).isEqualTo(ZoneOffset.UTC);
        if (expected != null) { assertThat(parsed.toInstant()).as(producer).isEqualTo(expected); }
        return node.asString();
    }

    private void assertDistinctEventIds(List<DeliveryStore.Job> jobs) {
        var ids = jobs.stream().map(job -> json(job.body()).path("eventId").asString()).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    private static UUID correlation(Map<String, UUID> correlations, String producer) {
        UUID id = UUID.randomUUID();
        correlations.put(producer, id);
        return id;
    }

    private List<String> producers(String... prefixes) {
        return AnalyticsContractFixtures.with(fixtures, "producer").stream().map(fixture -> fixture.text("producer"))
                .filter(name -> Arrays.stream(prefixes).anyMatch(name::startsWith)).toList();
    }
    private Map<String, String> content(Binding binding) {
        return Map.of("contentId", binding.contentId().toString(), "bindingId", binding.id().toString(),
                "assetId", binding.assetId().toString());
    }
    private JsonNode json(String body) { return mapper.readTree(body); }
    private void progress(UUID session, long sequence, long position, long played, State state,
            Map<String, UUID> correlations, Map<Integer, Instant> times) {
        times.put((int) sequence, clock.instant());
        var id = correlation(correlations, "playback-" + sequence);
        assertThat(playback.progress(viewer, session, new Progress(sequence, position, played, state), id).accepted()).isTrue();
    }
    private IdentityPrincipal actor(String role) {
        UUID id = UUID.randomUUID(); UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role, email_verified) VALUES (?, ?, 'Viewer', 'inert-fixture', ?, true)",
                id, id + "@example.test", role);
        new TransactionTemplate(transactions).executeWithoutResult(status -> profiles.initialize(id, "Viewer"));
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)", sid,
                id, Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().plusSeconds(7200)));
        return new IdentityPrincipal(id, sid, role, true);
    }
    private AdminView create(Kind kind, UUID parent, Integer ordinal) {
        return catalog.create(admin, new Create(kind, parent, ordinal,
                new Metadata("Title", "Description", List.of("drama"), 2026, "en", List.of(), List.of(), Tier.FREE)));
    }
    private AdminView publish(AdminView content, UUID correlationId) {
        return catalog.publish(admin, content.id(), new Version(content.version()), correlationId);
    }
    private AdminView ready(AdminView content) {
        content = catalog.bind(admin, content.id(), new Bind(content.version(), UUID.randomUUID(), 1));
        mediaState(content.candidate(), 1, MediaState.READY, 120, UUID.randomUUID());
        return catalog.get(admin, content.id());
    }
    private void mediaState(Binding binding, long version, MediaState state, Integer durationSeconds, UUID correlationId) {
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), version, clock.instant(), correlationId, mapper.valueToTree(new MediaProjectionService.Change(
                        binding.contentId(), binding.id(), binding.assetId(), binding.assetVersion(), state, durationSeconds))));
    }
}
