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
@Import(AnalyticsReadIntegrationTest.TimeConfiguration.class)
class AnalyticsReadIntegrationTest {
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
    record Observed(String path, String authorization, String cookie, String correlationId, String body) {}
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
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
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
    private void candidates(List<UUID> ids) {
        responder = exchange -> respond(exchange, 200, mapper.writeValueAsString(new CandidateResponse(profile, clock.instant(), ids)));
    }
    private Recommendations recommendations() { return analytics.recommendations(viewer, profile, 20, UUID.randomUUID()); }
    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private Statistics statistics() { return analytics.statistics(admin, today().minusDays(6), today(), UUID.randomUUID()); }

    @Test void authenticatesDownstreamWithScopedServiceJwtAndFiltersCandidatesUsingCurrentCoreState() throws Exception {
        var movie = movie(); var hidden = movie();
        catalog.unpublish(admin, hidden.id(), new Version(hidden.version()), UUID.randomUUID());
        var series = publish(create(Kind.SERIES, null, null));
        var season = publish(create(Kind.SEASON, series.id(), 1));
        var episode = publish(ready(create(Kind.EPISODE, season.id(), 1)));
        var emptySeries = publish(create(Kind.SERIES, null, null));
        candidates(List.of(hidden.id(), UUID.randomUUID(), episode.id(), emptySeries.id(), series.id(), movie.id(), movie.id()));
        UUID correlation = UUID.randomUUID();
        var result = analytics.recommendations(viewer, profile, 20, correlation);
        assertThat(result.source()).isEqualTo(Source.ANALYTICS);
        assertThat(result.items()).extracting(PublicView::id).containsExactly(series.id(), movie.id());
        assertThat(result.reason()).isNull(); assertThat(result.updatedAt()).isEqualTo(clock.instant());
        assertThat(observed).hasSize(1);
        var call = observed.element();
        assertThat(call.path()).isEqualTo("/internal/v1/recommendations/query");
        assertThat(call.cookie()).isNull(); assertThat(call.correlationId()).isEqualTo(correlation.toString());
        var query = mapper.readTree(call.body());
        assertThat(query.path("accountId").asString()).isEqualTo(viewer.accountId().toString());
        assertThat(query.path("profileId").asString()).isEqualTo(profile.toString());
        assertThat(query.path("limit").asInt()).isEqualTo(100);
        var jwt = SignedJWT.parse(call.authorization().substring(7));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) AnalyticsTestKeys.SERVICE.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("recommendations:read");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(AnalyticsServiceTokens.AUDIENCE);
        assertThat(jwt.getJWTClaimsSet().getClaims()).doesNotContainKeys("email", "sid", "profileId");
        assertThat(fixtureError.get()).isNull();
    }

    @Test void fallbackIsTruthfulBoundedAndStillFiltersUnpublishedOrFailedMedia() {
        var movie = movie(); var failed = movie();
        jdbc.update("UPDATE catalog_media_bindings SET state = 'FAILED' WHERE id = ?", failed.active().id());
        responder = exchange -> respond(exchange, 503, "{\"privateDownstreamError\":true}");
        var result = analytics.recommendations(viewer, profile, 1, UUID.randomUUID());
        assertThat(result.source()).isEqualTo(Source.FALLBACK); assertThat(result.reason()).isEqualTo(Failure.UNAVAILABLE);
        assertThat(result.updatedAt()).isNull(); assertThat(result.items()).hasSize(1);
        assertThat(result.items()).extracting(PublicView::id).doesNotContain(failed.id());
        assertThat(observed).hasSize(1);
        candidates(List.of(UUID.randomUUID()));
        assertThat(recommendations().reason()).isEqualTo(Failure.NO_VISIBLE_CANDIDATES);
        candidates(List.of(movie.id()));
        responder = exchange -> respond(exchange, 200, mapper.writeValueAsString(new CandidateResponse(profile, clock.instant().minusSeconds(901), List.of(movie.id()))));
        assertThat(recommendations().reason()).isEqualTo(Failure.STALE);
    }

    @Test void downstreamProfileMismatchMalformedSchemaFutureTimeAndOversizedBodiesNeverLeakCandidates() {
        var movie = movie();
        for (String invalid : List.of("not-json", "{}", "null",
                mapper.writeValueAsString(new CandidateResponse(UUID.randomUUID(), clock.instant(), List.of(movie.id()))),
                mapper.writeValueAsString(new CandidateResponse(profile, clock.instant().plusSeconds(1), List.of(movie.id()))),
                mapper.writeValueAsString(new CandidateResponse(profile, clock.instant(), Collections.nCopies(101, movie.id()))),
                "{\"profileId\":\"" + profile + "\",\"updatedAt\":\"" + clock.instant() + "\",\"contentIds\":[null]}")) {
            responder = exchange -> respond(exchange, 200, invalid);
            assertThat(recommendations().reason()).isEqualTo(Failure.INVALID_RESPONSE);
        }
        responder = exchange -> respond(exchange, 200, "x".repeat(65537));
        assertThat(recommendations().source()).isEqualTo(Source.FALLBACK);
    }

    @Test void timeoutIncludesDelayedBodyAndRedirectNeverForwardsServiceCredentials() {
        for (boolean headersFirst : List.of(false, true)) {
            responder = exchange -> {
                if (headersFirst) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                }
                Thread.sleep(1500);
                if (!headersFirst) { respond(exchange, 200, "{}"); }
            };
            long start = System.nanoTime();
            assertThat(recommendations().reason()).isEqualTo(Failure.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
        }
        observed.clear();
        responder = exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort() + "/credential-trap");
            respond(exchange, 302, "{}");
        };
        assertThat(recommendations().reason()).isEqualTo(Failure.UNAVAILABLE);
        assertThat(observed).hasSize(1);
        assertThat(observed.element().path()).isEqualTo("/internal/v1/recommendations/query");
    }

    @Test void unauthorizedRequestsDoNotReachAnalyticsAndPublicResultsContainNoServiceCredential() throws Exception {
        var movie = movie(); candidates(List.of(movie.id()));
        String path = "/v1/profiles/" + profile + "/recommendations";
        assertThat(request(null, "GET", path, null, false).statusCode()).isEqualTo(401);
        assertThat(request(admin, "GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(request(viewer, "GET", path + "?limit=101", null, false).statusCode()).isEqualTo(400);
        assertThat(request(viewer, "GET", "/v1/admin/statistics?from=" + today() + "&to=" + today(), null, false).statusCode()).isEqualTo(403);
        assertThat(observed).isEmpty();
        var response = request(viewer, "GET", path, null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ANALYTICS").doesNotContain("Bearer", "accountId", "profileId", "email", "assetId");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    @Test void concurrentProfileDeletionAndRevocationAreRecheckedWithoutHoldingAccountLockDuringHttp() {
        var second = profiles.create(viewer, "Temporary", UUID.randomUUID());
        responder = exchange -> {
            profiles.delete(viewer, second.id(), second.version(), UUID.randomUUID());
            respond(exchange, 200, mapper.writeValueAsString(new CandidateResponse(second.id(), clock.instant(), List.of())));
        };
        assertThatThrownBy(() -> analytics.recommendations(viewer, second.id(), 20, UUID.randomUUID())).hasMessage("NOT_FOUND");
        assertThat(fixtureError.get()).isNull();
        responder = exchange -> {
            sessions.revokeOwned(viewer, viewer.sessionId());
            respond(exchange, 503, "{}");
        };
        assertThatThrownBy(this::recommendations).hasMessage("INVALID_CREDENTIALS");
        assertThat(fixtureError.get()).isNull();
    }

    @Test void unpublicationDuringTheCallIsFilteredAndUnverifiedUsersMayReceiveRecommendations() {
        var movie = movie();
        jdbc.update("UPDATE identity_accounts SET email_verified = FALSE WHERE id = ?", viewer.accountId());
        responder = exchange -> {
            catalog.unpublish(admin, movie.id(), new Version(movie.version()), UUID.randomUUID());
            respond(exchange, 200, mapper.writeValueAsString(new CandidateResponse(profile, clock.instant(), List.of(movie.id()))));
        };
        var result = recommendations();
        assertThat(result.source()).isEqualTo(Source.FALLBACK);
        assertThat(result.items()).extracting(PublicView::id).doesNotContain(movie.id());
        assertThat(fixtureError.get()).isNull();
    }

    @Test void statisticsDistinguishAvailableStaleZeroAndUnavailableWithBoundedUtcWindow() throws Exception {
        responder = exchange -> respond(exchange, 200, mapper.writeValueAsString(new StatisticsResponse(today().minusDays(6), today(), clock.instant(), 0L, 0L, 0L)));
        var fresh = statistics();
        assertThat(fresh.status()).isEqualTo(Status.AVAILABLE); assertThat(fresh.qualifiedViews()).isZero();
        assertThat(SignedJWT.parse(observed.element().authorization().substring(7)).getJWTClaimsSet().getStringClaim("scope"))
                .isEqualTo("statistics:read");
        responder = exchange -> respond(exchange, 200, mapper.writeValueAsString(new StatisticsResponse(today().minusDays(6), today(), clock.instant().minusSeconds(901), 4L, 2L, 160000L)));
        var stale = statistics();
        assertThat(stale.status()).isEqualTo(Status.STALE); assertThat(stale.qualifiedViews()).isEqualTo(4);
        assertThat(stale.updatedAt()).isEqualTo(clock.instant().minusSeconds(901));
        responder = exchange -> respond(exchange, 503, "{}");
        var missing = statistics();
        assertThat(missing.status()).isEqualTo(Status.UNAVAILABLE); assertThat(missing.qualifiedViews()).isNull();
        assertThat(missing.acceptedWatchedMs()).isNull(); assertThat(missing.updatedAt()).isNull();
        int calls = observed.size();
        assertThatThrownBy(() -> analytics.statistics(admin, today().minusDays(31), today(), UUID.randomUUID())).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> analytics.statistics(admin, today(), today().plusDays(1), UUID.randomUUID())).hasMessage("INVALID_REQUEST");
        assertThat(observed).hasSize(calls);
    }

    @Test void statisticsRejectNegativeCountsWrongWindowAndDemotedAdministrators() {
        for (String wrongType : List.of("1.5", "\"1\"")) {
            String body = "{\"from\":\"" + today().minusDays(6) + "\",\"to\":\"" + today()
                    + "\",\"updatedAt\":\"" + clock.instant() + "\",\"qualifiedViews\":" + wrongType
                    + ",\"uniqueViewers\":0,\"acceptedWatchedMs\":0}";
            responder = exchange -> respond(exchange, 200, body);
            assertThat(statistics().reason()).isEqualTo(Failure.INVALID_RESPONSE);
        }
        for (StatisticsResponse invalid : List.of(
                new StatisticsResponse(today().minusDays(6), today(), clock.instant(), -1L, 0L, 0L),
                new StatisticsResponse(today().minusDays(6), today(), clock.instant(), 1L, 2L, 0L),
                new StatisticsResponse(today(), today(), clock.instant(), 1L, 1L, 30000L))) {
            responder = exchange -> respond(exchange, 200, mapper.writeValueAsString(invalid));
            assertThat(statistics().reason()).isEqualTo(Failure.INVALID_RESPONSE);
        }
        responder = exchange -> {
            jdbc.update("UPDATE identity_accounts SET role = 'USER' WHERE id = ?", admin.accountId());
            respond(exchange, 503, "{}");
        };
        assertThatThrownBy(this::statistics).hasMessage("ACCESS_DENIED");
    }

    @Test void disabledAdapterWorksWithoutSecretsOrAnyDownstreamCall() {
        var properties = new AnalyticsProperties(false, null, "", "", "");
        var tokens = new AnalyticsServiceTokens(properties, AnalyticsTestKeys.identity(true), AnalyticsTestKeys.playback(), clock);
        var disabledClient = new AnalyticsClient(properties, tokens, mapper, validator);
        try {
            var service = new AnalyticsReadService(readAccess, disabledClient, clock);
            assertThat(service.recommendations(viewer, profile, 20, UUID.randomUUID()).reason()).isEqualTo(Failure.DISABLED);
            assertThat(service.statistics(admin, today(), today(), UUID.randomUUID()).reason()).isEqualTo(Failure.DISABLED);
            assertThat(observed).isEmpty();
        } finally { disabledClient.close(); }
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
    private AdminView create(Kind kind, UUID parent, Integer ordinal) {
        return catalog.create(admin, new Create(kind, parent, ordinal,
                new Metadata("Title", "Description", List.of("drama"), 2026, "en", List.of(), List.of(), Tier.FREE)));
    }
    private AdminView movie() { return publish(ready(create(Kind.MOVIE, null, null))); }
    private AdminView publish(AdminView content) { return catalog.publish(admin, content.id(), new Version(content.version()), UUID.randomUUID()); }
    private AdminView ready(AdminView content) {
        content = catalog.bind(admin, content.id(), new Bind(content.version(), UUID.randomUUID(), 1));
        var binding = content.candidate();
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), 1, clock.instant(), UUID.randomUUID(), mapper.valueToTree(new MediaProjectionService.Change(
                        content.id(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.READY, 120))));
        return catalog.get(admin, content.id());
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
