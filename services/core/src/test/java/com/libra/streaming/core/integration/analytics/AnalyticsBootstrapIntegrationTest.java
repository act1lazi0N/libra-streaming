package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.delivery.DeliveryStore;
import com.libra.streaming.core.playback.PlaybackService;
import com.libra.streaming.core.profiles.infrastructure.ProfileService;
import java.sql.Timestamp;
import java.net.URI;
import java.net.http.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
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

/** Real PostgreSQL and HTTP bootstrap checks; media readiness is supplied as an event, Kafka is not exercised. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AnalyticsBootstrapIntegrationTest.TimeConfiguration.class)
class AnalyticsBootstrapIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_events_test").withUsername("analytics_events_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("libra.services.analytics.enabled", () -> true);
        registry.add("libra.services.analytics.public-key", () -> AnalyticsTestKeys.publicKey(BOOTSTRAP));
        registry.add("libra.services.analytics.key-id", () -> "bootstrap");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock bootstrapClock() { return new MutableClock(); }
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
    static final java.security.KeyPair BOOTSTRAP = AnalyticsTestKeys.pair();
    @LocalServerPort int port;
    HttpClient http;
    IdentityPrincipal viewer;
    IdentityPrincipal admin;


    @BeforeEach void setup() {
        clock.reset();
        http = HttpClient.newHttpClient();
        viewer = actor("USER"); admin = actor("ADMIN");
    }

    @AfterEach void closeHttp() { http.close(); }

    @Test void catalogPagesMatchCurrentVersionsAndLatestEvents() throws Exception {
        var movie = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        var series = publish(create(Kind.SERIES, null, null), UUID.randomUUID());
        var season = publish(create(Kind.SEASON, series.id(), 1), UUID.randomUUID());
        var episode = publish(ready(create(Kind.EPISODE, season.id(), 1)), UUID.randomUUID());
        var hidden = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        catalog.unpublish(admin, hidden.id(), new Version(hidden.version()), UUID.randomUUID());
        var draft = create(Kind.MOVIE, null, null);
        var failed = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        mediaState(failed.active(), 2, MediaState.FAILED, null, UUID.randomUUID());
        var items = pages("snapshots/catalog", 2, "items");
        var expectedIds = jdbc.queryForList("SELECT id::text FROM catalog_contents ORDER BY id", String.class);
        assertThat(items).extracting(n -> n.path("contentId").asString()).containsExactlyElementsOf(expectedIds);
        assertThat(expectedIds).contains(movie.id().toString(), series.id().toString(), season.id().toString(),
                episode.id().toString(), hidden.id().toString(), draft.id().toString(), failed.id().toString());
        for (JsonNode item : items) {
            fields(item, "contentId", "version", "kind", "parentId", "ordinal", "published", "active", "publishedAt");
            UUID id = uuid(item.path("contentId"));
            integer(item.path("version"));
            assertThat(item.path("kind").isString()).isTrue();
            assertThat(List.of("MOVIE", "SERIES", "SEASON", "EPISODE")).contains(item.path("kind").asString());
            if (!item.path("parentId").isNull()) { uuid(item.path("parentId")); }
            if (!item.path("ordinal").isNull()) { integer(item.path("ordinal")); }
            var row = jdbc.queryForMap("SELECT version, published_revision, published_at FROM catalog_contents WHERE id = ?", id);
            assertThat(item.path("version").asLong()).isEqualTo(((Number) row.get("version")).longValue());
            if (row.get("published_revision") == null) {
                for (String field : List.of("published", "active", "publishedAt")) { assertThat(item.path(field).isNull()).isTrue(); }
            } else {
                var payload = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE topic = 'core.catalog.v1' AND aggregate_id = ? ORDER BY aggregate_version DESC LIMIT 1", String.class, id));
                assertThat(item.path("published")).isEqualTo(payload.path("published"));
                assertThat(item.path("active")).isEqualTo(payload.path("active"));
                assertThat(item.path("published").isObject()).isTrue();
                assertThat(item.path("active").isNull() || item.path("active").isObject()).isTrue();
                assertThat(item.path("publishedAt").isString()).isTrue();
                assertThat(Instant.parse(item.path("publishedAt").asString())).isEqualTo(((Timestamp) row.get("published_at")).toInstant());
            }
        }
    }

    @Test void profilePagesIncludeCurrentLiveVersionsAndDeletionTombstones() throws Exception {
        var live = profiles.create(viewer, "Live", UUID.randomUUID());
        profiles.rename(viewer, live.id(), "Renamed", live.version(), UUID.randomUUID());
        var deleted = profiles.create(viewer, "Deleted", UUID.randomUUID());
        long version = profiles.rename(viewer, deleted.id(), "Before deletion", deleted.version(), UUID.randomUUID()).version();
        profiles.delete(viewer, deleted.id(), version, UUID.randomUUID());
        var expected = jdbc.queryForList("""
                SELECT id::text AS id, account_id::text AS account, version, false AS deleted FROM profiles
                UNION ALL SELECT aggregate_id::text, payload ->> 'accountId', aggregate_version, true
                FROM outbox_events WHERE topic = 'core.profiles.v1' AND event_type = 'ProfileDeleted'
                ORDER BY id
                """);
        var items = pages("snapshots/profiles", 1, "items");
        assertThat(items).hasSize(expected.size());
        assertThat(items).extracting(n -> n.path("profileId").asString()).doesNotHaveDuplicates();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i); var row = expected.get(i);
            fields(item, "profileId", "accountId", "version", "deleted");
            uuid(item.path("profileId")); uuid(item.path("accountId")); integer(item.path("version"));
            assertThat(item.path("deleted").isBoolean()).isTrue();
            assertThat(item.path("profileId").asString()).isEqualTo(row.get("id"));
            assertThat(item.path("accountId").asString()).isEqualTo(row.get("account"));
            assertThat(item.path("version").asLong()).isEqualTo(((Number) row.get("version")).longValue());
            assertThat(item.path("deleted").asBoolean()).isEqualTo(row.get("deleted"));
        }
    }

    @Test void playbackPagesPreserveEveryPublisherBodyInInsertOrder() throws Exception {
        var movie = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        UUID profile = profiles.list(viewer).getFirst().id();
        for (int i = 0; i < 3; i++) {
            UUID session = playback.admit(viewer, new Admission(profile, movie.id())).view().sessionId();
            assertThat(playback.progress(viewer, session, new Progress(1, 0, 0, State.PLAYING), UUID.randomUUID()).accepted()).isTrue();
            clock.advance(10);
            assertThat(playback.progress(viewer, session, new Progress(2, 10000, 10000, State.PLAYING), UUID.randomUUID()).accepted()).isTrue();
        }
        var events = pages("events/playback", 2, "events");
        var orderedIds = jdbc.queryForList("SELECT event_id::text FROM outbox_events WHERE topic = 'core.playback.v1' ORDER BY created_at, event_id", String.class);
        assertThat(events).extracting(n -> n.path("eventId").asString()).containsExactlyElementsOf(orderedIds).doesNotHaveDuplicates();
        // Claim uses the real publisher's serializer and real PostgreSQL leases; no Kafka broker is involved.
        clock.advance(3600);
        var published = new HashMap<String, JsonNode>();
        for (var job = delivery.claim(DeliveryStore.Kind.OUTBOX); job.isPresent(); job = delivery.claim(DeliveryStore.Kind.OUTBOX)) {
            var value = job.get();
            if (value.topic().equals("core.playback.v1")) { published.put(value.id().toString(), mapper.readTree(value.body())); }
            assertThat(delivery.acknowledged(value)).isTrue();
        }
        assertThat(published.keySet()).containsExactlyInAnyOrderElementsOf(orderedIds);
        for (JsonNode event : events) {
            fields(event, "eventId", "eventType", "schemaVersion", "aggregateId", "aggregateVersion", "occurredAt", "correlationId", "payload");
            uuid(event.path("eventId")); uuid(event.path("aggregateId")); uuid(event.path("correlationId"));
            integer(event.path("schemaVersion")); integer(event.path("aggregateVersion"));
            assertThat(event.path("eventType").isString()).isTrue();
            assertThat(event.path("occurredAt").isString()).isTrue(); Instant.parse(event.path("occurredAt").asString());
            var payload = event.path("payload");
            fields(payload, "accountId", "profileId", "sessionId", "contentId", "assetId", "assetVersion", "sequence",
                    "positionMs", "acceptedWatchedMs", "totalWatchedMs", "qualified", "state");
            for (String field : List.of("accountId", "profileId", "sessionId", "contentId", "assetId")) { uuid(payload.path(field)); }
            integer(payload.path("assetVersion")); integer(payload.path("sequence"));
            for (String field : List.of("positionMs", "acceptedWatchedMs", "totalWatchedMs")) {
                assertThat(payload.path(field).isIntegralNumber()).isTrue(); assertThat(payload.path(field).asLong()).isNotNegative();
            }
            assertThat(payload.path("positionMs").asLong()).isLessThanOrEqualTo(604800000L);
            assertThat(payload.path("acceptedWatchedMs").asLong()).isLessThanOrEqualTo(30000L);
            assertThat(payload.path("qualified").isBoolean()).isTrue();
            assertThat(payload.path("state").isString()).isTrue();
            assertThat(List.of("PLAYING", "PAUSED", "BUFFERING", "SEEKING", "ENDED")).contains(payload.path("state").asString());
            assertThat(event).isEqualTo(published.get(event.path("eventId").asString()));
        }
    }

    @Test void invalidLimitsAndCursorsReturnNoStoreProblems() throws Exception {
        for (String endpoint : List.of("snapshots/catalog", "snapshots/profiles", "events/playback")) {
            for (String query : List.of("limit=0", "limit=501", "limit=abc", "after=not-a-uuid",
                    "after=ABCDEFAB-1234-1234-1234-ABCDEFABCDEF", "after=123.bad-cursor")) {
                var response = get(endpoint + "?" + query);
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
                assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            }
        }
    }

    @Test void catalogPagingSurvivesPublicationChangesBetweenPages() throws Exception {
        var earlier = publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        JsonNode first = page("snapshots/catalog?limit=1", "items");
        publish(ready(create(Kind.MOVIE, null, null)), UUID.randomUUID());
        catalog.unpublish(admin, earlier.id(), new Version(earlier.version()), UUID.randomUUID());
        // Per-page consistency plus the Analytics version rule absorbs changes between snapshots.
        var seen = new HashSet<String>();
        for (JsonNode item : first.path("items")) { assertThat(seen.add(item.path("contentId").asString())).isTrue(); }
        int remaining = 1000;
        while (!first.path("next").isNull()) {
            assertThat(remaining--).isPositive();
            first = page("snapshots/catalog?limit=1&after=" + first.path("next").asString(), "items");
            for (JsonNode item : first.path("items")) { assertThat(seen.add(item.path("contentId").asString())).isTrue(); }
        }
    }

    private List<JsonNode> pages(String endpoint, int limit, String field) throws Exception {
        var all = new ArrayList<JsonNode>(); String after = null; int remaining = 1000;
        do {
            assertThat(remaining--).isPositive();
            var page = page(endpoint + "?limit=" + limit + (after == null ? "" : "&after=" + after), field);
            assertThat(page.path(field).size()).isLessThanOrEqualTo(limit);
            page.path(field).forEach(all::add);
            after = page.path("next").isNull() ? null : page.path("next").asString();
        } while (after != null);
        return all;
    }
    private JsonNode page(String path, String field) throws Exception {
        var response = get(path); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode page = mapper.readTree(response.body()); fields(page, field, "next");
        assertThat(page.path(field).isArray()).isTrue();
        assertThat(page.path("next").isNull() || page.path("next").isString()).isTrue();
        return page;
    }
    private HttpResponse<String> get(String path) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("libra-analytics-services").subject("libra-analytics")
                .audience("libra-core-internal").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(60)))
                .claim("purpose", "service-access").claim("scope", "analytics.bootstrap:read").build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("bootstrap").build(), claims);
        jwt.sign(new RSASSASigner(BOOTSTRAP.getPrivate()));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/analytics/" + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + jwt.serialize()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void fields(JsonNode node, String... names) {
        assertThat(node.isObject()).isTrue();
        assertThat(node.propertyNames()).containsExactlyInAnyOrder(names);
    }
    private static UUID uuid(JsonNode node) {
        assertThat(node.isString()).isTrue(); UUID id = UUID.fromString(node.asString());
        assertThat(id.toString()).isEqualTo(node.asString()); return id;
    }
    private static void integer(JsonNode node) { assertThat(node.isIntegralNumber()).isTrue(); assertThat(node.asLong()).isPositive(); }

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
