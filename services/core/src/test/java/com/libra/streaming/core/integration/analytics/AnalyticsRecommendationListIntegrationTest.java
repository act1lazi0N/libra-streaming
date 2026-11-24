package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.identity.*;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import com.libra.streaming.core.profiles.infrastructure.ProfileService;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;
import com.sun.net.httpserver.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Validator;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.security.interfaces.RSAPublicKey;
import static org.assertj.core.api.Assertions.*;

/** Real Core HTTP/security/catalog and Testcontainers PostgreSQL; Analytics HTTP is a local stub. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AnalyticsRecommendationListIntegrationTest.TimeConfiguration.class)
class AnalyticsRecommendationListIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_adapter_test").withUsername("analytics_adapter_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("libra.analytics.enabled", () -> true);
        registry.add("libra.analytics.base-url", () -> "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort());
        registry.add("libra.analytics.private-key", () -> AnalyticsTestKeys.privateKey(AnalyticsTestKeys.SERVICE));
        registry.add("libra.analytics.public-key", () -> AnalyticsTestKeys.publicKey(AnalyticsTestKeys.SERVICE));
        registry.add("libra.analytics.key-id", () -> "analytics-fixture");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock analyticsClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        synchronized void reset() { now = Instant.now().truncatedTo(ChronoUnit.SECONDS); }
        @Override public synchronized Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalyticsReadAccess readAccess;
    @Autowired AnalyticsClient client;
    @Autowired Validator validator;
    @Autowired CatalogService catalog;
    @Autowired MediaProjectionService media;
    @Autowired ProfileService profiles;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired MutableClock clock;
    @LocalServerPort int port;
    IdentityPrincipal viewer;
    IdentityPrincipal admin;
    UUID profile;
    UUID otherProfile;
    UUID correlation;
    HttpClient http;
    @BeforeEach void setup() {
        clock.reset();
        viewer = actor(true, "USER"); admin = actor(true, "ADMIN");
        profile = profiles.list(viewer).getFirst().id();
        otherProfile = profiles.create(viewer, "Second", UUID.randomUUID()).id();
        correlation = UUID.randomUUID();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        responder = exchange -> respond(exchange, 503, "{}");
        client.recommendations(new RecommendationQuery(viewer.accountId(), profile, 100), UUID.randomUUID());
        observed.clear();
        fixtureError.set(null);
    }
    @AfterEach void close() { http.close(); }

    interface Responder { void respond(HttpExchange exchange) throws Exception; }
    record Observed(String method, String path, String authorization, String cookie, String correlationId, String contentType, String body) {
        @Override public String toString() { return "Observed[" + method + " " + path + ", credentials redacted]"; }
    }
    static final Queue<Observed> observed = new ConcurrentLinkedQueue<>();
    static final AtomicReference<Throwable> fixtureError = new AtomicReference<>();
    static volatile Responder responder;
    static final ExecutorService downstreamThreads = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer DOWNSTREAM = server();
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(downstreamThreads);
            server.createContext("/", exchange -> {
                try (exchange) {
                    observed.add(new Observed(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Cookie"), exchange.getRequestHeaders().getFirst("X-Correlation-ID"),
                            exchange.getRequestHeaders().getFirst("Content-Type"), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    responder.respond(exchange);
                } catch (java.io.IOException ignored) {
                    // Expected when the bounded client cancels a delayed/chunked fixture response.
                } catch (Throwable error) { fixtureError.set(error); }
            });
            server.start(); return server;
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
    @AfterAll static void stopDownstream() { DOWNSTREAM.stop(0); downstreamThreads.shutdownNow(); }
    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private String path(UUID id, String list) {
        return "/v1/profiles/" + id + "/recommendations/lists/" + list;
    }

    private String query(String list, UUID seed) {
        return path(profile, list) + (list.equals("similar") ? "?contentId=" + seed : "");
    }

    private tools.jackson.databind.JsonNode result(String path) throws Exception {
        var response = request(viewer, "GET", path, null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(fixtureError.get()).isNull();
        var result = mapper.readTree(response.body());
        assertPublicShape(result);
        return result;
    }

    // No JSON-schema validator dependency exists in the neighbouring tests: check the closed contract key sets.
    private void assertPublicShape(tools.jackson.databind.JsonNode value) {
        assertThat(value.propertyNames()).containsExactlyInAnyOrder(
                "source", "reason", "list", "algorithmVersion", "updatedAt", "items");
        assertThat(value.path("source").asString()).isIn("ANALYTICS", "FALLBACK");
        assertThat(value.path("list").asString()).isIn("personal", "similar", "popular", "new");
        assertThat(value.path("items").isArray()).isTrue();
        assertThat(value.path("items").size()).isLessThanOrEqualTo(20);
        if (!value.path("reason").isNull()) {
            assertThat(value.path("reason").asString()).isIn("DISABLED", "UNAVAILABLE", "INVALID_RESPONSE",
                    "STALE", "NO_VISIBLE_CANDIDATES");
        }
        if (!value.path("algorithmVersion").isNull()) {
            assertThat(value.path("algorithmVersion").asString()).matches("[a-z0-9][a-z0-9.-]{0,31}");
        }
        if (!value.path("updatedAt").isNull()) { Instant.parse(value.path("updatedAt").asString()); }
        for (var item : value.path("items")) {
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("content", "reason");
            assertThat(item.path("reason").asString()).isIn("GENRE_INTEREST", "SIMILAR_GENRES", "POPULAR", "NEWLY_PUBLISHED");
            assertThat(item.path("content").propertyNames()).containsExactlyInAnyOrder("id", "kind", "parentId",
                    "ordinal", "revision", "metadata", "mediaReady", "durationSeconds", "publishedAt");
            UUID.fromString(item.path("content").path("id").asString());
        }
        assertThat(value.toString()).doesNotContain("accountId", "profileId", "Bearer",
                viewer.accountId().toString(), admin.accountId().toString(), otherProfile.toString());
        for (var owned : profiles.list(admin)) { assertThat(value.toString()).doesNotContain(owned.id().toString()); }
    }

    private List<String> ids(tools.jackson.databind.JsonNode value) {
        var ids = new ArrayList<String>();
        value.path("items").forEach(item -> ids.add(item.path("content").path("id").asString()));
        return ids;
    }

    private Map<String, String> values(UUID seed) {
        return Map.of("accountId", viewer.accountId().toString(), "profileId", profile.toString(),
                "movieId", seed.toString(), "seriesId", UUID.randomUUID().toString(),
                "otherId", UUID.randomUUID().toString(), "updatedAt", clock.instant().minusSeconds(60).toString(),
                "staleUpdatedAt", clock.instant().minusSeconds(901).toString(),
                "futureUpdatedAt", clock.instant().plusSeconds(1).toString());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"personal", "similar", "popular", "new"})
    void sendsCanonicalV2QueryAndScopedServiceIdentity(String list) throws Exception {
        var seed = movie("Seed", Tier.FREE);
        result(query(list, seed.id()));
        assertThat(observed).hasSize(1);
        var call = observed.element();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/internal/v2/recommendations/query");
        assertThat(call.contentType()).isEqualTo("application/json");
        assertThat(call.correlationId()).isEqualTo(correlation.toString());
        assertThat(call.cookie()).isNull();
        var fixture = AnalyticsContractFixtures.byField(AnalyticsContractFixtures.load(mapper, "analytics-v2"),
                "coreRequest", "list-" + list);
        assertThat(mapper.readTree(call.body())).isEqualTo(mapper.readTree(fixture.instantiate(values(seed.id()))));
        // Assert booleans to keep credentials out of assertion failure output.
        assertThat(call.authorization() != null && call.authorization().startsWith("Bearer ")).isTrue();
        var jwt = SignedJWT.parse(call.authorization().substring(7));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) AnalyticsTestKeys.SERVICE.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("recommendations:read");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(AnalyticsServiceTokens.AUDIENCE);
    }

    @Test void everyListResponseFixtureHasItsDeclaredOutcomeAfterCoreVisibility() throws Exception {
        var seed = movie("Seed", Tier.FREE);
        var fixtures = AnalyticsContractFixtures.load(mapper, "analytics-v2").stream()
                .filter(f -> f.id().startsWith("list-") && f.text("coreOutcome") != null).toList();
        assertThat(fixtures).hasSize(15);
        // All candidate placeholders are deliberately unknown to Core, including movieId. The actual seed is separate.
        var values = values(UUID.randomUUID());
        for (var fixture : fixtures) {
            String list = fixture.text("coreList");
            // Only this semantic-invalid fixture needs movieId to equal the actual seed.
            var substitutions = new HashMap<>(values);
            if (fixture.id().equals("list-similar-includes-seed")) { substitutions.put("movieId", seed.id().toString()); }
            String body = fixture.instantiate(substitutions);
            responder = exchange -> respond(exchange, 200, body);
            var actual = result(query(list, seed.id()));
            String outcome = fixture.text("coreOutcome");
            boolean emptyTruth = outcome.equals("ANALYTICS") && (list.equals("popular") || list.equals("similar"))
                    && mapper.readTree(body).path("items").isEmpty();
            assertThat(actual.path("source").asString()).as(fixture.id()).isEqualTo(emptyTruth ? "ANALYTICS" : "FALLBACK");
            assertThat(actual.path("list").asString()).as(fixture.id()).isEqualTo(list);
            if (emptyTruth) {
                assertThat(actual.path("reason").isNull()).as(fixture.id()).isTrue();
                assertThat(actual.path("algorithmVersion").asString()).isEqualTo("rules-v1");
                assertThat(Instant.parse(actual.path("updatedAt").asString())).isEqualTo(clock.instant().minusSeconds(60));
                assertThat(actual.path("items").isEmpty()).isTrue();
            } else {
                assertFallback(actual, outcome.equals("ANALYTICS") ? "NO_VISIBLE_CANDIDATES" : outcome, list);
            }
        }
    }

    @Test void unpublicationDuringHttpKeepsVisibleRankReasonsAndMetadataAndAppliesLimit() throws Exception {
        var a = movie("A", Tier.FREE);
        var b = movie("B", Tier.FREE);
        var c = movie("C", Tier.FREE);
        String body = answer(profile, ListKind.PERSONAL, List.of(
                new RecommendedTitle(b.id(), Reason.GENRE_INTEREST),
                new RecommendedTitle(UUID.randomUUID(), Reason.GENRE_INTEREST),
                new RecommendedTitle(a.id(), Reason.POPULAR), new RecommendedTitle(c.id(), Reason.NEWLY_PUBLISHED)));
        responder = exchange -> {
            // Independent Core transaction while the original request waits for Analytics.
            catalog.unpublish(admin, c.id(), new Version(c.version()), UUID.randomUUID());
            respond(exchange, 200, body);
        };
        var actual = result(path(profile, "personal"));
        assertThat(ids(actual)).containsExactly(b.id().toString(), a.id().toString());
        assertThat(actual.path("items").get(0).path("reason").asString()).isEqualTo("GENRE_INTEREST");
        assertThat(actual.path("items").get(1).path("reason").asString()).isEqualTo("POPULAR");
        assertThat(actual.path("source").asString()).isEqualTo("ANALYTICS");
        assertThat(actual.path("reason").isNull()).isTrue();
        assertThat(actual.path("algorithmVersion").asString()).isEqualTo("rules-v1");
        assertThat(Instant.parse(actual.path("updatedAt").asString())).isEqualTo(clock.instant().minusSeconds(60));
        responder = exchange -> respond(exchange, 200, body);
        assertThat(ids(result(path(profile, "personal") + "?limit=1"))).containsExactly(b.id().toString());
    }

    @Test void invalidQueriesAndNonTitleOrHiddenSeedsNeverCallAnalytics() throws Exception {
        var hidden = create(Kind.MOVIE, null, null, "Hidden", Tier.FREE);
        var unpublished = movie("Unpublished", Tier.FREE);
        catalog.unpublish(admin, unpublished.id(), new Version(unpublished.version()), UUID.randomUUID());
        var series = publish(create(Kind.SERIES, null, null, "Series", Tier.FREE));
        var season = publish(create(Kind.SEASON, series.id(), 1, "Season", Tier.FREE));
        var episode = publish(ready(create(Kind.EPISODE, season.id(), 1, "Episode", Tier.FREE)));
        for (UUID seed : List.of(hidden.id(), unpublished.id(), season.id(), episode.id(), UUID.randomUUID())) {
            assertError(query("similar", seed), 404, "NOT_FOUND");
        }
        for (String list : List.of("personal", "popular", "new")) {
            assertError(path(profile, list) + "?contentId=" + series.id(), 400, "INVALID_REQUEST");
        }
        for (String suffix : List.of("similar", "unknown", "PERSONAL", "personal?limit=0", "personal?limit=21")) {
            assertError(path(profile, suffix), 400, "INVALID_REQUEST");
        }
        assertThat(observed).isEmpty();
    }

    private void assertError(String path, int status, String code) throws Exception {
        var response = request(viewer, "GET", path, null, false);
        assertThat(response.statusCode()).as(path).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(observed).isEmpty();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"personal", "similar", "popular", "new"})
    void failuresUseHonestFallbackAndTimeoutIsBounded(String list) throws Exception {
        var seed = movie("Seed", Tier.FREE);
        var newest = movie("Newest", Tier.FREE);
        // Only these two rows change; put them after previous tests' titles, without relying on wall-clock ticks.
        jdbc.update("UPDATE catalog_contents SET published_at = (SELECT max(published_at) + interval '1 second' FROM catalog_contents) WHERE id = ?", seed.id());
        jdbc.update("UPDATE catalog_contents SET published_at = (SELECT max(published_at) + interval '1 second' FROM catalog_contents) WHERE id = ?", newest.id());
        String path = query(list, seed.id()) + (list.equals("similar") ? "&limit=2" : "?limit=2");
        for (String failure : List.of("503", "timeout", "non-json")) {
            responder = exchange -> {
                if (failure.equals("timeout")) { Thread.sleep(700); }
                respond(exchange, failure.equals("503") ? 503 : 200, failure.equals("non-json") ? "not-json" : "{}");
            };
            long start = System.nanoTime();
            var actual = result(path);
            if (failure.equals("timeout")) {
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
            }
            assertFallback(actual, failure.equals("non-json") ? "INVALID_RESPONSE" : "UNAVAILABLE", list);
            if (list.equals("personal") || list.equals("new")) {
                assertThat(ids(actual)).containsExactly(newest.id().toString(), seed.id().toString());
            }
        }
        observed.clear();
        var properties = new AnalyticsProperties(false, null, "", "", "");
        var tokens = new AnalyticsServiceTokens(properties, AnalyticsTestKeys.identity(true), AnalyticsTestKeys.playback(), clock);
        var disabled = new AnalyticsClient(properties, tokens, mapper, validator);
        try {
            // As in the report IT: real transactional access/projection, with a locally disabled adapter.
            var service = new AnalyticsReadService(readAccess, disabled, clock);
            var actual = mapper.valueToTree(service.recommendationList(viewer, profile,
                    ListKind.valueOf(list.toUpperCase(Locale.ROOT)), list.equals("similar") ? seed.id() : null, 2, correlation));
            assertPublicShape(actual);
            assertFallback(actual, "DISABLED", list);
            if (list.equals("personal") || list.equals("new")) {
                assertThat(ids(actual)).containsExactly(newest.id().toString(), seed.id().toString());
            }
            assertThat(observed).isEmpty();
        } finally { disabled.close(); }
    }

    private void assertFallback(tools.jackson.databind.JsonNode actual, String reason, String list) {
        assertThat(actual.path("source").asString()).isEqualTo("FALLBACK");
        assertThat(actual.path("reason").asString()).isEqualTo(reason);
        assertThat(actual.path("algorithmVersion").isNull()).isTrue();
        assertThat(actual.path("updatedAt").isNull()).isTrue();
        if (list.equals("popular") || list.equals("similar")) { assertThat(actual.path("items").isEmpty()).isTrue(); }
        else {
            for (var item : actual.path("items")) { assertThat(item.path("reason").asString()).isEqualTo("NEWLY_PUBLISHED"); }
        }
    }

    @Test void authenticationAndOwnershipDenyBeforeAnalyticsAndRecommendationsNeverGrantPremium() throws Exception {
        String path = path(profile, "personal");
        assertThat(request(null, "GET", path, null, false).statusCode()).isEqualTo(401);
        var stranger = actor(true, "USER");
        assertThat(request(stranger, "GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(observed).isEmpty();
        var premium = movie("Premium", Tier.PREMIUM);
        String body = answer(profile, ListKind.PERSONAL, List.of(new RecommendedTitle(premium.id(), Reason.GENRE_INTEREST)));
        responder = exchange -> respond(exchange, 200, body);
        var actual = result(path);
        assertThat(actual.path("source").asString()).isEqualTo("ANALYTICS");
        assertThat(ids(actual)).containsExactly(premium.id().toString());
        assertThat(actual.toString()).doesNotContain(stranger.accountId().toString());
        var admission = new com.libra.streaming.core.playback.PlaybackModels.Admission(profile, premium.id());
        var response = request(viewer, "POST", "/v1/playback/sessions", mapper.writeValueAsString(admission), true);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo("PREMIUM_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_sessions WHERE account_id = ?", Integer.class,
                viewer.accountId())).isZero();
    }

    @Test void alternatingProfilesNeverMixItemsAndEachRequestCallsAnalyticsOnce() throws Exception {
        var a = movie("First profile", Tier.FREE);
        var b = movie("Second profile", Tier.FREE);
        responder = exchange -> {
            UUID requested = UUID.fromString(mapper.readTree(observed.stream().reduce((first, last) -> last).orElseThrow().body())
                    .path("profileId").asString());
            respond(exchange, 200, answer(requested, ListKind.PERSONAL,
                    List.of(new RecommendedTitle(requested.equals(profile) ? a.id() : b.id(), Reason.GENRE_INTEREST))));
        };
        for (UUID requested : List.of(profile, otherProfile, profile, otherProfile)) {
            var actual = result(path(requested, "personal"));
            assertThat(actual.path("source").asString()).isEqualTo("ANALYTICS");
            assertThat(ids(actual)).containsExactly((requested.equals(profile) ? a : b).id().toString());
            assertThat(actual.toString()).doesNotContain(profile.toString(), otherProfile.toString());
        }
        assertThat(observed).hasSize(4);
        var requestedProfiles = new ArrayList<String>();
        for (var call : observed) { requestedProfiles.add(mapper.readTree(call.body()).path("profileId").asString()); }
        assertThat(requestedProfiles).containsExactly(profile.toString(), otherProfile.toString(), profile.toString(), otherProfile.toString());
    }

    private String answer(UUID profileId, ListKind list, List<RecommendedTitle> items) {
        return mapper.writeValueAsString(new RecommendationListResponse(profileId, list, "rules-v1",
                clock.instant().minusSeconds(60), items));
    }

    private AdminView adminCall(String suffix, Object body, int expected) throws Exception {
        var response = request(admin, "POST", "/v1/admin/catalog" + suffix, mapper.writeValueAsString(body), true);
        assertThat(response.statusCode()).isEqualTo(expected);
        return mapper.readValue(response.body(), AdminView.class);
    }

    private AdminView create(Kind kind, UUID parent, Integer ordinal, String title, Tier tier) throws Exception {
        return adminCall("", new Create(kind, parent, ordinal,
                new Metadata(title, "Description", List.of("drama"), 2026, "en", List.of(), List.of(), tier)), 201);
    }

    private AdminView movie(String title, Tier tier) throws Exception { return publish(ready(create(Kind.MOVIE, null, null, title, tier))); }
    private AdminView publish(AdminView content) throws Exception {
        return adminCall("/" + content.id() + "/publication", new Version(content.version()), 200);
    }
    private AdminView ready(AdminView content) throws Exception {
        content = adminCall("/" + content.id() + "/media-bindings", new Bind(content.version(), UUID.randomUUID(), 1), 200);
        var binding = content.candidate();
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), 1, clock.instant(), UUID.randomUUID(), mapper.valueToTree(new MediaProjectionService.Change(
                        content.id(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.READY, 120))));
        return catalog.get(admin, content.id());
    }

    private IdentityPrincipal actor(boolean verified, String role) {
        UUID id = UUID.randomUUID(); UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role, email_verified) VALUES (?, ?, 'Viewer', 'inert-fixture', ?, ?)",
                id, id + "@example.test", role, verified);
        new TransactionTemplate(transactions).executeWithoutResult(status -> profiles.initialize(id, "Viewer"));
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)", sid,
                id, Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().plusSeconds(3600)));
        return new IdentityPrincipal(id, sid, role, verified);
    }
    private HttpResponse<String> request(IdentityPrincipal actor, String method, String path, String body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20)).header("X-Correlation-ID", correlation.toString())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        List<String> cookies = new ArrayList<>();
        if (actor != null) {
            var claims = JwtClaimsSet.builder().issuer("libra-core").audience(List.of("libra-web"))
                    .subject(actor.accountId().toString()).claim("sid", actor.sessionId().toString()).claim("purpose", "access")
                    .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
            cookies.add("LIBRA_ACCESS=" + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        }
        if (csrf) {
            var token = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            builder.header("X-CSRF-TOKEN", mapper.readTree(token.body()).path("token").asString());
            token.headers().allValues("Set-Cookie").forEach(value -> cookies.add(value.split(";", 2)[0]));
        }
        if (!cookies.isEmpty()) { builder.header("Cookie", String.join("; ", cookies)); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
