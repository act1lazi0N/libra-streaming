package com.libra.streaming.media.control;

import com.libra.streaming.media.persistence.MediaPersistenceService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
