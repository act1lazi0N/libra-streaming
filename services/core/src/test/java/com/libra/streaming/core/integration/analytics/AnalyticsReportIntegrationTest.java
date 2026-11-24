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
import org.junit.jupiter.params.provider.CsvSource;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AnalyticsReportIntegrationTest.TimeConfiguration.class)
class AnalyticsReportIntegrationTest {
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
    @Autowired AnalyticsReadService analytics;
    @Autowired AnalyticsReadAccess readAccess;
    @Autowired AnalyticsClient client;
    @Autowired Validator validator;
    @Autowired IdentitySessionService sessions;
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
    HttpClient http;
    @BeforeEach void setup() {
        clock.reset();
        viewer = actor(true, "USER"); admin = actor(true, "ADMIN");
        profile = profiles.list(viewer).getFirst().id();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        responder = exchange -> respond(exchange, 503, "{}");
        client.recommendations(new RecommendationQuery(viewer.accountId(), profile, 100), UUID.randomUUID());
        observed.clear();
        fixtureError.set(null);
    }
    @AfterEach void close() { http.close(); }

    interface Responder { void respond(HttpExchange exchange) throws Exception; }
    record Observed(String path, String authorization, String cookie, String correlationId, String contentType, String body) {}
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
                    observed.add(new Observed(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
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
    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String path() { return "/v1/admin/statistics/report?from=" + today().minusDays(6) + "&to=" + today(); }
    private Map<String, String> values() {
        var values = new HashMap<String, String>();
        values.put("from", today().minusDays(6).toString());
        values.put("to", today().toString());
        values.put("now", clock.instant().toString());
        values.put("updatedAt", clock.instant().minusSeconds(60).toString());
        values.put("staleUpdatedAt", clock.instant().minusSeconds(901).toString());
        values.put("futureUpdatedAt", clock.instant().plusSeconds(1).toString());
        values.put("dayBefore", today().minusDays(7).toString());
        values.put("from32", today().minusDays(31).toString());
        values.put("tomorrow", today().plusDays(1).toString());
        values.put("movieId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        values.put("seriesId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        values.put("otherId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
        for (int i = 1; i <= 7; i++) { values.put("day" + i, today().minusDays(7 - i).toString()); }
        return values;
    }
    private String available() {
        return AnalyticsContractFixtures.byField(AnalyticsContractFixtures.load(mapper, "analytics-v2"),
                "id", "report-available").instantiate(values());
    }
    private tools.jackson.databind.JsonNode report() throws Exception {
        var response = request(admin, "GET", path(), null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertPrivate(response.body());
        assertThat(fixtureError.get()).isNull();
        return mapper.readTree(response.body());
    }
    private void assertPrivate(String body) {
        assertThat(body).doesNotContain("accountId", "profileId", "sessionId", "Bearer",
                admin.accountId().toString(), viewer.accountId().toString(),
                admin.sessionId().toString(), viewer.sessionId().toString());
        for (var actor : List.of(admin, viewer)) {
            for (var seeded : profiles.list(actor)) { assertThat(body).doesNotContain(seeded.id().toString()); }
        }
    }
    private void assertNoNumbers(tools.jackson.databind.JsonNode result) {
        for (String key : List.of("updatedAt", "totals", "days", "topContent")) {
            assertThat(result.has(key)).as(key).isTrue();
            assertThat(result.path(key).isNull()).as(key).isTrue();
        }
    }

    @Test void defaultTopSendsExactV2QueryAndScopedServiceIdentity() throws Exception {
        responder = exchange -> respond(exchange, 200, available());
        UUID correlation = UUID.randomUUID();
        String token = accessToken(admin);
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path()))
                .header("Cookie", "LIBRA_ACCESS=" + token).header("X-Correlation-ID", correlation.toString())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asString()).isEqualTo("AVAILABLE");
        assertPrivate(response.body());
        assertThat(observed).hasSize(1);
        var call = observed.element();
        assertThat(call.path()).isEqualTo("/internal/v2/statistics/query");
        assertThat(call.contentType()).isEqualTo("application/json");
        assertThat(call.correlationId()).isEqualTo(correlation.toString());
        assertThat(call.cookie()).isNull();
        assertThat(mapper.readTree(call.body())).isEqualTo(mapper.readTree(AnalyticsContractFixtures.byField(
                AnalyticsContractFixtures.load(mapper, "analytics-v2"), "coreRequest", "statisticsReport").instantiate(values())));
        assertThat(call.authorization()).startsWith("Bearer ");
        var jwt = SignedJWT.parse(call.authorization().substring(7));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) AnalyticsTestKeys.SERVICE.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("statistics:read");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(AnalyticsServiceTokens.AUDIENCE);
        assertThat(fixtureError.get()).isNull();
    }

    @Test void everyResponseFixtureHasItsDeclaredOutcomeAndExactNumbers() throws Exception {
        // Report fixtures only: the corpus also holds RA05.3's recommendation lists, which carry a coreOutcome too.
        var fixtures = AnalyticsContractFixtures.with(AnalyticsContractFixtures.load(mapper, "analytics-v2"), "coreOutcome")
                .stream().filter(f -> "#/components/schemas/StatisticsReport".equals(f.text("pointer"))).toList();
        assertThat(fixtures).hasSize(26);
        for (var fixture : fixtures) {
            String body = fixture.instantiate(values());
            responder = exchange -> respond(exchange, 200, body);
            var actual = report();
            String outcome = fixture.text("coreOutcome");
            assertThat(actual.path("status").asString()).as(fixture.id())
                    .isEqualTo(outcome.equals("INVALID_RESPONSE") ? "UNAVAILABLE" : outcome);
            if (outcome.equals("AVAILABLE")) { assertThat(actual.path("reason").isNull()).as(fixture.id()).isTrue(); }
            else { assertThat(actual.path("reason").asString()).as(fixture.id()).isEqualTo(outcome); }
            if (outcome.equals("INVALID_RESPONSE")) { assertNoNumbers(actual); }
            else {
                var sent = mapper.readTree(body);
                assertThat(actual.path("updatedAt")).as(fixture.id()).isEqualTo(sent.path("updatedAt"));
                assertThat(actual.path("totals")).as(fixture.id()).isEqualTo(sent.path("totals"));
                assertThat(actual.path("days")).as(fixture.id()).isEqualTo(sent.path("days"));
                assertThat(actual.path("topContent").size()).isEqualTo(sent.path("topContent").size());
                for (int i = 0; i < sent.path("topContent").size(); i++) {
                    for (String key : List.of("contentId", "qualifiedViews", "uniqueViewers", "acceptedWatchedMs")) {
                        assertThat(actual.path("topContent").get(i).path(key)).as(fixture.id() + " " + key)
                                .isEqualTo(sent.path("topContent").get(i).path(key));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 50})
    void explicitTopBoundaryIsForwardedToAnalytics(int top) throws Exception {
        String zeros = AnalyticsContractFixtures.byField(AnalyticsContractFixtures.load(mapper, "analytics-v2"),
                "id", "report-zeros").instantiate(values());
        responder = exchange -> respond(exchange, 200, zeros);
        var response = request(admin, "GET", path() + "&top=" + top, null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(observed).hasSize(1);
        assertThat(mapper.readTree(observed.element().body())).isEqualTo(mapper.valueToTree(
                new StatisticsReportQuery(today().minusDays(6), today(), top)));
        assertThat(fixtureError.get()).isNull();
    }

    @Test void failuresAndDisabledAdapterExposeNulls() throws Exception {
        responder = exchange -> respond(exchange, 503, "{}");
        var missing = report();
        assertThat(missing.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(missing.path("reason").asString()).isEqualTo("UNAVAILABLE");
        assertNoNumbers(missing);
        responder = exchange -> respond(exchange, 200, "not-json");
        var malformed = report();
        assertThat(malformed.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(malformed.path("reason").asString()).isEqualTo("INVALID_RESPONSE");
        assertNoNumbers(malformed);
        observed.clear();
        var properties = new AnalyticsProperties(false, null, "", "", "");
        var tokens = new AnalyticsServiceTokens(properties, AnalyticsTestKeys.identity(true), AnalyticsTestKeys.playback(), clock);
        var disabledClient = new AnalyticsClient(properties, tokens, mapper, validator);
        try {
            var service = new AnalyticsReadService(readAccess, disabledClient, clock);
            var result = service.statisticsReport(admin, today().minusDays(6), today(), 10, UUID.randomUUID());
            assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
            assertThat(result.reason()).isEqualTo(Failure.DISABLED);
            assertNoNumbers(mapper.valueToTree(result));
            assertThat(observed).isEmpty();
        } finally { disabledClient.close(); }
    }

    @Test void anonymousAndUserAreDeniedBeforeDownstream() throws Exception {
        assertThat(request(null, "GET", path(), null, false).statusCode()).isEqualTo(401);
        assertThat(request(viewer, "GET", path(), null, false).statusCode()).isEqualTo(403);
        assertThat(observed).isEmpty();
    }

    @Test void invalidQueriesNeverReachDownstream() throws Exception {
        for (String query : List.of(path() + "&top=0", path() + "&top=51", path() + "&top=abc", path() + "&top=10.5",
                "/v1/admin/statistics/report?from=" + today().minusDays(31) + "&to=" + today(),
                "/v1/admin/statistics/report?from=" + today() + "&to=" + today().plusDays(1),
                "/v1/admin/statistics/report?from=" + today() + "&to=" + today().minusDays(1))) {
            var response = request(admin, "GET", query, null, false);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(observed).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"200,false", "200,true", "503,false", "503,true"})
    void demotionAndSuspensionDuringSuccessOrFailureDenyDisclosure(int status, boolean suspend) throws Exception {
        observed.clear();
        responder = exchange -> {
            // The open HTTP exchange holds Core's request while this independent transaction revokes authority.
            if (suspend) { jdbc.update("UPDATE identity_accounts SET status = 'SUSPENDED' WHERE id = ?", admin.accountId()); }
            else { jdbc.update("UPDATE identity_accounts SET role = 'USER' WHERE id = ?", admin.accountId()); }
            respond(exchange, status, status == 200 ? available() : "{}");
        };
        try {
            var response = request(admin, "GET", path(), null, false);
            assertThat(response.statusCode()).isEqualTo(suspend ? 401 : 403);
            assertThat(response.body()).doesNotContain("qualifiedViews", "uniqueViewers", "acceptedWatchedMs", "topContent", "totals", "days");
            assertThat(observed).hasSize(1);
            assertThat(fixtureError.get()).isNull();
        } finally {
            jdbc.update("UPDATE identity_accounts SET role = 'ADMIN', status = 'ACTIVE' WHERE id = ?", admin.accountId());
        }
    }

    private Metadata metadata(String title) {
        return new Metadata(title, "Description", List.of("drama"), 2026, "en", List.of(), List.of(), Tier.FREE);
    }
    private AdminView adminCall(String method, String suffix, Object body, int expected) throws Exception {
        var response = request(admin, method, "/v1/admin/catalog" + suffix, mapper.writeValueAsString(body), true);
        assertThat(response.statusCode()).isEqualTo(expected);
        return mapper.readValue(response.body(), AdminView.class);
    }
    @Test void labelsUsePublishedRevisionOrDraftAndPreserveAnalyticsOrder() throws Exception {
        var movie = adminCall("POST", "", new Create(Kind.MOVIE, null, null, metadata("Published movie")), 201);
        movie = adminCall("POST", "/" + movie.id() + "/media-bindings", new Bind(movie.version(), UUID.randomUUID(), 1), 200);
        var binding = movie.candidate();
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), 1, clock.instant(), UUID.randomUUID(), mapper.valueToTree(new MediaProjectionService.Change(
                        movie.id(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.READY, 120))));
        movie = catalog.get(admin, movie.id());
        movie = adminCall("POST", "/" + movie.id() + "/publication", new Version(movie.version()), 200);
        movie = adminCall("PUT", "/" + movie.id() + "/draft", new Edit(movie.version(), metadata("New unpublished revision")), 200);
        var draft = adminCall("POST", "", new Create(Kind.MOVIE, null, null, metadata("Draft movie")), 201);
        var series = adminCall("POST", "", new Create(Kind.SERIES, null, null, metadata("Published series")), 201);
        series = adminCall("POST", "/" + series.id() + "/publication", new Version(series.version()), 200);
        UUID unknown = UUID.randomUUID();
        var ids = List.of(movie.id(), draft.id(), series.id(), unknown);
        var top = List.of(new ContentCounts(ids.get(0), 3L, 2L, 150000L),
                new ContentCounts(ids.get(1), 2L, 2L, 90000L), new ContentCounts(ids.get(2), 0L, 0L, 2L),
                new ContentCounts(ids.get(3), 0L, 0L, 1L));
        var base = mapper.readValue(available(), StatisticsReportResponse.class);
        var days = new ArrayList<>(base.days());
        days.set(6, new DayCounts(today(), 0L, 0L, 3L));
        String body = mapper.writeValueAsString(new StatisticsReportResponse(base.from(), base.to(), base.updatedAt(),
                new Counts(5L, 3L, 240003L), days, top));
        responder = exchange -> respond(exchange, 200, body);
        var result = report();
        assertThat(result.path("status").asString()).isEqualTo("AVAILABLE");
        var titles = result.path("topContent");
        for (int i = 0; i < ids.size(); i++) { assertThat(titles.get(i).path("contentId").asString()).isEqualTo(ids.get(i).toString()); }
        for (int i = 0; i < 3; i++) {
            assertThat(titles.get(i).path("kind").asString()).isEqualTo(i == 2 ? "SERIES" : "MOVIE");
            assertThat(titles.get(i).path("title").asString()).isEqualTo(List.of("Published movie", "Draft movie", "Published series").get(i));
            assertThat(titles.get(i).path("published").asBoolean()).isEqualTo(i != 1);
        }
        assertThat(titles.get(3).path("kind").isNull()).isTrue();
        assertThat(titles.get(3).path("title").isNull()).isTrue();
        assertThat(titles.get(3).path("published").asBoolean()).isFalse();
    }

    private String accessToken(IdentityPrincipal actor) {
        var claims = JwtClaimsSet.builder().issuer("libra-core").audience(List.of("libra-web"))
                .subject(actor.accountId().toString()).claim("sid", actor.sessionId().toString()).claim("purpose", "access")
                .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
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
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
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
