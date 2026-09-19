package com.libra.streaming.core.community;

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
import static com.libra.streaming.core.community.CommunityModels.*;
import static com.libra.streaming.core.playback.PlaybackModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CommunityIntegrationTest.TimeConfiguration.class)
class CommunityIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("community_test").withUsername("community_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
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
    @Autowired ReviewService reviews;
    @Autowired WatchlistService watchlist;
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

    @Test void watchlistIsIdempotentProfileScopedAndRemovedWithProfile() throws Exception {
        var movie = movie();
        var second = profiles.create(viewer, "Second", UUID.randomUUID());
        race(() -> { watchlist.add(viewer, profile, movie.id()); return true; },
                () -> { watchlist.add(viewer, profile, movie.id()); return true; });
        assertThat(watchlist.list(viewer, profile, 20, 0)).hasSize(1);
        assertThat(watchlist.list(viewer, second.id(), 20, 0)).isEmpty();
        assertThatThrownBy(() -> watchlist.add(admin, profile, movie.id())).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> watchlist.remove(admin, profile, movie.id())).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> watchlist.list(admin, profile, 20, 0)).hasMessage("NOT_FOUND");
        watchlist.add(viewer, second.id(), movie.id());
        profiles.delete(viewer, second.id(), second.version(), UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM watchlist_entries WHERE profile_id = ?", Integer.class, second.id())).isZero();
        watchlist.remove(viewer, profile, movie.id()); watchlist.remove(viewer, profile, movie.id());
        assertThat(watchlist.list(viewer, profile, 20, 0)).isEmpty();
    }

    @Test void watchlistRequiresPublishedMovieOrSeriesAndUsesPublishedMetadata() {
        var series = publish(create(Kind.SERIES, null, null));
        var season = publish(create(Kind.SEASON, series.id(), 1));
        var draft = create(Kind.MOVIE, null, null);
        assertThatThrownBy(() -> watchlist.add(viewer, profile, season.id())).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> watchlist.add(viewer, profile, draft.id())).hasMessage("NOT_FOUND");
        watchlist.add(viewer, profile, series.id());
        catalog.edit(admin, series.id(), new Edit(series.version(), new Metadata("Secret draft", "", List.of(), 2026, "en", List.of(), List.of(), Tier.FREE)));
        assertThat(watchlist.list(viewer, profile, 20, 0).getFirst().title()).isEqualTo("Title");
        var current = catalog.get(admin, series.id());
        catalog.unpublish(admin, series.id(), new Version(current.version()), UUID.randomUUID());
        assertThat(watchlist.list(viewer, profile, 20, 0)).isEmpty();
        watchlist.remove(viewer, profile, series.id());
    }

    @Test void reviewRequiresOneQualifiedSessionAndVerifiedLiveAccount() {
        var movie = movie();
        assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(0, 5))).hasMessage("QUALIFIED_VIEW_REQUIRED");
        UUID id = playback.admit(viewer, new Admission(profile, movie.id())).view().sessionId();
        playback.progress(viewer, id, new Progress(1, 114000, 0, State.SEEKING), UUID.randomUUID());
        assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(0, 5))).hasMessage("QUALIFIED_VIEW_REQUIRED");
        playback.progress(viewer, id, new Progress(2, 0, 0, State.PLAYING), UUID.randomUUID());
        clock.advance(29);
        playback.progress(viewer, id, new Progress(3, 29000, 29000, State.PLAYING), UUID.randomUUID());
        assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(0, 5))).hasMessage("QUALIFIED_VIEW_REQUIRED");
        clock.advance(1);
        playback.progress(viewer, id, new Progress(4, 30000, 1000, State.ENDED), UUID.randomUUID());
        jdbc.update("UPDATE identity_accounts SET email_verified = FALSE WHERE id = ?", viewer.accountId());
        assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(0, 5))).hasMessage("EMAIL_VERIFICATION_REQUIRED");
        jdbc.update("UPDATE identity_accounts SET email_verified = TRUE WHERE id = ?", viewer.accountId());
        history.clear(viewer, profile, movie.id());
        assertThat(reviews.write(viewer, movie.id(), write(0, 5)).version()).isEqualTo(1);
        accounts.suspend(admin, viewer.accountId());
        assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(1, 4))).hasMessage("INVALID_CREDENTIALS");
    }

    @Test void qualifiedEpisodeAllowsSeriesVoteAcrossProfilesButNotAnotherTitle() {
        var series = publish(create(Kind.SERIES, null, null));
        var season = publish(create(Kind.SEASON, series.id(), 1));
        var episode = publish(ready(create(Kind.EPISODE, season.id(), 1)));
        var second = profiles.create(viewer, "Second", UUID.randomUUID());
        qualify(viewer, second.id(), episode.id());
        var review = reviews.write(viewer, series.id(), write(0, 4));
        assertThatThrownBy(() -> reviews.write(viewer, series.id(), write(0, 5))).hasMessage("VERSION_CONFLICT");
        assertThat(reviews.write(viewer, series.id(), write(review.version(), 5)).id()).isEqualTo(review.id());
        assertThat(reviews.list(series.id(), 20, 0).count()).isEqualTo(1);
        assertThatThrownBy(() -> reviews.write(viewer, episode.id(), write(0, 4))).hasMessage("INVALID_REQUEST");
        var other = movie();
        assertThatThrownBy(() -> reviews.write(viewer, other.id(), write(0, 4))).hasMessage("QUALIFIED_VIEW_REQUIRED");
        profiles.delete(viewer, second.id(), second.version(), UUID.randomUUID());
        assertThat(reviews.mine(viewer, series.id()).id()).isEqualTo(review.id());
        assertThatThrownBy(() -> reviews.write(viewer, series.id(), write(2, 3))).hasMessage("QUALIFIED_VIEW_REQUIRED");
    }

    @Test void hiddenReviewsStayHiddenAcrossEditDeleteRepostAndRestorationDoesNotResurrectDeletion() {
        var movie = movie(); qualify(viewer, profile, movie.id());
        var review = reviews.write(viewer, movie.id(), write(0, 5));
        var hidden = reviews.moderate(admin, review.id(), moderate(1, Visibility.HIDDEN), UUID.randomUUID());
        assertThat(reviews.list(movie.id(), 20, 0).count()).isZero();
        assertThat(reviews.list(movie.id(), 20, 0).averageStars()).isNull();
        assertThat(reviews.write(viewer, movie.id(), write(hidden.version(), 1)).hidden()).isTrue();
        reviews.delete(viewer, movie.id(), 3);
        assertThat(reviews.mine(viewer, movie.id()).text()).isNull();
        assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(0, 5))).hasMessage("VERSION_CONFLICT");
        var reposted = reviews.write(viewer, movie.id(), write(4, 4));
        assertThat(reposted.id()).isEqualTo(review.id()); assertThat(reposted.hidden()).isTrue();
        var visible = reviews.moderate(admin, review.id(), moderate(5, Visibility.VISIBLE), UUID.randomUUID());
        assertThat(reviews.list(movie.id(), 20, 0).averageStars()).isEqualByComparingTo("4.00");
        reviews.delete(viewer, movie.id(), visible.version());
        reviews.moderate(admin, review.id(), moderate(7, Visibility.VISIBLE), UUID.randomUUID());
        assertThat(reviews.list(movie.id(), 20, 0).items()).isEmpty();
        assertThat(reviews.mine(viewer, movie.id()).deleted()).isTrue();
        assertThat(reviews.audit(admin, review.id(), 20, 0)).hasSize(3);
    }

    @Test void aggregateCountsOnlyVisibleNonDeletedReviewsAndPublicDataIsMinimal() throws Exception {
        var movie = movie(); qualify(viewer, profile, movie.id());
        var first = reviews.write(viewer, movie.id(), write(0, 5));
        var other = actor(true, "USER"); qualify(other, profiles.list(other).getFirst().id(), movie.id());
        reviews.write(other, movie.id(), write(0, 2));
        assertThat(reviews.list(movie.id(), 1, 0).count()).isEqualTo(2);
        assertThat(reviews.list(movie.id(), 1, 0).averageStars()).isEqualByComparingTo("3.50");
        var response = request(null, "GET", "/v1/catalog/" + movie.id() + "/reviews", null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("accountId", "email", "profileId", viewer.accountId().toString(), "reporter", "hidden");
        reviews.moderate(admin, first.id(), moderate(1, Visibility.HIDDEN), UUID.randomUUID());
        assertThat(reviews.list(movie.id(), 20, 0).averageStars()).isEqualByComparingTo("2.00");
        reviews.delete(other, movie.id(), 1);
        assertThat(reviews.list(movie.id(), 20, 0).count()).isZero();
        catalog.unpublish(admin, movie.id(), new Version(movie.version()), UUID.randomUUID());
        assertThat(request(null, "GET", "/v1/catalog/" + movie.id() + "/reviews", null, false).statusCode()).isEqualTo(404);
    }

    @Test void reportsDeduplicateAndModerationIsAuthorizedVersionedAndAudited() throws Exception {
        var movie = movie(); qualify(viewer, profile, movie.id());
        var review = reviews.write(viewer, movie.id(), write(0, 5));
        var reporter = actor(true, "USER");
        race(() -> { reviews.report(reporter, review.id(), new Report("Spam")); return true; },
                () -> { reviews.report(reporter, review.id(), new Report("Spam")); return true; });
        assertThat(reviews.reports(admin, review.id(), 20, 0)).hasSize(1);
        assertThat(reviews.adminList(admin, true, 100, 0)).anySatisfy(row -> {
            assertThat(row.review().id()).isEqualTo(review.id()); assertThat(row.reportCount()).isEqualTo(1);
        });
        assertThatThrownBy(() -> reviews.report(viewer, review.id(), new Report("Own"))).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> reviews.report(actor(false, "USER"), review.id(), new Report("Spam")))
                .hasMessage("EMAIL_VERIFICATION_REQUIRED");
        assertThatThrownBy(() -> reviews.moderate(viewer, review.id(), moderate(1, Visibility.HIDDEN), UUID.randomUUID()))
                .hasMessage("ACCESS_DENIED");
        UUID correlation = UUID.randomUUID();
        reviews.moderate(admin, review.id(), moderate(1, Visibility.HIDDEN), correlation);
        assertThatThrownBy(() -> reviews.moderate(admin, review.id(), moderate(1, Visibility.VISIBLE), UUID.randomUUID()))
                .hasMessage("VERSION_CONFLICT");
        assertThat(reviews.audit(admin, review.id(), 20, 0)).singleElement().satisfies(audit -> {
            assertThat(audit.administratorId()).isEqualTo(admin.accountId());
            assertThat(audit.correlationId()).isEqualTo(correlation);
            assertThat(audit.previousHidden()).isFalse(); assertThat(audit.reviewVersion()).isEqualTo(2);
        });
        assertThatThrownBy(() -> reviews.report(reporter, review.id(), new Report("Again"))).hasMessage("NOT_FOUND");
    }

    @Test void concurrentAuthorsAndModeratorsCannotLoseUpdatesOrCreateMultipleVotes() throws Exception {
        var movie = movie(); qualify(viewer, profile, movie.id());
        var results = race(() -> attempt(() -> reviews.write(viewer, movie.id(), write(0, 5))),
                () -> attempt(() -> reviews.write(viewer, movie.id(), write(0, 2))));
        assertThat(results).containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        var review = reviews.mine(viewer, movie.id());
        results = race(() -> attempt(() -> reviews.write(viewer, movie.id(), write(1, 3))),
                () -> attempt(() -> reviews.moderate(admin, review.id(), moderate(1, Visibility.HIDDEN), UUID.randomUUID())));
        assertThat(results).containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        assertThat(reviews.mine(viewer, movie.id()).version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews WHERE account_id = ? AND content_id = ?", Integer.class,
                viewer.accountId(), movie.id())).isEqualTo(1);
    }

    @Test void auditFailureRollsBackModeration() {
        var movie = movie(); qualify(viewer, profile, movie.id());
        var review = reviews.write(viewer, movie.id(), write(0, 5));
        // Force a real database failure in the audit insert, scoped to this review only.
        jdbc.execute("ALTER TABLE review_moderation_audit ADD CONSTRAINT test_audit_failure CHECK (review_id <> '" + review.id() + "'::uuid)");
        try {
            assertThatThrownBy(() -> reviews.moderate(admin, review.id(), moderate(1, Visibility.HIDDEN), UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(reviews.mine(viewer, movie.id()).hidden()).isFalse();
            assertThat(reviews.mine(viewer, movie.id()).version()).isEqualTo(1);
            assertThat(reviews.audit(admin, review.id(), 20, 0)).isEmpty();
        } finally { jdbc.execute("ALTER TABLE review_moderation_audit DROP CONSTRAINT test_audit_failure"); }
    }

    @Test void realHttpEnforcesCsrfAuthenticationOwnershipValidationAndAdminRole() throws Exception {
        var movie = movie(); qualify(viewer, profile, movie.id());
        String path = "/v1/me/reviews/" + movie.id(); String body = mapper.writeValueAsString(write(0, 4));
        assertThat(request(viewer, "PUT", path, body, false).statusCode()).isEqualTo(403);
        assertThat(request(null, "PUT", path, body, true).statusCode()).isEqualTo(401);
        for (String invalid : List.of("{\"stars\":4}", "{\"expectedVersion\":0,\"stars\":6}",
                "{\"expectedVersion\":0,\"stars\":4,\"hidden\":false}",
                mapper.writeValueAsString(new WriteReview(0L, 4, "x".repeat(2001))))) {
            assertThat(request(viewer, "PUT", path, invalid, true).statusCode()).isEqualTo(400);
        }
        assertThat(request(viewer, "PUT", path, body, true).statusCode()).isEqualTo(200);
        var review = reviews.mine(viewer, movie.id());
        assertThat(request(admin, "GET", path, null, false).statusCode()).isEqualTo(404);
        assertThat(request(admin, "DELETE", path + "?expectedVersion=1", null, true).statusCode()).isEqualTo(404);
        String moderation = "/v1/admin/reviews/" + review.id() + "/moderation";
        assertThat(request(viewer, "PUT", moderation, mapper.writeValueAsString(moderate(1, Visibility.HIDDEN)), true).statusCode()).isEqualTo(403);
        assertThat(request(viewer, "GET", "/v1/admin/reviews/" + review.id() + "/audit", null, false).statusCode()).isEqualTo(403);
        assertThat(request(admin, "PUT", moderation, "{\"expectedVersion\":1,\"visibility\":\"HIDDEN\",\"reason\":\" \"}", true).statusCode()).isEqualTo(400);
        assertThat(request(admin, "PUT", moderation, mapper.writeValueAsString(moderate(1, Visibility.HIDDEN)), true).statusCode()).isEqualTo(200);
        String watchPath = "/v1/profiles/" + profile + "/watchlist/" + movie.id();
        assertThat(request(viewer, "PUT", watchPath, null, false).statusCode()).isEqualTo(403);
        assertThat(request(admin, "PUT", watchPath, null, true).statusCode()).isEqualTo(404);
        assertThat(request(viewer, "PUT", watchPath, null, true).statusCode()).isEqualTo(204);
        assertThat(request(viewer, "GET", "/v1/catalog/" + movie.id() + "/reviews?limit=101", null, false).statusCode()).isEqualTo(400);
    }

    @Test void rateLimitsSurviveRejectedReviewTransactions() throws Exception {
        var movie = movie();
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> reviews.write(viewer, movie.id(), write(0, 5))).hasMessage("QUALIFIED_VIEW_REQUIRED");
        }
        var response = request(viewer, "PUT", "/v1/me/reviews/" + movie.id(), mapper.writeValueAsString(write(0, 5)), true);
        assertThat(response.statusCode()).isEqualTo(429); assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(reviews.list(movie.id(), 20, 0).count()).isZero();
    }

    @Test void reportHttpDenialsAndRateLimitsCannotCreateExtraReports() throws Exception {
        var movie = movie(); qualify(viewer, profile, movie.id());
        var review = reviews.write(viewer, movie.id(), write(0, 5));
        var reporter = actor(true, "USER");
        String path = "/v1/reviews/" + review.id() + "/reports";
        String body = mapper.writeValueAsString(new Report("Spam"));
        assertThat(request(reporter, "POST", path, body, false).statusCode()).isEqualTo(403);
        assertThat(request(null, "POST", path, body, true).statusCode()).isEqualTo(401);
        assertThat(request(reporter, "POST", path, "{\"reason\":\" \"}", true).statusCode()).isEqualTo(400);
        assertThat(request(reporter, "POST", path, "{\"reason\":\"Spam\",\"reporterId\":\"" + viewer.accountId() + "\"}", true).statusCode()).isEqualTo(400);
        assertThat(reviews.reports(admin, review.id(), 20, 0)).isEmpty();
        assertThat(request(reporter, "POST", path, body, true).statusCode()).isEqualTo(204);
        for (int i = 1; i < 20; i++) { reviews.report(reporter, review.id(), new Report("Ignored duplicate")); }
        assertThat(request(reporter, "POST", path, body, true).statusCode()).isEqualTo(429);
        assertThat(reviews.reports(admin, review.id(), 20, 0)).singleElement()
                .satisfies(report -> assertThat(report.reason()).isEqualTo("Spam"));
        assertThat(request(reporter, "GET", "/v1/admin/reviews/" + review.id() + "/reports", null, false).statusCode()).isEqualTo(403);
    }

    @Test void profileDeletionRacingWithWatchlistAddLeavesNoOrphanAndUnverifiedUsersCanSave() throws Exception {
        var unverified = actor(false, "USER");
        var temporary = profiles.create(unverified, "Temporary", UUID.randomUUID());
        var movie = movie();
        watchlist.add(unverified, temporary.id(), movie.id());
        assertThat(watchlist.list(unverified, temporary.id(), 20, 0)).hasSize(1);
        race(() -> {
            String result = attempt(() -> watchlist.add(unverified, temporary.id(), movie.id()));
            assertThat(result).isIn("OK", "NOT_FOUND"); return true;
        }, () -> { profiles.delete(unverified, temporary.id(), temporary.version(), UUID.randomUUID()); return true; });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM watchlist_entries WHERE profile_id = ?", Integer.class, temporary.id())).isZero();
    }

    @Test void migrationUpgradesV5PreservingExistingRowsAndIsRepeatable() {
        var baseline = org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("community_upgrade").defaultSchema("community_upgrade").target("5").load();
        baseline.migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO community_upgrade.identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Existing', 'inert-fixture', 'USER')", id, id + "@example.test");
        var upgrade = org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("community_upgrade").defaultSchema("community_upgrade").target("6").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT display_name FROM community_upgrade.identity_accounts WHERE id = ?", String.class, id)).isEqualTo("Existing");
    }

    private WriteReview write(long version, int stars) { return new WriteReview(version, stars, "Plain text review"); }
    private Moderate moderate(long version, Visibility visibility) { return new Moderate(version, visibility, "Moderator decision"); }
    private String attempt(Runnable action) {
        try { action.run(); return "OK"; } catch (DomainException exception) { return exception.code(); }
    }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
    private void qualify(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        UUID id = playback.admit(actor, new Admission(profileId, contentId)).view().sessionId();
        playback.progress(actor, id, new Progress(1, 0, 0, State.PLAYING), UUID.randomUUID());
        clock.advance(30);
        assertThat(playback.progress(actor, id, new Progress(2, 30000, 30000, State.ENDED), UUID.randomUUID()).qualified()).isTrue();
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
