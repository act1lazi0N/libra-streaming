package com.libra.streaming.core.playback;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.history.HistoryService;
import com.libra.streaming.core.identity.*;
import com.libra.streaming.core.integration.outbox.EventEnvelope;
import com.libra.streaming.core.profiles.ProfileService;
import com.libra.streaming.core.subscriptions.SubscriptionService;
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
import static com.libra.streaming.core.playback.PlaybackModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PlaybackIntegrationTest.TimeConfiguration.class)
class PlaybackIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("playback_test").withUsername("playback_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Deliberately unavailable: accepted history/outbox must not call Analytics or a broker.
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock playbackClock() { return new MutableClock(); }
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
    @Autowired NextEpisodeService nextEpisodes;
    @Autowired CatalogService catalog;
    @Autowired MediaProjectionService media;
    @Autowired ProfileService profiles;
    @Autowired SubscriptionService subscriptions;
    @Autowired IdentitySessionService identitySessions;
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

    @Test void httpAdmissionUsesCsrfAndReturnsOnlyPathWithScopedHttpOnlyCookie() throws Exception {
        var content = movie(Tier.FREE);
        String body = mapper.writeValueAsString(new Admission(profile, content.id()));
        assertThat(request(viewer, "POST", "/v1/playback/sessions", body, false).statusCode()).isEqualTo(403);
        assertThat(request(null, "POST", "/v1/playback/sessions", body, true).statusCode()).isEqualTo(401);
        var response = request(viewer, "POST", "/v1/playback/sessions", body, true);
        assertThat(response.statusCode()).isEqualTo(201);
        var json = mapper.readTree(response.body());
        String id = json.path("sessionId").asString();
        assertThat(json.path("manifestPath").asString()).isEqualTo("/api/media/v1/streams/" + id + "/master.m3u8");
        assertThat(response.body()).doesNotContain("ticket", "assetId", "bindingId", "email", "?");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("LIBRA_PLAYBACK=", "HttpOnly", "SameSite=Lax", "Path=/api/media/v1/streams/" + id + "/")
                .doesNotContain("Domain="));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_sessions WHERE account_id = ?", Integer.class, viewer.accountId())).isEqualTo(1);
        var jwks = request(null, "GET", "/v1/playback/jwks", null, false);
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(jwks.body()).contains("test-playback-key", "RSA").doesNotContain("\"d\"", "\"p\"", "\"q\"");
    }

    @Test void admissionRechecksVerificationOwnershipAndPremiumWithoutCreatingState() {
        var free = movie(Tier.FREE); var premium = movie(Tier.PREMIUM);
        var unverified = actor(false, "ADMIN");
        UUID unverifiedProfile = profiles.list(unverified).getFirst().id();
        assertThatThrownBy(() -> playback.admit(unverified, new Admission(unverifiedProfile, free.id())))
                .hasMessage("EMAIL_VERIFICATION_REQUIRED");
        assertThatThrownBy(() -> playback.admit(admin, new Admission(profile, free.id()))).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> playback.admit(viewer, new Admission(profile, premium.id()))).hasMessage("PREMIUM_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_sessions WHERE account_id = ?", Integer.class, viewer.accountId())).isZero();
        assertThat(history.list(viewer, profile, false, 20, 0)).isEmpty();
    }

    @Test void premiumAndAuthenticationExpiryCapTicketsAndEqualityDeniesRenewal() {
        var movie = movie(Tier.PREMIUM);
        subscriptions.activate(viewer, UUID.randomUUID(), "PREMIUM_30_DAYS");
        jdbc.update("UPDATE subscriptions SET expires_at = ? WHERE account_id = ?", Timestamp.from(clock.instant().plusSeconds(40)), viewer.accountId());
        var issued = admit(movie);
        assertThat(issued.view().expiresAt()).isEqualTo(clock.instant().plusSeconds(40));
        clock.advance(40);
        assertThatThrownBy(() -> playback.renew(viewer, issued.view().sessionId())).hasMessage("PLAYBACK_EXPIRED");
        assertThatThrownBy(() -> admit(movie)).hasMessage("PREMIUM_REQUIRED");
        var free = movie(Tier.FREE);
        jdbc.update("UPDATE identity_sessions SET expires_at = ? WHERE id = ?", Timestamp.from(clock.instant().plusSeconds(20)), viewer.sessionId());
        assertThat(admit(free).view().expiresAt()).isEqualTo(clock.instant().plusSeconds(20));
    }

    @Test void renewalRequiresOriginalAuthenticationSessionAndCurrentAccountRights() throws Exception {
        var content = movie(Tier.FREE);
        UUID id = admit(content).view().sessionId();
        var otherSession = anotherSession(viewer);
        assertThatThrownBy(() -> playback.renew(otherSession, id)).hasMessage("NOT_FOUND");
        assertThat(request(admin, "POST", "/v1/playback/sessions/" + id + "/renew", "", true).statusCode()).isEqualTo(404);
        clock.advance(30);
        assertThat(playback.renew(viewer, id).view().expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
        identitySessions.revokeOwned(viewer, viewer.sessionId());
        assertThatThrownBy(() -> playback.renew(viewer, id)).hasMessage("INVALID_CREDENTIALS");
        assertThat(request(viewer, "POST", "/v1/playback/sessions/" + id + "/renew", "", true).statusCode()).isEqualTo(401);
        var suspended = actor(true, "USER");
        var owned = profiles.list(suspended).getFirst().id();
        UUID suspendedId = playback.admit(suspended, new Admission(owned, content.id())).view().sessionId();
        accounts.suspend(admin, suspended.accountId());
        assertThatThrownBy(() -> playback.renew(suspended, suspendedId)).hasMessage("INVALID_CREDENTIALS");
    }

    @Test void hiddenAncestorsAndReplacedAssetsDenyRenewalAndProgress() {
        var series = publish(create(Kind.SERIES, null, null, Tier.FREE));
        var season = publish(create(Kind.SEASON, series.id(), 1, Tier.FREE));
        var episode = publish(ready(create(Kind.EPISODE, season.id(), 1, Tier.FREE)));
        UUID id = admit(episode).view().sessionId();
        catalog.unpublish(admin, series.id(), new Version(series.version()), UUID.randomUUID());
        assertThatThrownBy(() -> playback.renew(viewer, id)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> progress(id, 1, 0, 0, State.PLAYING)).hasMessage("NOT_FOUND");
        publish(catalog.get(admin, series.id()));
        // A processing candidate alone does not invalidate the old active asset.
        var replacement = catalog.bind(admin, episode.id(), new Bind(episode.version(), UUID.randomUUID(), 2));
        assertThat(playback.renew(viewer, id)).isNotNull();
        readyEvent(replacement.candidate());
        publish(catalog.get(admin, episode.id()));
        assertThatThrownBy(() -> playback.renew(viewer, id)).hasMessage("MEDIA_BINDING_CHANGED");
    }

    @Test void activeMediaFailureAndPremiumLossStopRenewal() {
        var content = movie(Tier.PREMIUM);
        subscriptions.activate(viewer, UUID.randomUUID(), "PREMIUM_30_DAYS");
        UUID id = admit(content).view().sessionId();
        jdbc.update("UPDATE subscriptions SET expires_at = ? WHERE account_id = ?", Timestamp.from(clock.instant()), viewer.accountId());
        assertThatThrownBy(() -> playback.renew(viewer, id)).hasMessage("PREMIUM_REQUIRED");
        subscriptions.activate(viewer, UUID.randomUUID(), "PREMIUM_30_DAYS");
        var binding = content.active();
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), 2, clock.instant(), UUID.randomUUID(), mapper.valueToTree(new MediaProjectionService.Change(
                        content.id(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.FAILED, null))));
        assertThatThrownBy(() -> playback.renew(viewer, id)).hasMessage("MEDIA_NOT_READY");
    }

    @Test void progressSeparatesTimelineFromDurationAndDoesNotCountSeekPauseBufferOrMissingHeartbeats() {
        UUID id = admit(movie(Tier.FREE)).view().sessionId();
        assertThat(progress(id, 1, 0, 30000, State.PLAYING).watchedMs()).isZero();
        clock.advance(15);
        assertThat(progress(id, 2, 15000, 30000, State.PLAYING).watchedMs()).isEqualTo(15000);
        clock.advance(1);
        assertThat(progress(id, 3, 110000, 30000, State.SEEKING).watchedMs()).isEqualTo(15000);
        clock.advance(10);
        assertThat(progress(id, 4, 110000, 30000, State.PAUSED).watchedMs()).isEqualTo(15000);
        clock.advance(10);
        assertThat(progress(id, 5, 110000, 30000, State.BUFFERING).watchedMs()).isEqualTo(15000);
        progress(id, 6, 110000, 0, State.PLAYING);
        clock.advance(31);
        assertThat(progress(id, 7, 110000, 30000, State.PLAYING).watchedMs()).isEqualTo(15000);
        clock.advance(15);
        var qualified = progress(id, 8, 115000, 15000, State.ENDED);
        assertThat(qualified.watchedMs()).isEqualTo(30000);
        assertThat(qualified.qualified()).isTrue();
        assertThat(playback.progress(viewer, id, new Progress(8, 115000, 15000, State.ENDED), UUID.randomUUID()).accepted()).isFalse();
        assertThatThrownBy(() -> progress(id, 9, 0, 0, State.PLAYING)).hasMessage("PLAYBACK_ENDED");
        assertThat(eventCount(id)).isEqualTo(8);
        assertThat(history.list(viewer, profile, false, 20, 0).getFirst().completed()).isTrue();
        assertThat(history.list(viewer, profile, true, 20, 0)).isEmpty();
    }

    @Test void duplicateStaleAndConcurrentSequenceCannotDoubleCountOrMoveResumeBackwards() throws Exception {
        UUID id = admit(movie(Tier.FREE)).view().sessionId();
        progress(id, 1, 0, 0, State.PLAYING);
        clock.advance(15);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ProgressView> operation = () -> { barrier.await(10, TimeUnit.SECONDS); return progress(id, 3, 15000, 15000, State.PLAYING); };
            var first = pool.submit(operation); var second = pool.submit(operation);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS).accepted(), second.get(20, TimeUnit.SECONDS).accepted()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(progress(id, 2, 0, 0, State.PAUSED).accepted()).isFalse();
        assertThat(eventCount(id)).isEqualTo(2);
        var resumed = history.list(viewer, profile, true, 20, 0).getFirst();
        assertThat(resumed.positionMs()).isEqualTo(15000);
        assertThat(jdbc.queryForObject("SELECT watched_ms FROM playback_sessions WHERE id = ?", Long.class, id)).isEqualTo(15000);
    }

    @Test void latestOpenedSessionOwnsResumeAndCompletedReplayStartsAtZero() {
        var content = movie(Tier.FREE);
        UUID first = admit(content).view().sessionId();
        progress(first, 1, 50000, 0, State.PAUSED);
        var second = admit(content);
        assertThat(second.view().resumePositionMs()).isEqualTo(50000);
        progress(first, 2, 100000, 0, State.PAUSED);
        assertThat(history.list(viewer, profile, false, 20, 0).getFirst().positionMs()).isEqualTo(50000);
        progress(second.view().sessionId(), 1, 114000, 0, State.ENDED);
        assertThat(history.list(viewer, profile, false, 20, 0).getFirst().completed()).isTrue();
        assertThat(admit(content).view().resumePositionMs()).isZero();
        // Completion is a timeline fact and does not qualify a view.
        assertThat(jdbc.queryForObject("SELECT watched_ms FROM playback_sessions WHERE id = ?", Long.class, second.view().sessionId())).isZero();
    }

    @Test void clearAndProfileDeletionPreventLateProgressFromRecreatingHistory() {
        var content = movie(Tier.FREE);
        UUID id = admit(content).view().sessionId();
        progress(id, 1, 15000, 0, State.PAUSED);
        history.clear(viewer, profile, content.id());
        assertThatThrownBy(() -> progress(id, 2, 30000, 0, State.PAUSED)).hasMessage("PLAYBACK_ENDED");
        assertThatThrownBy(() -> playback.renew(viewer, id)).hasMessage("PLAYBACK_ENDED");
        assertThat(history.list(viewer, profile, false, 20, 0)).isEmpty();
        var second = profiles.create(viewer, "Temporary", UUID.randomUUID());
        UUID secondId = playback.admit(viewer, new Admission(second.id(), content.id())).view().sessionId();
        profiles.delete(viewer, second.id(), second.version(), UUID.randomUUID());
        assertThatThrownBy(() -> playback.renew(viewer, secondId)).hasMessage("NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM watch_history WHERE profile_id = ?", Integer.class, second.id())).isZero();
    }

    @Test void historyClearRacingWithProgressAlwaysLeavesHistoryEmpty() throws Exception {
        var content = movie(Tier.FREE);
        UUID id = admit(content).view().sessionId();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { progress(id, 1, 15000, 0, State.PAUSED); }
                catch (com.libra.streaming.core.api.DomainException exception) { assertThat(exception.code()).isEqualTo("PLAYBACK_ENDED"); }
                return true;
            });
            var clear = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); history.clear(viewer, profile, null); return true; });
            assertThat(update.get(20, TimeUnit.SECONDS)).isTrue(); assertThat(clear.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(history.list(viewer, profile, false, 20, 0)).isEmpty();
    }

    @Test void waitingForCatalogLocksCannotRenewOrWriteProgressAfterSessionExpiry() throws Exception {
        for (boolean renewing : List.of(true, false)) {
            var content = movie(Tier.FREE);
            var issued = admit(content);
            UUID id = issued.view().sessionId();
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var writer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    jdbc.queryForObject("SELECT id FROM catalog_contents WHERE id = ? FOR UPDATE", UUID.class, content.id());
                    locked.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) { throw new IllegalStateException("Test lock timed out"); }
                    } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                    return true;
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var blocked = pool.submit(() -> {
                    assertThatThrownBy(() -> {
                        if (renewing) { playback.renew(viewer, id); }
                        else { progress(id, 1, 15000, 0, State.PAUSED); }
                    }).hasMessage("PLAYBACK_EXPIRED");
                    return true;
                });
                try {
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(
                            jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%ORDER BY id FOR SHARE%')", Boolean.class)));
                    clock.advance(300);
                } finally { release.countDown(); }
                assertThat(writer.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(blocked.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(eventCount(id)).isZero();
            assertThat(jdbc.queryForObject("SELECT expires_at FROM playback_sessions WHERE id = ?", Timestamp.class, id).toInstant())
                    .isEqualTo(issued.view().expiresAt());
        }
    }

    @Test void outboxFailureRollsBackSessionAndHistoryAndBrokerOutageDoesNotBlockRetry() {
        UUID id = admit(movie(Tier.FREE)).view().sessionId();
        jdbc.update("""
                INSERT INTO outbox_events(event_id, topic, event_type, schema_version, aggregate_id, aggregate_version,
                    occurred_at, correlation_id, payload) VALUES (?, 'core.playback.v1', 'PlaybackProgressAccepted', 1, ?, 1, ?, ?, '{}')
                """, UUID.randomUUID(), id, Timestamp.from(clock.instant()), UUID.randomUUID());
        assertThatThrownBy(() -> progress(id, 1, 15000, 0, State.PAUSED)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT sequence FROM playback_sessions WHERE id = ?", Long.class, id)).isZero();
        assertThat(history.list(viewer, profile, false, 20, 0).getFirst().positionMs()).isZero();
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", id);
        assertThat(progress(id, 1, 15000, 0, State.PAUSED).accepted()).isTrue();
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ?", String.class, id);
        assertThat(payload).contains("acceptedWatchedMs", "profileId", "sessionId").doesNotContain("ticket", "email", "auth_session", "password");
    }

    @Test void historyIsOwnedPaginatedAndHidesUnpublishedContent() throws Exception {
        var content = movie(Tier.FREE);
        UUID id = admit(content).view().sessionId(); progress(id, 1, 15000, 0, State.PAUSED);
        assertThatThrownBy(() -> history.list(admin, profile, false, 20, 0)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> history.clear(admin, profile, null)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> history.list(viewer, profile, false, 101, 0)).hasMessage("INVALID_REQUEST");
        var response = request(viewer, "GET", "/v1/profiles/" + profile + "/continue-watching", null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(content.id().toString()).doesNotContain("ticket", "accountId", "assetId");
        catalog.unpublish(admin, content.id(), new Version(content.version()), UUID.randomUUID());
        assertThat(history.list(viewer, profile, false, 20, 0)).isEmpty();
    }

    @Test void nextEpisodeSkipsHiddenUnavailableAndPremiumAndCrossesSeasons() {
        var series = publish(create(Kind.SERIES, null, null, Tier.FREE));
        var firstSeason = publish(create(Kind.SEASON, series.id(), 1, Tier.FREE));
        var secondSeason = publish(create(Kind.SEASON, series.id(), 2, Tier.FREE));
        var first = publish(ready(create(Kind.EPISODE, firstSeason.id(), 1, Tier.FREE)));
        create(Kind.EPISODE, firstSeason.id(), 2, Tier.FREE);
        var premium = publish(ready(create(Kind.EPISODE, firstSeason.id(), 3, Tier.PREMIUM)));
        var next = publish(ready(create(Kind.EPISODE, secondSeason.id(), 1, Tier.FREE)));
        assertThat(nextEpisodes.next(viewer, profile, first.id()).contentId()).isEqualTo(next.id());
        subscriptions.activate(viewer, UUID.randomUUID(), "PREMIUM_30_DAYS");
        assertThat(nextEpisodes.next(viewer, profile, first.id()).contentId()).isEqualTo(premium.id());
        assertThat(nextEpisodes.next(viewer, profile, next.id()).contentId()).isNull();
        catalog.unpublish(admin, secondSeason.id(), new Version(secondSeason.version()), UUID.randomUUID());
        assertThat(nextEpisodes.next(viewer, profile, premium.id()).contentId()).isNull();
    }

    @Test void invalidProgressAndEndAreRejectedWithoutOutboxOrHistoryMutation() throws Exception {
        UUID id = admit(movie(Tier.FREE)).view().sessionId();
        for (Progress invalid : List.of(new Progress(0, 0, 0, State.PLAYING), new Progress(1, 120001, 0, State.PLAYING),
                new Progress(1, 0, 30001, State.PLAYING), new Progress(1, 0, 0, null))) {
            assertThatThrownBy(() -> playback.progress(viewer, id, invalid, UUID.randomUUID())).hasMessage("INVALID_REQUEST");
        }
        String body = mapper.writeValueAsString(new Progress(1, 0, 0, State.PLAYING));
        assertThat(request(viewer, "POST", "/v1/playback/sessions/" + id + "/end", body, true).statusCode()).isEqualTo(400);
        assertThat(request(admin, "POST", "/v1/playback/sessions/" + id + "/progress", body, true).statusCode()).isEqualTo(404);
        assertThat(request(viewer, "POST", "/v1/playback/sessions/" + id + "/progress",
                "{\"sequence\":1,\"state\":\"PLAYING\"}", true).statusCode()).isEqualTo(400);
        assertThat(eventCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT sequence FROM playback_sessions WHERE id = ?", Long.class, id)).isZero();
    }

    @Test void migrationUpgradesV4WithoutChangingExistingIdentityAndIsRepeatable() {
        var baseline = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("playback_upgrade").defaultSchema("playback_upgrade").target("4").load();
        baseline.migrate();
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO playback_upgrade.identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Existing', 'inert-fixture', 'USER')",
                accountId, accountId + "@example.test");
        var upgrade = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("playback_upgrade").defaultSchema("playback_upgrade").target("5").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT display_name FROM playback_upgrade.identity_accounts WHERE id = ?", String.class, accountId))
                .isEqualTo("Existing");
    }

    private int eventCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, id); }
    private Issued admit(AdminView content) { return playback.admit(viewer, new Admission(profile, content.id())); }
    private ProgressView progress(UUID id, long sequence, long position, long played, State state) {
        return playback.progress(viewer, id, new Progress(sequence, position, played, state), UUID.randomUUID());
    }
    private IdentityPrincipal actor(boolean verified, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role, email_verified) VALUES (?, ?, 'Viewer', 'inert-fixture', ?, ?)",
                id, id + "@example.test", role, verified);
        new TransactionTemplate(transactions).executeWithoutResult(status -> profiles.initialize(id, "Viewer"));
        return anotherSession(new IdentityPrincipal(id, UUID.randomUUID(), role, verified));
    }
    private IdentityPrincipal anotherSession(IdentityPrincipal actor) {
        UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)", sid,
                actor.accountId(), Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().plusSeconds(3600)));
        return new IdentityPrincipal(actor.accountId(), sid, actor.role(), actor.emailVerified());
    }
    private AdminView create(Kind kind, UUID parent, Integer ordinal, Tier tier) {
        return catalog.create(admin, new Create(kind, parent, ordinal,
                new Metadata("Title", "Description", List.of("drama"), 2026, "en", List.of(), List.of(), tier)));
    }
    private AdminView movie(Tier tier) { return publish(ready(create(Kind.MOVIE, null, null, tier))); }
    private AdminView publish(AdminView content) { return catalog.publish(admin, content.id(), new Version(content.version()), UUID.randomUUID()); }
    private AdminView ready(AdminView content) {
        content = catalog.bind(admin, content.id(), new Bind(content.version(), UUID.randomUUID(), 1));
        readyEvent(content.candidate()); return catalog.get(admin, content.id());
    }
    private void readyEvent(Binding binding) {
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), 1, clock.instant(), UUID.randomUUID(), mapper.valueToTree(new MediaProjectionService.Change(
                        binding.contentId(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.READY, 120))));
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
