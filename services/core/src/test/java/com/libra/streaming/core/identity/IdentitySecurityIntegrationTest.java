package com.libra.streaming.core.identity;

import com.libra.streaming.core.TestIdentityProperties;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentitySecurityIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("identity_test").withUsername("identity_test").withPassword(UUID.randomUUID().toString());
    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.1")
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/readyz").forPort(8025));
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String ADMIN_EMAIL = "bootstrap@example.test";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("libra.identity.bootstrap-email", () -> ADMIN_EMAIL);
        registry.add("libra.identity.bootstrap-password", () -> ADMIN_PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityAccountService accounts;
    @Autowired IdentitySessionService sessions;
    @Autowired IdentityMailQueue mailQueue;
    @Autowired IdentitySecrets secrets;
    @Autowired IdentityProperties properties;
    @Autowired JavaMailSender sender;
    @Autowired JwtEncoder encoder;
    @Autowired IdentityRateLimiter limiter;
    @Autowired javax.sql.DataSource dataSource;
    private HttpClient http;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE identity_accounts, identity_rate_limits CASCADE");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void closeClient() { http.close(); }

    @Test
    void registrationIsGenericAndAtomicWithEncryptedMailAndArgon2() throws Exception {
        var user = user();
        var browser = new Browser();
        var first = browser.post("/auth/register", Map.of("email", user.email(), "password", user.password(), "displayName", "Viewer"));
        var duplicate = browser.post("/auth/register", Map.of("email", user.email().toUpperCase(Locale.ROOT), "password", user.password(), "displayName", "Other"));
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(duplicate.statusCode()).isEqualTo(202);
        assertThat(duplicate.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_accounts", String.class)).startsWith("$argon2id$").doesNotContain(user.password());
        String token = emailToken("VERIFY");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity_email_tokens", String.class)).isEqualTo(IdentitySecrets.hash(token));
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM identity_mail_queue", String.class))
                .doesNotContain(token, user.email());
        assertThat(jdbc.queryForObject("SELECT role FROM identity_accounts", String.class)).isEqualTo("USER");
    }

    @Test
    void registrationRejectsRoleInjectionAndWeakPasswordWithoutSideEffects() throws Exception {
        var browser = new Browser();
        var user = user();
        assertThat(browser.post("/auth/register", Map.of("email", user.email(), "password", user.password(),
                "displayName", "Viewer", "role", "ADMIN")).statusCode()).isEqualTo(400);
        assertThat(browser.post("/auth/register", Map.of("email", user.email(), "password", "short",
                "displayName", "Viewer")).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_mail_queue", Integer.class)).isZero();
    }

    @Test
    void loginAllowsUnverifiedAndCookiesAreHttpOnlyWithNoTokensInBody() throws Exception {
        var user = registered();
        var browser = new Browser();
        var response = browser.login(user);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).contains("LIBRA_ACCESS=", "HttpOnly", "SameSite=Lax", "Path=/"));
        assertThat(response.body()).doesNotContain(browser.cookies.get("LIBRA_ACCESS"), browser.cookies.get("LIBRA_REFRESH"));
        var me = browser.get("/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("emailVerified").asBoolean()).isFalse();
        assertThat(me.body()).doesNotContain("password", "token_hash");
        assertThat(browser.post("/admin/users/" + UUID.randomUUID() + "/suspension", null).statusCode()).isEqualTo(403);
    }

    @Test
    void loginRefreshAndLogoutRequireCsrfAndOldHeaderFailsAfterRotation() throws Exception {
        var user = registered();
        var browser = new Browser();
        assertThat(browser.send("POST", "/auth/login", Map.of("email", user.email(), "password", user.password()), null).statusCode()).isEqualTo(403);
        String beforeLogin = browser.csrf();
        assertThat(browser.send("POST", "/auth/login", Map.of("email", user.email(), "password", user.password()), beforeLogin).statusCode()).isEqualTo(200);
        browser.csrf();
        assertThat(browser.send("POST", "/auth/refresh", null, beforeLogin).statusCode()).isEqualTo(403);
        assertThat(browser.send("POST", "/auth/logout", null, null).statusCode()).isEqualTo(403);
        assertThat(browser.post("/auth/refresh", null).statusCode()).isEqualTo(200);
    }

    @Test
    void refreshRotatesAndReplayRevokesTheWholeSession() throws Exception {
        var browser = new Browser();
        browser.login(registered());
        String oldRefresh = browser.cookies.get("LIBRA_REFRESH");
        assertThat(browser.post("/auth/refresh", null).statusCode()).isEqualTo(200);
        String newRefresh = browser.cookies.get("LIBRA_REFRESH");
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        Browser attacker = browser.copy();
        attacker.cookies.put("LIBRA_REFRESH", oldRefresh);
        assertThat(attacker.post("/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
        assertThat(browser.post("/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_audit WHERE action = 'REFRESH_REPLAY_REVOKED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshHasOneWinnerAndReplayRevokesItsSession() throws Exception {
        var browser = new Browser();
        browser.login(registered());
        var token = browser.cookies.get("LIBRA_REFRESH");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { barrier.await(); return sessions.refresh(token); });
            var two = executor.submit(() -> { barrier.await(); return sessions.refresh(token); });
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).isPresent(), two.get(15, TimeUnit.SECONDS).isPresent()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
    }

    @Test
    void wrongAudienceIssuerExpiredPurposeMissingClaimAndTamperingAreDenied() throws Exception {
        var browser = new Browser();
        browser.login(registered());
        var row = jdbc.queryForMap("SELECT id, account_id FROM identity_sessions");
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (String variant : List.of("audience", "issuer", "expired", "purpose", "sid", "tampered", "expiry")) {
            var builder = JwtClaimsSet.builder().issuer(variant.equals("issuer") ? "other" : properties.issuer())
                    .audience(List.of(variant.equals("audience") ? "other" : properties.audience()))
                    .subject(row.get("account_id").toString()).issuedAt(now.minusSeconds(60))
                    .claim("purpose", variant.equals("purpose") ? "media" : "access");
            if (!variant.equals("sid")) { builder.claim("sid", row.get("id").toString()); }
            if (!variant.equals("expiry")) { builder.expiresAt(variant.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(300)); }
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
            if (variant.equals("tampered")) {
                String[] parts = token.split("\\.");
                var claims = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
                claims.put("sub", UUID.randomUUID().toString());
                token = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims)) + "." + parts[2];
            }
            browser.cookies.put("LIBRA_ACCESS", token);
            assertThat(browser.get("/me").statusCode()).as(variant).isEqualTo(401);
        }
    }

    @Test
    void sessionOwnershipRevokeAndGlobalLogoutInvalidateExistingAccess() throws Exception {
        var user = registered();
        var first = new Browser(); first.login(user);
        var second = new Browser(); second.login(user);
        var stranger = new Browser(); stranger.login(registered());
        UUID target = jdbc.queryForObject("SELECT session_id FROM identity_refresh_tokens WHERE token_hash = ?",
                UUID.class, IdentitySecrets.hash(first.cookies.get("LIBRA_REFRESH")));
        assertThat(stranger.delete("/me/sessions/" + target).statusCode()).isEqualTo(404);
        assertThat(second.delete("/me/sessions/" + target).statusCode()).isEqualTo(204);
        assertThat(first.get("/me").statusCode()).isEqualTo(401);
        assertThat(second.get("/me/sessions?limit=101").statusCode()).isEqualTo(400);
        assertThat(second.post("/auth/logout-all", null).statusCode()).isEqualTo(204);
        assertThat(second.get("/me").statusCode()).isEqualTo(401);
        assertThat(stranger.get("/me").statusCode()).isEqualTo(200);
    }

    @Test
    void changePasswordRejectsWrongCurrentAndRevokesAllSessionsOnSuccess() throws Exception {
        var user = registered();
        var first = new Browser(); first.login(user);
        var second = new Browser(); second.login(user);
        String next = UUID.randomUUID().toString();
        assertThat(first.post("/auth/change-password", Map.of("currentPassword", "wrong", "newPassword", next)).statusCode()).isEqualTo(401);
        assertThat(second.get("/me").statusCode()).isEqualTo(200);
        assertThat(first.post("/auth/change-password", Map.of("currentPassword", user.password(), "newPassword", next)).statusCode()).isEqualTo(204);
        assertThat(second.get("/me").statusCode()).isEqualTo(401);
        assertThat(second.login(user).statusCode()).isEqualTo(401);
        assertThat(second.login(new User(user.email(), next)).statusCode()).isEqualTo(200);
    }

    @Test
    void verificationIsSingleUseAndCannotUseResetToken() throws Exception {
        var user = registered();
        String verify = emailToken("VERIFY");
        var browser = new Browser(); browser.login(user);
        assertThat(browser.post("/auth/verify-email", Map.of("token", verify)).statusCode()).isEqualTo(204);
        assertThat(mapper.readTree(browser.get("/me").body()).path("emailVerified").asBoolean()).isTrue();
        assertThat(browser.post("/auth/verify-email", Map.of("token", verify)).statusCode()).isEqualTo(400);
        browser.post("/auth/forgot-password", Map.of("email", user.email()));
        assertThat(browser.post("/auth/verify-email", Map.of("token", emailToken("RESET"))).statusCode()).isEqualTo(400);
    }

    @Test
    void forgotIsGenericAndResetRevokesAllWithoutVerifyingOrReactivating() throws Exception {
        var user = registered();
        var browser = new Browser(); browser.login(user);
        var response = browser.post("/auth/forgot-password", Map.of("email", user.email()));
        var unknown = browser.post("/auth/forgot-password", Map.of("email", "missing@example.test"));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(unknown.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo(unknown.body());
        String token = emailToken("RESET");
        jdbc.update("UPDATE identity_accounts SET status = 'SUSPENDED'");
        String next = UUID.randomUUID().toString();
        assertThat(browser.post("/auth/reset-password", Map.of("token", token, "newPassword", next)).statusCode()).isEqualTo(204);
        assertThat(browser.post("/auth/reset-password", Map.of("token", token, "newPassword", next)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM identity_accounts", String.class)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT email_verified FROM identity_accounts", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_sessions WHERE revoked_at IS NULL", Integer.class)).isZero();
        assertThat(browser.login(new User(user.email(), next)).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredTokensAndResentLinksDoNotMutateAccount() throws Exception {
        var user = registered();
        String old = emailToken("VERIFY");
        var browser = new Browser();
        assertThat(browser.post("/auth/resend-verification", Map.of("email", user.email())).statusCode()).isEqualTo(202);
        assertThat(browser.post("/auth/verify-email", Map.of("token", old)).statusCode()).isEqualTo(400);
        String current = emailToken("VERIFY");
        jdbc.update("UPDATE identity_email_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThat(browser.post("/auth/verify-email", Map.of("token", current)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT email_verified FROM identity_accounts", Boolean.class)).isFalse();
        browser.post("/auth/forgot-password", Map.of("email", user.email()));
        String reset = emailToken("RESET");
        jdbc.update("UPDATE identity_email_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThat(browser.post("/auth/reset-password", Map.of("token", reset, "newPassword", UUID.randomUUID().toString())).statusCode()).isEqualTo(400);
        assertThat(browser.login(user).statusCode()).isEqualTo(200);
    }

    @Test
    void bootstrapIsOnceOnlyAndSuspensionRevokesSessions() throws Exception {
        accounts.bootstrapAdministrator();
        accounts.bootstrapAdministrator();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
        var admin = new Browser(); admin.login(new User(ADMIN_EMAIL, ADMIN_PASSWORD));
        assertThat(mapper.readTree(admin.get("/me").body()).path("emailVerified").asBoolean()).isFalse();
        var user = registered();
        var browser = new Browser(); browser.login(user);
        UUID id = jdbc.queryForObject("SELECT id FROM identity_accounts WHERE email = ?", UUID.class, user.email());
        assertThat(admin.post("/admin/users/" + id + "/suspension", null).statusCode()).isEqualTo(204);
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
        assertThat(browser.post("/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(browser.login(user).statusCode()).isEqualTo(401);
    }

    @Test
    void loginRateLimitPersistsAcrossFailuresAndConcurrentChecksAreBounded() throws Exception {
        var browser = new Browser();
        User missing = user();
        for (int i = 0; i < 10; i++) { assertThat(browser.login(missing).statusCode()).isEqualTo(401); }
        var denied = browser.login(missing);
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.headers().firstValue("Retry-After")).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isZero();
        CyclicBarrier barrier = new CyclicBarrier(4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                barrier.await();
                try { limiter.check("race", "key", 2, Duration.ofHours(1)); return true; }
                catch (IdentityException exception) { return false; }
            })).toList();
            int accepted = 0;
            for (var task : tasks) { if (task.get(10, TimeUnit.SECONDS)) { accepted++; } }
            assertThat(accepted).isEqualTo(2);
        }
    }

    @Test
    void mailWorkerCapturesRealSmtpAndPurgesSensitivePayload() throws Exception {
        var user = registered();
        String token = emailToken("VERIFY");
        worker(sender).deliverBatch();
        assertThat(jdbc.queryForObject("SELECT state FROM identity_mail_queue", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT encrypted_payload IS NULL FROM identity_mail_queue", Boolean.class)).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var response = http.send(HttpRequest.newBuilder(URI.create(mailpit() + "/api/v1/messages")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).contains(user.email());
            var messages = mapper.readTree(response.body()).path("messages");
            for (var message : messages) {
                if (message.toString().contains(user.email())) {
                    String id = message.path("ID").asString();
                    var detail = http.send(HttpRequest.newBuilder(URI.create(mailpit() + "/api/v1/message/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(detail.body()).contains(token);
                    return;
                }
            }
            fail("Synthetic message not captured");
        });
    }

    @Test
    void abandonedMailLeaseIsRecoveredAndStaleOwnerCannotAcknowledge() {
        registered();
        var first = mailQueue.claim().getFirst();
        assertThat(mailQueue.claim()).isEmpty();
        jdbc.update("UPDATE identity_mail_queue SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var recovered = mailQueue.claim().getFirst();
        assertThat(recovered.lease()).isNotEqualTo(first.lease());
        mailQueue.complete(first);
        assertThat(jdbc.queryForObject("SELECT state FROM identity_mail_queue", String.class)).isEqualTo("PROCESSING");
        mailQueue.complete(recovered);
        assertThat(jdbc.queryForObject("SELECT state FROM identity_mail_queue", String.class)).isEqualTo("SENT");
    }

    @Test
    void mailFailureRetriesBoundedlyAndExpiredMailNeverSends() {
        registered();
        var unavailable = new org.springframework.mail.javamail.JavaMailSenderImpl();
        unavailable.setHost("127.0.0.1"); unavailable.setPort(1);
        unavailable.getJavaMailProperties().put("mail.smtp.connectiontimeout", "1000");
        for (int attempt = 0; attempt < 5; attempt++) {
            worker(unavailable).deliverBatch();
            jdbc.update("UPDATE identity_mail_queue SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM identity_mail_queue", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT state FROM identity_mail_queue", String.class)).isEqualTo("DEAD");
        assertThat(mailQueue.claim()).isEmpty();
        jdbc.update("UPDATE identity_mail_queue SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertThat(mailQueue.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT encrypted_payload IS NULL FROM identity_mail_queue", Boolean.class)).isTrue();
    }

    private IdentityMailWorker worker(JavaMailSender mail) { return new IdentityMailWorker(mailQueue, secrets, mapper, mail, properties); }

    @Test
    void activeUnverifiedAccountCanResetAndConcurrentConsumptionHasOneWinner() throws Exception {
        var user = registered();
        accounts.requestEmail(user.email(), "RESET");
        String token = emailToken("RESET");
        String next = UUID.randomUUID().toString();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                barrier.await();
                try { accounts.resetPassword(token, next); return true; }
                catch (IdentityException exception) { return false; }
            })).toList();
            assertThat(List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(new Browser().login(new User(user.email(), next)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT email_verified FROM identity_accounts", Boolean.class)).isFalse();
    }

    @Test
    void expiredSessionDeniesAccessAndRefreshWhileLogoutCanClearExpiredAccessCookie() throws Exception {
        var browser = new Browser(); browser.login(registered());
        jdbc.update("UPDATE identity_sessions SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days', expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day'");
        assertThat(browser.get("/me").statusCode()).isEqualTo(401);
        var logout = browser.copy();
        logout.cookies.put("LIBRA_ACCESS", "invalid-access-cookie");
        assertThat(logout.post("/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(logout.cookies).doesNotContainKeys("LIBRA_ACCESS", "LIBRA_REFRESH");
        assertThat(browser.post("/auth/refresh", null).statusCode()).isEqualTo(401);
    }

    @Test
    void bootstrapRefusesToPromoteAnExistingRegisteredUser() {
        accounts.register(ADMIN_EMAIL, "Viewer", UUID.randomUUID().toString());
        assertThatThrownBy(accounts::bootstrapAdministrator).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT role FROM identity_accounts", String.class)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_bootstrap", Integer.class)).isZero();
    }

    @Test
    void failedMailEnqueueRollsBackRegistrationAndToken() {
        jdbc.execute("CREATE FUNCTION fail_test_mail() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic queue failure''; END'");
        jdbc.execute("CREATE TRIGGER fail_test_mail BEFORE INSERT ON identity_mail_queue FOR EACH ROW EXECUTE FUNCTION fail_test_mail()");
        try {
            assertThatThrownBy(this::registered).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_email_tokens", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_test_mail ON identity_mail_queue");
            jdbc.execute("DROP FUNCTION fail_test_mail()");
        }
    }

    @Test
    void corruptMailPayloadIsQuarantinedWithoutSending() {
        registered();
        jdbc.update("UPDATE identity_mail_queue SET encrypted_payload = 'invalid' ");
        worker(sender).deliverBatch();
        assertThat(jdbc.queryForObject("SELECT state FROM identity_mail_queue", String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM identity_mail_queue", String.class)).isEqualTo("INVALID_ENCRYPTED_PAYLOAD");
    }

    @Test
    void cleanupPreservesRateLimitBucketRefreshedByConcurrentRequest() throws Exception {
        String key = secrets.fingerprint("cleanup-race:key");
        jdbc.update("INSERT INTO identity_rate_limits (bucket_key, window_start, attempts) VALUES (?, CURRENT_TIMESTAMP - INTERVAL '2 days', 1)", key);
        try (var connection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            int updaterPid;
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                updaterPid = result.getInt(1);
            }
            try (var update = connection.prepareStatement("UPDATE identity_rate_limits SET window_start = CURRENT_TIMESTAMP, attempts = 2 WHERE bucket_key = ?")) {
                update.setString(1, key);
                update.executeUpdate();
            }
            var cleanup = executor.submit(mailQueue::claim);
            try {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))",
                        Integer.class, updaterPid)).isGreaterThan(0));
            } finally {
                // Release the lock even if the synchronization assertion fails.
                connection.commit();
            }
            cleanup.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_rate_limits WHERE bucket_key = ?", Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM identity_rate_limits WHERE bucket_key = ?", Integer.class, key)).isEqualTo(2);
    }

    @Test
    void cleanupRemovesOnlyOldRateLimitBucketsWithinTheBatchLimit() {
        jdbc.update("""
                INSERT INTO identity_rate_limits (bucket_key, window_start, attempts)
                SELECT lpad(i::text, 64, '0'), CURRENT_TIMESTAMP - INTERVAL '2 days', 1
                FROM generate_series(1, 501) AS i
                """);
        limiter.check("active", "key", 3, Duration.ofHours(1));
        mailQueue.claim();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_rate_limits WHERE window_start < CURRENT_TIMESTAMP - INTERVAL '1 day'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM identity_rate_limits WHERE bucket_key = ?", Integer.class,
                secrets.fingerprint("active:key"))).isEqualTo(1);
    }
    private String mailpit() { return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025); }
    private User user() { return new User("viewer-" + UUID.randomUUID() + "@example.test", UUID.randomUUID().toString()); }
    private User registered() {
        User user = user();
        accounts.register(user.email(), "Viewer", user.password());
        return user;
    }
    private String emailToken(String purpose) {
        var row = jdbc.queryForMap("SELECT id, encrypted_payload FROM identity_mail_queue WHERE purpose = ? AND state = 'PENDING' ORDER BY created_at DESC LIMIT 1", purpose);
        String plaintext = secrets.decrypt((UUID) row.get("id"), (String) row.get("encrypted_payload"));
        var matcher = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(plaintext);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
    private record User(String email, String password) {
        @Override public String toString() { return "TestUser[redacted]"; }
    }

    private class Browser {
        final Map<String, String> cookies = new HashMap<>();
        Browser copy() { var copy = new Browser(); copy.cookies.putAll(cookies); return copy; }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        String csrf() throws Exception {
            var response = get("/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body()).path("token").asString();
        }
        HttpResponse<String> post(String path, Object body) throws Exception { return send("POST", path, body, csrf()); }
        HttpResponse<String> delete(String path) throws Exception { return send("DELETE", path, null, csrf()); }
        HttpResponse<String> login(User user) throws Exception { return post("/auth/login", Map.of("email", user.email(), "password", user.password())); }
        HttpResponse<String> send(String method, String path, Object body, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
            if (!cookies.isEmpty()) { request.header("Cookie", cookies.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("; "))); }
            if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                for (var cookie : HttpCookie.parse(header)) {
                    if (cookie.getMaxAge() == 0) { cookies.remove(cookie.getName()); }
                    else { cookies.put(cookie.getName(), cookie.getValue()); }
                }
            }
            return response;
        }
    }
}
