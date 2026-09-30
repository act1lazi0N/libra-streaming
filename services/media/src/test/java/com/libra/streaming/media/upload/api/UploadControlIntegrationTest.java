package com.libra.streaming.media.upload.api;

import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import com.libra.streaming.media.upload.application.StagingInspector;
import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.application.UploadGrantSigner;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production Media filters and PostgreSQL, with an authenticated loopback Core binding fixture. */
@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
class UploadControlIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_control").withUsername("media_control").withPassword(UUID.randomUUID().toString());
    static final KeyPair CORE = pair(), MEDIA = pair();
    static final AtomicInteger BINDING_READS = new AtomicInteger();
    static volatile MediaPersistenceService.Ensure command;
    static volatile boolean candidate = true;
    static volatile boolean unavailable;
    static final HttpServer BINDINGS = bindingServer();
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.libra.streaming.media.processing.application.JobLeases leases;
    @MockitoBean StagingInspector staging;
    @MockitoSpyBean UploadGrantSigner signer;
    MockMvc mvc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("libra.media.core-service.enabled", () -> true);
        registry.add("libra.media.core-service.public-key", () -> key(CORE));
        registry.add("libra.media.core-service.key-id", () -> "core-control-test");
        registry.add("libra.media.core-binding.enabled", () -> true);
        registry.add("libra.media.core-binding.base-url", () -> "http://127.0.0.1:" + BINDINGS.getAddress().getPort());
        registry.add("libra.media.core-binding.private-key", () -> Base64.getEncoder().encodeToString(MEDIA.getPrivate().getEncoded()));
        registry.add("libra.media.core-binding.public-key", () -> key(MEDIA));
        registry.add("libra.media.core-binding.key-id", () -> "media-binding-test");
        registry.add("libra.media.core-binding.allow-http", () -> true);
    }

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE media_assets CASCADE");
        BINDING_READS.set(0);
        candidate = true;
        unavailable = false;
        command = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64),
                Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3500));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll static void stop() { BINDINGS.stop(0); }

    @Test void workerStatusExposesAuthoritativeAttemptAndSanitizedPermanentFailure() throws Exception {
        assertThat(ensure(body(command.byteLength())).getResponse().getStatus()).isEqualTo(201);
        assertThat(complete().getResponse().getStatus()).isEqualTo(202);
        var lease = leases.claim(java.time.Duration.ofSeconds(30)).orElseThrow();
        String path = "/internal/v1/uploads/" + command.uploadId();
        mvc.perform(get(path).header("Authorization", "Bearer " + token("core.media.uploads:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetState").value("PROCESSING"))
                .andExpect(jsonPath("$.attemptCount").value(1)).andExpect(jsonPath("$.failureCode").isEmpty());
        assertThat(leases.fail(lease, com.libra.streaming.media.processing.domain.ProcessingFailure.CORRUPT_INPUT)).isTrue();
        mvc.perform(get(path).header("Authorization", "Bearer " + token("core.media.uploads:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetState").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CORRUPT_INPUT"))
                .andExpect(jsonPath("$.jobId").value(lease.jobId().toString()));
        var duplicate = complete().getResponse();
        assertThat(duplicate.getStatus()).isEqualTo(202);
        assertThat(mapper.readTree(duplicate.getContentAsString()).get("failureCode").asText()).isEqualTo("CORRUPT_INPUT");
        assertThat(count("media_jobs")).isEqualTo(1);
    }

    @Test void exactCandidateProvisionsOnceAndChangedIdentityConflicts() throws Exception {
        String path = "/internal/v1/uploads/" + command.uploadId();
        String json = mapper.writeValueAsString(new UploadControlController.EnsureUpload(command.requestId(),
                command.contentId(), command.bindingId(), command.assetId(), command.assetVersion(),
                command.byteLength(), command.sha256(), command.expiresAt()));
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.uploadState").value("OPEN"))
                .andExpect(jsonPath("$.assetState").value("UPLOADING"));
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uploadId").value(command.uploadId().toString()));
        mvc.perform(get(path).header("Authorization", "Bearer " + token("core.media.uploads:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bindingId").value(command.bindingId().toString()));
        assertThat(BINDING_READS.get()).isEqualTo(2);
        assertThat(count("media_uploads")).isEqualTo(1);
        assertThat(count("media_assets")).isEqualTo(1);
        assertThat(count("media_jobs")).isZero();
        assertThat(count("media_outbox_events")).isZero();

        candidate = false;
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isNotFound());
        assertThat(count("media_uploads")).isEqualTo(1);
        candidate = true;

        String changed = mapper.writeValueAsString(new UploadControlController.EnsureUpload(command.requestId(),
                command.contentId(), command.bindingId(), command.assetId(), command.assetVersion(),
                2048, command.sha256(), command.expiresAt()));
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count("media_uploads")).isEqualTo(1);
    }

    @Test void simultaneousIdenticalEnsuresShareOneUploadAndAsset() throws Exception {
        String body = body(command.byteLength());
        var results = race(() -> ensure(body), () -> ensure(body));
        assertThat(results).extracting(MvcResult::getResponse)
                .extracting(response -> response.getStatus())
                .allSatisfy(status -> assertThat(status).isIn(200, 201));
        assertThat(results.get(0).getResponse().getContentAsString())
                .contains(command.uploadId().toString());
        assertThat(results.get(1).getResponse().getContentAsString())
                .contains(command.uploadId().toString());
        assertThat(count("media_uploads")).isEqualTo(1);
        assertThat(count("media_assets")).isEqualTo(1);
        assertThat(count("media_jobs")).isZero();
    }

    @Test void uploadUrlRequiresWriteScopeAndCurrentCandidateBeforeStorageAccess() throws Exception {
        ensure(body(command.byteLength()));
        String path = "/internal/v1/uploads/" + command.uploadId() + "/upload-url";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:read")))
                .andExpect(status().isForbidden());
        candidate = false;
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isNotFound());
        candidate = true;
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        assertThat(count("media_uploads")).isEqualTo(1);
        assertThat(count("media_jobs")).isZero();
    }

    @Test void completionRequiresStorageAndCurrentBindingThenQueuesOneDurableJob() throws Exception {
        ensure(body(command.byteLength()));
        String path = "/internal/v1/uploads/" + command.uploadId() + "/complete";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:read")))
                .andExpect(status().isForbidden());
        candidate = false;
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isNotFound());
        candidate = true;
        doThrow(new UploadFailure("SOURCE_MISSING")).when(staging).requireComplete(any());
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_MISSING"));
        assertThat(count("media_jobs")).isZero();
        doThrow(new UploadFailure("STORAGE_UNAVAILABLE")).when(staging).requireComplete(any());
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        assertThat(count("media_jobs")).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM media_uploads", String.class)).isEqualTo("OPEN");
        reset(staging);
        var first = mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.uploadState").value("SUBMITTED"))
                .andExpect(jsonPath("$.assetState").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0)).andReturn();
        String job = mapper.readTree(first.getResponse().getContentAsString()).path("jobId").asString();
        assertThat(UUID.fromString(job)).isNotNull();
        candidate = false;
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(job));
        assertThat(count("media_jobs")).isEqualTo(1);
        assertThat(count("media_outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE upload_id = ?", String.class,
                command.uploadId())).isEqualTo("QUEUED");
        verify(staging, times(1)).requireComplete(any());
    }

    @Test void expiredOpenUploadCannotBeCompletedOrAllocateAJob() throws Exception {
        ensure(body(command.byteLength()));
        jdbc.update("UPDATE media_uploads SET created_at = now() - interval '2 hours', "
                + "expires_at = now() - interval '1 hour' WHERE id = ?", command.uploadId());
        mvc.perform(post("/internal/v1/uploads/" + command.uploadId() + "/complete")
                .header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("UPLOAD_EXPIRED"));
        assertThat(count("media_jobs")).isZero();
        verifyNoInteractions(staging);
    }

    @Test void simultaneousCompletionsReturnTheSameDurableJob() throws Exception {
        ensure(body(command.byteLength()));
        var inspected = new CountDownLatch(2);
        doAnswer(invocation -> {
            inspected.countDown();
            assertThat(inspected.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(staging).requireComplete(any());
        var results = race(this::complete, this::complete);
        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(202));
        var first = mapper.readTree(results.get(0).getResponse().getContentAsString());
        var second = mapper.readTree(results.get(1).getResponse().getContentAsString());
        assertThat(first.path("jobId").asString()).isEqualTo(second.path("jobId").asString()).isNotBlank();
        assertThat(count("media_jobs")).isEqualTo(1);
        assertThat(first.path("jobId").asString()).isEqualTo(
                jdbc.queryForObject("SELECT id FROM media_jobs", UUID.class).toString());
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM media_jobs", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jobInsertOrCommitFailureRollsBackAndReturnsSanitizedUnavailableBeforeRetry(boolean atCommit) throws Exception {
        ensure(body(command.byteLength()));
        jdbc.execute("""
                CREATE FUNCTION reject_job() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private database failure fixture'; END $$
                """);
        jdbc.execute(atCommit
                ? "CREATE CONSTRAINT TRIGGER reject_job AFTER INSERT ON media_jobs "
                        + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_job()"
                : "CREATE TRIGGER reject_job BEFORE INSERT ON media_jobs FOR EACH ROW EXECUTE FUNCTION reject_job()");
        try {
            var failed = complete().getResponse();
            assertThat(failed.getStatus()).isEqualTo(503);
            assertThat(failed.getContentAsString()).contains("MEDIA_UNAVAILABLE")
                    .doesNotContain("private database", "media_jobs", "INSERT", "SQLException");
            assertThat(failed.getHeader("Cache-Control")).contains("no-store");
            assertThat(count("media_jobs")).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM media_uploads", String.class)).isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("SELECT state FROM media_assets", String.class)).isEqualTo("UPLOADING");
        } finally {
            jdbc.execute("DROP TRIGGER reject_job ON media_jobs");
            jdbc.execute("DROP FUNCTION reject_job()");
        }
        assertThat(complete().getResponse().getStatus()).isEqualTo(202);
        assertThat(count("media_jobs")).isEqualTo(1);
    }

    private MvcResult complete() throws Exception {
        return mvc.perform(post("/internal/v1/uploads/" + command.uploadId() + "/complete")
                .header("Authorization", "Bearer " + token("core.media.uploads:write"))).andReturn();
    }

    @Test void completionWhileReissueIsSigningPreventsReturningTheGrant() throws Exception {
        ensure(body(command.byteLength()));
        var signing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            signing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new UploadGrantSigner.Grant(command.uploadId(), "PUT", "https://example.invalid/fixture",
                    command.expiresAt(), java.util.Map.of());
        }).when(signer).sign(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reissue = pool.submit(() -> mvc.perform(post("/internal/v1/uploads/" + command.uploadId() + "/upload-url")
                    .header("Authorization", "Bearer " + token("core.media.uploads:write"))).andReturn());
            try {
                assertThat(signing.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(complete().getResponse().getStatus()).isEqualTo(202);
            } finally { release.countDown(); }
            var response = reissue.get(10, TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentAsString()).contains("UPLOAD_STATE_CONFLICT").doesNotContain("fixture", "https://");
        }
        assertThat(count("media_jobs")).isEqualTo(1);
    }

    @Test void simultaneousChangedFingerprintConflictsWithoutDuplicateAsset() throws Exception {
        var results = race(() -> ensure(body(1024)), () -> ensure(body(2048)));
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 409)
                .findFirst().orElseThrow().getResponse().getContentAsString())
                .contains("IDEMPOTENCY_CONFLICT");
        assertThat(count("media_uploads")).isEqualTo(1);
        assertThat(count("media_assets")).isEqualTo(1);
    }

    @Test void expiredAndSubmittedSessionsCannotIssueAnotherCapability() throws Exception {
        assertThat(ensure(body(command.byteLength())).getResponse().getStatus()).isEqualTo(201);
        String path = "/internal/v1/uploads/" + command.uploadId() + "/upload-url";
        jdbc.update("UPDATE media_uploads SET state = 'SUBMITTED' WHERE id = ?", command.uploadId());
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPLOAD_STATE_CONFLICT"));
        jdbc.update("UPDATE media_uploads SET state = 'OPEN', created_at = now() - interval '2 hours', "
                + "expires_at = now() - interval '1 hour' WHERE id = ?", command.uploadId());
        mvc.perform(post(path).header("Authorization", "Bearer " + token("core.media.uploads:write")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("UPLOAD_EXPIRED"));
        assertThat(BINDING_READS.get()).isEqualTo(1);
        assertThat(count("media_jobs")).isZero();
        assertThat(count("media_outbox_events")).isZero();
    }

    private String body(long byteLength) throws Exception {
        return mapper.writeValueAsString(new UploadControlController.EnsureUpload(command.requestId(),
                command.contentId(), command.bindingId(), command.assetId(), command.assetVersion(),
                byteLength, command.sha256(), command.expiresAt()));
    }

    private MvcResult ensure(String body) throws Exception {
        return mvc.perform(put("/internal/v1/uploads/" + command.uploadId())
                .header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static <T> java.util.List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var right = pool.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        }
    }

    @Test void staleOrUnavailableCoreCannotCreateAnyMediaRow() throws Exception {
        String path = "/internal/v1/uploads/" + command.uploadId();
        String body = mapper.writeValueAsString(new UploadControlController.EnsureUpload(command.requestId(),
                command.contentId(), command.bindingId(), command.assetId(), command.assetVersion(),
                command.byteLength(), command.sha256(), command.expiresAt()));
        candidate = false;
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        unavailable = true;
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:read"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).header("Authorization", "Bearer " + token("core.media.uploads:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(count("media_uploads")).isZero();
        assertThat(count("media_assets")).isZero();
    }

    private int count(String table) {
        return switch (table) {
            case "media_uploads", "media_assets", "media_jobs", "media_outbox_events" ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            default -> throw new IllegalArgumentException();
        };
    }
    private static HttpServer bindingServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/internal/v1/media/bindings/", exchange -> {
                BINDING_READS.incrementAndGet();
                int status = 200;
                byte[] body = new byte[0];
                try {
                    var jwt = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    if (!jwt.verify(new RSASSAVerifier((java.security.interfaces.RSAPublicKey) MEDIA.getPublic()))
                            || !"media.bindings:read".equals(jwt.getJWTClaimsSet().getStringClaim("scope"))) {
                        status = 401;
                    } else if (unavailable) {
                        status = 503;
                    } else {
                        var expected = command;
                        String json = "{\"bindingId\":\"" + expected.bindingId() + "\",\"contentId\":\""
                                + expected.contentId() + "\",\"assetId\":\"" + expected.assetId()
                                + "\",\"assetVersion\":" + expected.assetVersion() + ",\"candidate\":"
                                + candidate + ",\"active\":false}";
                        body = json.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                    }
                } catch (Exception exception) { status = 401; }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) { exchange.getResponseBody().write(body); }
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static KeyPair pair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String key(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }
    private static String token(String scope) throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = new JWTClaimsSet.Builder().issuer("libra-core-services").subject("libra-core")
                .audience("libra-media-internal").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                .claim("purpose", "service-access").claim("scope", scope).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("core-control-test").type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(CORE.getPrivate()));
        return jwt.serialize();
    }
}
