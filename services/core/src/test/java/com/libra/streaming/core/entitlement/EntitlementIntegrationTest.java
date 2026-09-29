package com.libra.streaming.core.entitlement;

import com.libra.streaming.core.entitlement.infrastructure.EntitlementService;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.identity.*;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import com.libra.streaming.core.profiles.infrastructure.ProfileService;
import com.libra.streaming.core.subscriptions.infrastructure.SubscriptionService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EntitlementIntegrationTest.TimeConfiguration.class)
class EntitlementIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("entitlement_test").withUsername("entitlement_test").withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock entitlementTestClock() {
            return Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntitlementService entitlements;
    @Autowired CatalogService catalog;
    @Autowired CatalogQueries queries;
    @Autowired MediaProjectionService media;
    @Autowired ProfileService profiles;
    @Autowired SubscriptionService subscriptions;
    @Autowired IdentitySessionService sessions;
    @Autowired IdentityAccountService accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired Clock clock;
    @LocalServerPort int port;
    IdentityPrincipal admin;
    IdentityPrincipal viewer;
    UUID profile;
    HttpClient http;

    @BeforeEach void setup() {
        admin = actor(true, "ADMIN");
        viewer = actor(true, "USER");
        profile = profiles.list(viewer).getFirst().id();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void close() { http.close(); }

    @Test void verifiedFreeViewerNeedsNoSubscriptionAndReceivesOnlyEligibilityOverHttp() throws Exception {
        var movie = movie(Tier.FREE);
        var result = entitlements.requireEligible(viewer, profile, movie.id());
        assertThat(result.bindingId()).isEqualTo(movie.active().id());
        assertThat(result.assetId()).isEqualTo(movie.active().assetId());
        assertThat(result.assetVersion()).isEqualTo(movie.active().assetVersion());
        assertThat(result.durationSeconds()).isEqualTo(120);
        assertThat(result.premiumExpiresAt()).isNull();
        assertThat(result.sessionExpiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
        int outboxBefore = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        var response = get(viewer, profile, movie.id());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(mapper.readTree(response.body()).size()).isEqualTo(3);
        assertThat(mapper.readTree(response.body()).path("eligible").asBoolean()).isTrue();
        assertThat(response.body()).contains("FREE").doesNotContain("asset", "binding", "session", "email", "token", "url");
        assertThat(subscriptions.status(viewer).status()).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(outboxBefore);
    }

    @Test void premiumIsSharedAcrossProfilesAndExpiresAtExactServerBoundary() throws Exception {
        var premium = movie(Tier.PREMIUM);
        var free = movie(Tier.FREE);
        assertProblem(viewer, profile, premium.id(), 403, "PREMIUM_REQUIRED");
        var purchase = subscriptions.activate(viewer, UUID.randomUUID(), "PREMIUM_30_DAYS");
        var secondProfile = profiles.create(viewer, "Second", UUID.randomUUID());
        assertThat(entitlements.requireEligible(viewer, profile, premium.id()).premiumExpiresAt()).isEqualTo(purchase.expiresAt());
        assertThat(entitlements.requireEligible(viewer, secondProfile.id(), premium.id()).premiumExpiresAt()).isEqualTo(purchase.expiresAt());
        for (long micros : new long[]{1, 0, -1}) {
            jdbc.update("UPDATE subscriptions SET expires_at = ? WHERE account_id = ?",
                    Timestamp.from(clock.instant().plus(micros, ChronoUnit.MICROS)), viewer.accountId());
            if (micros > 0) { assertThat(get(viewer, profile, premium.id()).statusCode()).isEqualTo(200); }
            else { assertProblem(viewer, profile, premium.id(), 403, "PREMIUM_REQUIRED"); }
            assertThat(get(viewer, profile, free.id()).statusCode()).isEqualTo(200);
        }
        assertThat(subscriptions.purchases(viewer, 20, 0)).hasSize(1);
    }

    @Test void unverifiedAccountsCannotPlayFreeOrPremiumAndAdminsHaveNoBypass() throws Exception {
        var free = movie(Tier.FREE);
        var premium = movie(Tier.PREMIUM);
        for (String role : List.of("USER", "ADMIN")) {
            var actor = actor(false, role);
            UUID owned = profiles.list(actor).getFirst().id();
            // A stale or forged in-process flag is not authoritative either.
            var stale = new IdentityPrincipal(actor.accountId(), actor.sessionId(), role, true);
            assertThatThrownBy(() -> entitlements.requireEligible(stale, owned, free.id())).hasMessage("EMAIL_VERIFICATION_REQUIRED");
            assertProblem(actor, owned, free.id(), 403, "EMAIL_VERIFICATION_REQUIRED");
            assertProblem(actor, owned, premium.id(), 403, "EMAIL_VERIFICATION_REQUIRED");
            jdbc.update("UPDATE identity_accounts SET email_verified = TRUE WHERE id = ?", actor.accountId());
            assertThat(entitlements.requireEligible(actor, owned, free.id()).accessTier()).isEqualTo(Tier.FREE);
            assertProblem(actor, owned, premium.id(), 403, "PREMIUM_REQUIRED");
        }
    }

    @Test void authenticationRevocationSuspensionAndSessionOwnershipAreRechecked() throws Exception {
        var movie = movie(Tier.FREE);
        assertProblem(null, profile, movie.id(), 401, "AUTHENTICATION_REQUIRED");
        assertThatThrownBy(() -> entitlements.requireEligible(null, profile, movie.id())).hasMessage("INVALID_CREDENTIALS");
        for (String state : List.of("REVOKED", "SUSPENDED", "EXPIRED", "FOREIGN_SESSION")) {
            var actor = actor(true, "USER");
            UUID owned = profiles.list(actor).getFirst().id();
            var denied = actor;
            switch (state) {
                case "REVOKED" -> sessions.revokeOwned(actor, actor.sessionId());
                case "SUSPENDED" -> accounts.suspend(admin, actor.accountId());
                case "EXPIRED" -> jdbc.update("UPDATE identity_sessions SET expires_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), actor.sessionId());
                case "FOREIGN_SESSION" -> denied = new IdentityPrincipal(actor.accountId(), viewer.sessionId(), "USER", true);
                default -> throw new IllegalStateException();
            }
            var current = denied;
            assertProblem(current, owned, movie.id(), 401, "AUTHENTICATION_REQUIRED");
            assertThatThrownBy(() -> entitlements.requireEligible(current, owned, movie.id())).hasMessage("INVALID_CREDENTIALS");
        }
    }

    @Test void foreignDeletedAndUnknownProfilesAreHiddenEvenWithPremium() throws Exception {
        var movie = movie(Tier.PREMIUM);
        subscriptions.activate(viewer, UUID.randomUUID(), "PREMIUM_30_DAYS");
        UUID foreign = profiles.list(admin).getFirst().id();
        assertProblem(viewer, foreign, movie.id(), 404, "NOT_FOUND");
        assertProblem(admin, profile, movie.id(), 404, "NOT_FOUND");
        assertProblem(viewer, UUID.randomUUID(), movie.id(), 404, "NOT_FOUND");
        var deleted = profiles.create(viewer, "Temporary", UUID.randomUUID());
        profiles.delete(viewer, deleted.id(), deleted.version(), UUID.randomUUID());
        assertProblem(viewer, deleted.id(), movie.id(), 404, "NOT_FOUND");
        assertThat(get(viewer, profile, movie.id()).statusCode()).isEqualTo(200);
    }

    @Test void draftsUnknownContentAndNonPlayableContainersAreDenied() throws Exception {
        var draft = create(Kind.MOVIE, null, Tier.FREE);
        assertProblem(viewer, profile, draft.id(), 404, "NOT_FOUND");
        assertProblem(viewer, profile, UUID.randomUUID(), 404, "NOT_FOUND");
        var series = publish(create(Kind.SERIES, null, Tier.FREE));
        var season = publish(create(Kind.SEASON, series.id(), Tier.FREE));
        assertProblem(viewer, profile, series.id(), 409, "NOT_PLAYABLE_CONTENT");
        assertProblem(viewer, profile, season.id(), 409, "NOT_PLAYABLE_CONTENT");
    }

    @Test void hidingEitherAncestorOrTheEpisodeStopsEligibilityAndRestoringPreservesChildState() throws Exception {
        var series = publish(create(Kind.SERIES, null, Tier.PREMIUM));
        var season = publish(create(Kind.SEASON, series.id(), Tier.PREMIUM));
        var episode = publish(ready(create(Kind.EPISODE, season.id(), Tier.FREE)));
        // Tier is explicit on the playable unit, not implicitly inherited from a container.
        assertThat(get(viewer, profile, episode.id()).statusCode()).isEqualTo(200);
        for (UUID id : List.of(series.id(), season.id(), episode.id())) {
            var current = catalog.get(admin, id);
            catalog.unpublish(admin, id, new Version(current.version()), UUID.randomUUID());
            assertProblem(viewer, profile, episode.id(), 404, "NOT_FOUND");
            assertThatThrownBy(() -> queries.get(episode.id())).hasMessage("NOT_FOUND");
            if (!id.equals(episode.id())) { assertThat(catalog.get(admin, episode.id()).published()).isNotNull(); }
            publish(catalog.get(admin, id));
            assertThat(get(viewer, profile, episode.id()).statusCode()).isEqualTo(200);
        }
    }

    @Test void replacementNeverChangesSelectionUntilExplicitPublicationAndStaleOldReadyCannotRestoreIt() throws Exception {
        var movie = movie(Tier.FREE);
        var original = movie.active();
        movie = catalog.bind(admin, movie.id(), new Bind(movie.version(), original.assetId(), 2));
        var candidate = movie.candidate();
        long version = 2;
        for (MediaState state : List.of(MediaState.PROCESSING, MediaState.FAILED, MediaState.READY)) {
            media.accept(candidate.assetId().toString(), event(candidate, version++, state));
            assertThat(entitlements.requireEligible(viewer, profile, movie.id()).bindingId()).isEqualTo(original.id());
        }
        movie = publish(catalog.get(admin, movie.id()));
        assertThat(entitlements.requireEligible(viewer, profile, movie.id()).assetVersion()).isEqualTo(2);
        media.accept(original.assetId().toString(), event(original, 1, MediaState.READY));
        media.accept(candidate.assetId().toString(), event(candidate, 5, MediaState.FAILED));
        assertProblem(viewer, profile, movie.id(), 409, "MEDIA_NOT_READY");
        media.accept(candidate.assetId().toString(), event(candidate, 4, MediaState.READY));
        assertProblem(viewer, profile, movie.id(), 409, "MEDIA_NOT_READY");
        media.accept(candidate.assetId().toString(), event(candidate, 6, MediaState.READY));
        assertThat(entitlements.requireEligible(viewer, profile, movie.id()).bindingId()).isEqualTo(candidate.id());
    }

    @Test void readinessAndPublishedTierCannotBeBypassedUsingDraftsOrCandidateState() throws Exception {
        var movie = movie(Tier.PREMIUM);
        movie = catalog.edit(admin, movie.id(), new Edit(movie.version(), metadata(Tier.FREE)));
        assertProblem(viewer, profile, movie.id(), 403, "PREMIUM_REQUIRED");
        movie = publish(movie);
        assertThat(entitlements.requireEligible(viewer, profile, movie.id()).accessTier()).isEqualTo(Tier.FREE);
        var active = movie.active();
        movie = catalog.bind(admin, movie.id(), new Bind(movie.version(), UUID.randomUUID(), 1));
        media.accept(movie.candidate().assetId().toString(), event(movie.candidate(), 1, MediaState.READY));
        for (MediaState state : List.of(MediaState.PROCESSING, MediaState.FAILED)) {
            media.accept(active.assetId().toString(), event(active, state == MediaState.PROCESSING ? 2 : 3, state));
            assertProblem(viewer, profile, movie.id(), 409, "MEDIA_NOT_READY");
        }
        // Defensive handling of an absent selected asset, despite a READY candidate.
        jdbc.update("UPDATE catalog_contents SET active_binding = NULL WHERE id = ?", movie.id());
        assertProblem(viewer, profile, movie.id(), 409, "MEDIA_NOT_READY");
        publish(catalog.get(admin, movie.id()));
        assertThat(get(viewer, profile, movie.id()).statusCode()).isEqualTo(200);
    }

    @Test void invalidIdentifiersAreRejectedWithSafeDetailsAndWithoutCreatingState() throws Exception {
        var movie = movie(Tier.FREE);
        var response = request(viewer, "/v1/profiles/not-a-uuid/entitlements/" + movie.id());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST").doesNotContain("Exception", "SELECT");
        // Spring's standard Problem instance may contain the request path; error detail stays generic.
        assertThat(mapper.readTree(response.body()).path("detail").asString()).isEqualTo("Bad Request.");
        assertThatThrownBy(() -> entitlements.requireEligible(viewer, null, movie.id())).hasMessage("INVALID_REQUEST");
        assertThat(subscriptions.purchases(viewer, 20, 0)).isEmpty();
    }

    @Test void concurrentPublicationDoesNotMixFreeRevisionWithPremiumAsset() throws Exception {
        var free = movie(Tier.FREE);
        var changed = catalog.edit(admin, free.id(), new Edit(free.version(), metadata(Tier.PREMIUM)));
        var premium = publish(ready(changed));
        UUID contentId = free.id();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 60; i++) {
                    var selection = i % 2 == 0 ? free : premium;
                    new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.update(
                            "UPDATE catalog_contents SET published_revision = ?, active_binding = ? WHERE id = ?",
                            selection.published().revision(), selection.active().id(), contentId));
                }
                return true;
            });
            var reader = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 60; i++) {
                    try {
                        var result = entitlements.requireEligible(viewer, profile, contentId);
                        assertThat(result.accessTier()).isEqualTo(Tier.FREE);
                        assertThat(result.bindingId()).isEqualTo(free.active().id());
                        assertThat(result.publishedRevision()).isEqualTo(free.published().revision());
                    } catch (DomainException exception) { assertThat(exception.code()).isEqualTo("PREMIUM_REQUIRED"); }
                }
                return true;
            });
            assertThat(writer.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(reader.get(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private IdentityPrincipal actor(boolean verified, String role) {
        UUID id = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role, email_verified) VALUES (?, ?, 'Viewer', 'inert-fixture', ?, ?)",
                id, id + "@example.test", role, verified);
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                sid, id, Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().plusSeconds(3600)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> profiles.initialize(id, "Viewer"));
        return new IdentityPrincipal(id, sid, role, verified);
    }

    private Metadata metadata(Tier tier) { return new Metadata("Title", "Description", List.of("drama"), 2026, "en", List.of(), List.of(), tier); }
    private AdminView create(Kind kind, UUID parent, Tier tier) {
        return catalog.create(admin, new Create(kind, parent, parent == null ? null : 1, metadata(tier)));
    }
    private AdminView publish(AdminView content) { return catalog.publish(admin, content.id(), new Version(content.version()), UUID.randomUUID()); }
    private AdminView ready(AdminView content) {
        content = catalog.bind(admin, content.id(), new Bind(content.version(), UUID.randomUUID(), 1));
        media.accept(content.candidate().assetId().toString(), event(content.candidate(), 1, MediaState.READY));
        return catalog.get(admin, content.id());
    }
    private AdminView movie(Tier tier) { return publish(ready(create(Kind.MOVIE, null, tier))); }
    private EventEnvelope event(Binding binding, long version, MediaState state) {
        return new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1, binding.assetId(), version, clock.instant(), UUID.randomUUID(),
                mapper.valueToTree(new MediaProjectionService.Change(binding.contentId(), binding.id(), binding.assetId(), binding.assetVersion(), state,
                        state == MediaState.READY ? 120 : null)));
    }
    private void assertProblem(IdentityPrincipal actor, UUID profileId, UUID contentId, int status, String code) throws Exception {
        var response = get(actor, profileId, contentId);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("assetId", "bindingId", "sessionId", "stackTrace");
    }
    private HttpResponse<String> get(IdentityPrincipal actor, UUID profileId, UUID contentId) throws Exception {
        return request(actor, "/v1/profiles/" + profileId + "/entitlements/" + contentId);
    }
    private HttpResponse<String> request(IdentityPrincipal actor, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20)).GET();
        if (actor != null) {
            var claims = JwtClaimsSet.builder().issuer("libra-core").audience(List.of("libra-web"))
                    .subject(actor.accountId().toString()).claim("sid", actor.sessionId().toString()).claim("purpose", "access")
                    .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
            request.header("Cookie", "LIBRA_ACCESS=" + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
