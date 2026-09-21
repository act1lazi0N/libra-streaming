package com.libra.streaming.core.integration.security;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.history.HistoryService;
import com.libra.streaming.core.identity.*;
import com.libra.streaming.core.integration.outbox.EventEnvelope;
import com.libra.streaming.core.playback.PlaybackService;
import com.libra.streaming.core.profiles.ProfileService;
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
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.KeyPair;
import static com.libra.streaming.core.playback.PlaybackModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InternalServiceSecurityIntegrationTest.TimeConfiguration.class)
class InternalServiceSecurityIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("community_test").withUsername("community_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("libra.services.media.enabled", () -> true);
        registry.add("libra.services.media.public-key", () -> Base64.getEncoder().encodeToString(MEDIA.getPublic().getEncoded()));
        registry.add("libra.services.media.key-id", () -> "media-fixture");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock communityClock() { return new MutableClock(); }
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
    @Autowired PlaybackService playback;
    @Autowired HistoryService history;
    @Autowired CatalogService catalog;
    @Autowired MediaProjectionService media;
    @Autowired ProfileService profiles;
    @Autowired IdentityAccountService accounts;
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
    }
    @AfterEach void close() { http.close(); }

    static final KeyPair MEDIA = pair();
    private static KeyPair pair() {
        try { var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
    private String token(java.util.function.Consumer<JWTClaimsSet.Builder> edit) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("libra-media-services").subject("libra-media")
                .audience("libra-core-internal").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(60)))
                .claim("purpose", "service-access").claim("scope", "media.bindings:read");
        edit.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("media-fixture").build(), claims.build());
        jwt.sign(new RSASSASigner(MEDIA.getPrivate())); return jwt.serialize();
    }
    private HttpResponse<String> bearer(String method, String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void exactMediaServiceIdentityReadsBindingAndBrowserCookiesCannot() throws Exception {
        var content = ready(create(Kind.MOVIE, null, null));
        String path = "/internal/v1/media/bindings/" + content.candidate().id();
        assertThat(request(admin, "GET", path, null, false).statusCode()).isEqualTo(401);
        assertThat(bearer("GET", path, null).statusCode()).isEqualTo(401);
        var response = bearer("GET", path, token(claims -> {}));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(content.candidate().assetId().toString(), "\"candidate\":true")
                .doesNotContain("email", "password", "privateKey", "storage", "token");
        assertThat(bearer("GET", "/internal/v1/media/bindings/" + UUID.randomUUID(), token(claims -> {})).statusCode()).isEqualTo(404);
        assertThat(bearer("POST", path, token(claims -> {})).statusCode()).isEqualTo(403);
        assertThat(bearer("GET", "/v1/admin/operations/summary", token(claims -> {})).statusCode()).isEqualTo(401);
        assertThat(request(admin, "POST", "/v1/admin/catalog", "{}", false).statusCode()).isEqualTo(403);
    }
    @Test void invalidAudienceIssuerPurposeSubjectLifetimeMissingClaimsAndSignatureFailClosed() throws Exception {
        String path = "/internal/v1/media/bindings/" + UUID.randomUUID();
        List<java.util.function.Consumer<JWTClaimsSet.Builder>> invalid = List.of(
                claims -> claims.audience("libra-web"), claims -> claims.issuer("libra-core"),
                claims -> claims.subject("libra-analytics"), claims -> claims.claim("purpose", "access"),
                claims -> claims.issueTime(null), claims -> claims.expirationTime(null), claims -> claims.jwtID(null),
                claims -> claims.expirationTime(Date.from(clock.instant())),
                claims -> claims.issueTime(Date.from(clock.instant().plusSeconds(1))),
                claims -> claims.expirationTime(Date.from(clock.instant().plusSeconds(61))));
        for (var change : invalid) { assertThat(bearer("GET", path, token(change)).statusCode()).isEqualTo(401); }
        String valid = token(claims -> {}); int signature = valid.lastIndexOf('.') + 1;
        String tampered = valid.substring(0, signature) + (valid.charAt(signature) == 'A' ? 'B' : 'A') + valid.substring(signature + 1);
        assertThat(bearer("GET", path, tampered).statusCode()).isEqualTo(401);
        assertThat(bearer("GET", path, token(claims -> claims.claim("scope", "statistics:read"))).statusCode()).isEqualTo(403);
        assertThat(bearer("GET", path, token(claims -> claims.claim("scope", null))).statusCode()).isEqualTo(403);
    }
    @Test void disabledServiceIdentityAndReusedPublicKeysAreRejected() {
        var playback = new com.libra.streaming.core.playback.PlaybackProperties(
                Base64.getEncoder().encodeToString(MEDIA.getPrivate().getEncoded()), Base64.getEncoder().encodeToString(MEDIA.getPublic().getEncoded()), "test");
        var analytics = new com.libra.streaming.core.integration.analytics.AnalyticsProperties(false, null, "", "", "");
        var disabled = InternalServiceSecurity.decoder(new MediaServiceProperties(false, "", ""), playback, analytics, clock);
        assertThatThrownBy(() -> disabled.decode("anything")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> InternalServiceSecurity.decoder(new MediaServiceProperties(true, playback.publicKey(), "test"), playback, analytics, clock))
                .hasMessageContaining("dedicated RSA");
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
