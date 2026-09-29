package com.libra.streaming.core.subscriptions;

import com.libra.streaming.core.subscriptions.infrastructure.SubscriptionService;
import com.libra.streaming.core.subscriptions.application.SubscriptionOperations;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.net.HttpCookie;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SubscriptionIntegrationTest.TimeConfiguration.class)
class SubscriptionIntegrationTest {
    private static final String PLAN = "PREMIUM_30_DAYS";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("subscription_test").withUsername("subscription_test").withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock subscriptionTestClock() {
            return Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SubscriptionService subscriptions;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @LocalServerPort int port;
    IdentityPrincipal viewer;
    HttpClient http;

    @BeforeEach void setup() {
        viewer = actor(true, "USER");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void close() { http.close(); }

    @Test void upgradeFromMilestoneThreePreservesAccountsWithoutGrantingPremium() {
        var baseline = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("subscription_upgrade").defaultSchema("subscription_upgrade").target("3").load();
        baseline.migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO subscription_upgrade.identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Existing', 'inert-fixture', 'USER')", id, id + "@example.test");
        var upgrade = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("subscription_upgrade").defaultSchema("subscription_upgrade").target("4").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_upgrade.identity_accounts WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_upgrade.subscriptions", Integer.class)).isZero();
    }

    @Test void initialPurchaseReplayAndIntentionalRepurchaseHaveStableReceipts() {
        assertThat(subscriptions.status(viewer)).isEqualTo(new SubscriptionOperations.StatusView("NONE", null, true));
        UUID key = UUID.randomUUID();
        var first = subscriptions.activate(viewer, key, PLAN);
        assertThat(first.simulated()).isTrue();
        assertThat(first.termStart()).isEqualTo(clock.instant());
        assertThat(first.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        var second = subscriptions.activate(viewer, UUID.randomUUID(), PLAN);
        assertThat(second.termStart()).isEqualTo(first.expiresAt());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt().plus(Duration.ofDays(30)));
        assertThat(subscriptions.activate(viewer, key, PLAN)).isEqualTo(first);
        assertThat(subscriptions.status(viewer).expiresAt()).isEqualTo(second.expiresAt());
        assertThat(subscriptions.purchases(viewer, 20, 0)).hasSize(2);
    }

    @Test void expiryBoundaryAndExpiredRepurchaseUseServerTime() {
        var first = subscriptions.activate(viewer, UUID.randomUUID(), PLAN);
        jdbc.update("UPDATE subscriptions SET expires_at = ? WHERE account_id = ?",
                Timestamp.from(clock.instant().plus(1, ChronoUnit.MICROS)), viewer.accountId());
        assertThat(subscriptions.status(viewer).status()).isEqualTo("ACTIVE");
        jdbc.update("UPDATE subscriptions SET expires_at = ? WHERE account_id = ?", Timestamp.from(clock.instant()), viewer.accountId());
        assertThat(subscriptions.status(viewer).status()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE subscriptions SET expires_at = ? WHERE account_id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(3))), viewer.accountId());
        var renewed = subscriptions.activate(viewer, UUID.randomUUID(), PLAN);
        assertThat(renewed.termStart()).isEqualTo(clock.instant());
        assertThat(renewed.expiresAt()).isEqualTo(first.expiresAt());
    }

    @Test void concurrentSameKeyCommitsOnePurchaseAndIdenticalResults() throws Exception {
        UUID key = UUID.randomUUID();
        var results = race(() -> subscriptions.activate(viewer, key, PLAN), 4);
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(subscriptions.purchases(viewer, 20, 0)).hasSize(1);
        assertThat(subscriptions.status(viewer).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test void concurrentDistinctKeysAccumulateEveryTerm() throws Exception {
        var results = race(() -> subscriptions.activate(viewer, UUID.randomUUID(), PLAN), 4);
        assertThat(new HashSet<>(results)).hasSize(4);
        assertThat(subscriptions.status(viewer).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(120)));
        assertThat(subscriptions.purchases(viewer, 20, 0)).hasSize(4);
    }

    @Test void failedReceiptInsertRollsBackExtensionAndKeyRemainsRetryable() {
        var first = subscriptions.activate(viewer, UUID.randomUUID(), PLAN);
        UUID key = UUID.randomUUID();
        // Deliberate database failure after the expiry UPDATE, in a disposable test database.
        jdbc.execute("ALTER TABLE subscription_purchases ADD CONSTRAINT reject_test_purchase CHECK (idempotency_key <> '" + key + "'::uuid)");
        try {
            assertThatThrownBy(() -> subscriptions.activate(viewer, key, PLAN))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(subscriptions.status(viewer).expiresAt()).isEqualTo(first.expiresAt());
            assertThat(subscriptions.purchases(viewer, 20, 0)).hasSize(1);
        } finally { jdbc.execute("ALTER TABLE subscription_purchases DROP CONSTRAINT reject_test_purchase"); }
        assertThat(subscriptions.activate(viewer, key, PLAN).expiresAt()).isEqualTo(first.expiresAt().plus(Duration.ofDays(30)));
    }

    @Test void currentVerificationIsAuthoritativeAndAdminHasNoBypass() throws Exception {
        var unverified = actor(false, "USER");
        var staleVerified = new IdentityPrincipal(unverified.accountId(), unverified.sessionId(), "USER", true);
        assertThatThrownBy(() -> subscriptions.activate(staleVerified, UUID.randomUUID(), PLAN)).hasMessage("EMAIL_VERIFICATION_REQUIRED");
        Browser browser = new Browser(unverified);
        assertThat(browser.send("GET", "", null, false, null).statusCode()).isEqualTo(200);
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN), true, UUID.randomUUID().toString()).body()).contains("EMAIL_VERIFICATION_REQUIRED");
        assertThat(subscriptions.purchases(unverified, 20, 0)).isEmpty();
        var admin = actor(false, "ADMIN");
        assertThatThrownBy(() -> subscriptions.activate(admin, UUID.randomUUID(), PLAN)).hasMessage("EMAIL_VERIFICATION_REQUIRED");
        jdbc.update("UPDATE identity_accounts SET email_verified = TRUE WHERE id = ?", unverified.accountId());
        assertThat(subscriptions.activate(unverified, UUID.randomUUID(), PLAN).simulated()).isTrue();
    }

    @Test void httpEnforcesAuthenticationCsrfPayloadBindingAndPrivateResponses() throws Exception {
        Browser anonymous = new Browser(null);
        assertThat(anonymous.send("GET", "", null, false, null).statusCode()).isEqualTo(401);
        assertThat(anonymous.send("POST", "/simulate", Map.of("plan", PLAN), true, UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
        Browser browser = new Browser(viewer);
        String key = UUID.randomUUID().toString();
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN), false, key).statusCode()).isEqualTo(403);
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN), true, null).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN), true, "invalid").statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN, "expiresAt", "2099-01-01T00:00:00Z"), true, key).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", "/simulate", Map.of("plan", "OTHER"), true, key).statusCode()).isEqualTo(400);
        assertThat(subscriptions.purchases(viewer, 20, 0)).isEmpty();
        var created = browser.send("POST", "/simulate", Map.of("plan", PLAN), true, key);
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(created.body()).contains("\"simulated\":true").doesNotContain(viewer.accountId().toString(), "email", "idempotency");
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN), true, key).body()).isEqualTo(created.body());
        var conflict = browser.send("POST", "/simulate", Map.of("plan", "OTHER"), true, key);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("IDEMPOTENCY_CONFLICT");
        assertThat(subscriptions.purchases(viewer, 20, 0)).hasSize(1);
    }

    @Test void historyAndKeysAreAccountScopedWithBoundedStablePagination() throws Exception {
        UUID key = UUID.randomUUID();
        var own = subscriptions.activate(viewer, key, PLAN);
        var other = actor(true, "USER");
        assertThat(subscriptions.status(other).status()).isEqualTo("NONE");
        assertThat(subscriptions.purchases(other, 20, 0)).isEmpty();
        var foreign = subscriptions.activate(other, key, PLAN);
        assertThat(foreign.id()).isNotEqualTo(own.id());
        subscriptions.activate(viewer, UUID.randomUUID(), PLAN);
        var all = subscriptions.purchases(viewer, 20, 0);
        assertThat(all).hasSize(2).doesNotContain(foreign);
        assertThat(subscriptions.purchases(viewer, 1, 0)).containsExactly(all.get(0));
        assertThat(subscriptions.purchases(viewer, 1, 1)).containsExactly(all.get(1));
        Browser browser = new Browser(viewer);
        assertThat(browser.send("GET", "/purchases", null, false, null).body()).doesNotContain(foreign.id().toString());
        for (String query : List.of("?limit=101", "?limit=0", "?offset=-1", "?offset=10001")) {
            assertThat(browser.send("GET", "/purchases" + query, null, false, null).statusCode()).isEqualTo(400);
        }
    }

    @Test void revokedExpiredAndSuspendedSessionsCannotPurchaseOrReplay() throws Exception {
        UUID key = UUID.randomUUID();
        subscriptions.activate(viewer, key, PLAN);
        Browser browser = new Browser(viewer);
        jdbc.update("UPDATE identity_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", viewer.sessionId());
        assertThat(browser.send("POST", "/simulate", Map.of("plan", PLAN), true, key.toString()).statusCode()).isEqualTo(401);
        assertThatThrownBy(() -> subscriptions.activate(viewer, key, PLAN)).hasMessage("INVALID_CREDENTIALS");
        for (String state : List.of("SUSPENDED", "EXPIRED")) {
            var actor = actor(true, "USER");
            if (state.equals("SUSPENDED")) {
                jdbc.update("UPDATE identity_accounts SET status = 'SUSPENDED' WHERE id = ?", actor.accountId());
            } else {
                jdbc.update("UPDATE identity_sessions SET expires_at = ? WHERE id = ?", Timestamp.from(clock.instant()), actor.sessionId());
            }
            assertThat(new Browser(actor).send("POST", "/simulate", Map.of("plan", PLAN), true, UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
            assertThatThrownBy(() -> subscriptions.activate(actor, UUID.randomUUID(), PLAN)).hasMessage("INVALID_CREDENTIALS");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_purchases WHERE account_id = ?", Integer.class, actor.accountId())).isZero();
        }
    }

    private IdentityPrincipal actor(boolean verified, String role) {
        UUID id = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role, email_verified) VALUES (?, ?, 'Viewer', 'inert-fixture', ?, ?)",
                id, id + "@example.test", role, verified);
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                sid, id, Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().plusSeconds(3600)));
        return new IdentityPrincipal(id, sid, role, verified);
    }

    private List<SubscriptionOperations.PurchaseView> race(Callable<SubscriptionOperations.PurchaseView> action, int threads) throws Exception {
        var barrier = new CyclicBarrier(threads);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Future<SubscriptionOperations.PurchaseView>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return action.call(); }));
            }
            List<SubscriptionOperations.PurchaseView> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        }
    }

    private class Browser {
        final Map<String, String> cookies = new HashMap<>();
        Browser(IdentityPrincipal principal) {
            if (principal != null) {
                var claims = JwtClaimsSet.builder().issuer("libra-core").audience(List.of("libra-web"))
                        .subject(principal.accountId().toString()).claim("sid", principal.sessionId().toString())
                        .claim("purpose", "access").issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
                cookies.put("LIBRA_ACCESS", encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
            }
        }
        HttpResponse<String> send(String method, String path, Object body, boolean csrf, String key) throws Exception {
            String token = csrf ? mapper.readTree(request("GET", "/v1/auth/csrf", null, null, null).body()).path("token").asString() : null;
            return request(method, "/v1/subscriptions" + path, body, token, key);
        }
        private HttpResponse<String> request(String method, String path, Object body, String token, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json");
            if (!cookies.isEmpty()) { request.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; "))); }
            if (token != null) { request.header("X-CSRF-TOKEN", token); }
            if (key != null) { request.header("Idempotency-Key", key); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                for (var cookie : HttpCookie.parse(header)) { cookies.put(cookie.getName(), cookie.getValue()); }
            }
            return response;
        }
    }
}
