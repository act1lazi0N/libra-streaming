package com.libra.streaming.media.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import jakarta.servlet.http.Cookie;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import com.libra.streaming.media.upload.infrastructure.MediaSecurityQueueProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {"libra.media.storage.enabled=false", "libra.media.upload-control.enabled=false"})
@Import({CoreServiceSecurityIntegrationTest.Probe.class, MediaSecurityQueueProbe.class})
class CoreServiceSecurityIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_service_auth").withUsername("media_service_auth").withPassword(UUID.randomUUID().toString());
    static final KeyPair CORE = pair();
    static final String KID = "core-media-test";
    static final String PATH = "/internal/v1/uploads/" + UUID.randomUUID();
    static final String READ = "core.media.uploads:read";
    static final String WRITE = "core.media.uploads:write";
    static final AtomicInteger CALLS = new AtomicInteger();
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired Probe probe;
    MockMvc mvc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("libra.media.core-service.enabled", () -> true);
        registry.add("libra.media.core-service.public-key", () -> Base64.getEncoder().encodeToString(CORE.getPublic().getEncoded()));
        registry.add("libra.media.core-service.key-id", () -> KID);
    }
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        CALLS.set(0);
        jdbc.execute("TRUNCATE media_assets CASCADE");
        probe.command = new MediaPersistenceService.Ensure(UUID.fromString(PATH.substring(PATH.lastIndexOf('/') + 1)),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1024,
                "a".repeat(64), Instant.now().plusSeconds(3500));
    }

    @AfterEach void deniedRequestsHaveNoDatabaseEffects() {
        if (CALLS.get() == 0) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_uploads", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        }
    }

    @Test void dedicatedReadAndWriteReachOnlyTheirContractOperations() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token(READ, b -> {})))
                .andExpect(status().isOk()).andExpect(jsonPath("authorized").value(true));
        mvc.perform(put(PATH).header("Authorization", "Bearer " + token(WRITE, b -> {})))
                .andExpect(status().isOk());
        mvc.perform(post(PATH + "/complete").header("Authorization", "Bearer " + token(WRITE, b -> {})))
                .andExpect(status().isOk());
        mvc.perform(post(PATH + "/upload-url").header("Authorization", "Bearer " + token(WRITE, b -> {})))
                .andExpect(status().isOk());
        assertThat(CALLS.get()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_uploads", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).isEqualTo(1);
    }

    @Test void deniedCompletionLeavesExistingUploadUnchanged() throws Exception {
        mvc.perform(put(PATH).header("Authorization", "Bearer " + token(WRITE, b -> {}))).andExpect(status().isOk());
        mvc.perform(post(PATH + "/complete").header("Authorization", "Bearer " + token(READ, b -> {})))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/complete").header("Authorization", "Bearer " + token(WRITE, b -> b.claim("purpose", "playback"))))
                .andExpect(status().isUnauthorized());
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets", String.class)).isEqualTo("UPLOADING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
    }

    @Test void scopesAndUnlistedMethodsFailBeforeHandler() throws Exception {
        mvc.perform(put(PATH).header("Authorization", "Bearer " + token(READ, b -> {})))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ACCESS_DENIED"));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token(WRITE, b -> {}))).andExpect(status().isForbidden());
        mvc.perform(delete(PATH).header("Authorization", "Bearer " + token(WRITE, b -> {}))).andExpect(status().isForbidden());
        mvc.perform(get(PATH + "/extra").header("Authorization", "Bearer " + token(READ, b -> {}))).andExpect(status().isForbidden());
        assertThat(CALLS.get()).isZero();
    }

    @Test void anonymousCookiesQueryTokensAndForgedRolesCannotBecomeServiceIdentity() throws Exception {
        String valid = token(READ, b -> {});
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).cookie(new Cookie("libra_access", valid), new Cookie("libra_playback", valid))
                        .header("X-Role", "ADMIN").header("X-User-ID", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("access_token", valid)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer canary-invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("canary"))));
        assertThat(CALLS.get()).isZero();
    }

    @Test void rejectsWrongIdentityAndInvalidTimesBeforeHandler() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> mutations = List.of(
                b -> b.issuer("libra-core"), b -> b.subject("browser-admin"), b -> b.audience("libra-web"),
                b -> b.audience(List.of("libra-media-internal", "libra-web")), b -> b.claim("purpose", "playback"),
                b -> b.claim("scope", "ROLE_ADMIN"), b -> b.claim("scope", List.of(READ)),
                b -> b.claim("scope", READ + " " + WRITE), b -> b.jwtID(" "),
                b -> b.issueTime(Date.from(Instant.now().plusSeconds(30))),
                b -> b.expirationTime(Date.from(Instant.now().minusSeconds(1))),
                b -> b.expirationTime(Date.from(Instant.now().plusSeconds(120))), b -> b.issueTime(null), b -> b.expirationTime(null));
        for (var mutation : mutations) {
            mvc.perform(get(PATH).header("Authorization", "Bearer " + token(READ, mutation)))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(CALLS.get()).isZero();
    }

    @Test void rejectsForeignKeysAlgorithmsAndKeyIds() throws Exception {
        var claims = claims(READ).build();
        for (String raw : List.of(sign(claims, pair(), JWSAlgorithm.RS256, KID),
                sign(claims, CORE, JWSAlgorithm.RS512, KID), sign(claims, CORE, JWSAlgorithm.RS256, "other"))) {
            mvc.perform(get(PATH).header("Authorization", "Bearer " + raw)).andExpect(status().isUnauthorized());
        }
        assertThat(CALLS.get()).isZero();
    }

    @Test void rejectsAmbiguousAuthorizationHeadersBeforeHandler() throws Exception {
        String valid = "Bearer " + token(WRITE, b -> {});
        for (String other : List.of(valid, "Basic canary", "Bearer canary")) {
            mvc.perform(put(PATH).header("Authorization", valid, other))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
            mvc.perform(put(PATH).header("Authorization", other, valid)).andExpect(status().isUnauthorized());
        }
        assertThat(CALLS.get()).isZero();
    }

    @Test void tamperedUnsignedAndSymmetricCredentialsCannotUseThePinnedRsaIdentity() throws Exception {
        String valid = token(WRITE, b -> {});
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims(WRITE).subject("tampered").build().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
        var hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), claims(WRITE).build());
        hmac.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[32]));
        for (String raw : List.of(tampered, new PlainJWT(claims(WRITE).build()).serialize(), hmac.serialize())) {
            mvc.perform(put(PATH).header("Authorization", "Bearer " + raw))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
        }
        assertThat(CALLS.get()).isZero();
    }

    @Test void exactExpiryAndMaximumLifetimeUseZeroLeeway() throws Exception {
        Instant now = Instant.parse("2026-09-25T00:00:00Z");
        var decoder = CoreServiceSecurity.decoder(new CoreServiceProperties(true,
                Base64.getEncoder().encodeToString(CORE.getPublic().getEncoded()), KID), Clock.fixed(now, java.time.ZoneOffset.UTC));
        var atLimit = claims(READ).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).build();
        assertThat(decoder.decode(sign(atLimit, CORE, JWSAlgorithm.RS256, KID)).getSubject()).isEqualTo("libra-core");
        for (var invalid : List.of(
                claims(READ).issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now)).build(),
                claims(READ).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(61))).build(),
                claims(READ).issueTime(Date.from(now.plusSeconds(1))).expirationTime(Date.from(now.plusSeconds(60))).build(),
                claims(READ).issueTime(Date.from(now)).expirationTime(Date.from(now)).build())) {
            assertThatThrownBy(() -> decoder.decode(sign(invalid, CORE, JWSAlgorithm.RS256, KID)))
                    .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }

    @Test void malformedSignedClaimsAndOversizedTokensAreSanitized() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> mutations = List.of(
                b -> b.claim("iss", 42), b -> b.claim("sub", List.of("libra-core")),
                b -> b.claim("aud", List.of(42)), b -> b.claim("jti", 42),
                b -> b.jwtID("x".repeat(129)), b -> b.claim("purpose", List.of("service-access")),
                b -> b.claim("scope", Map.of("value", WRITE)), b -> b.claim("iat", "canary"),
                b -> b.claim("exp", "canary"), b -> b.claim("nbf", "canary"),
                b -> b.notBeforeTime(Date.from(Instant.now().plusSeconds(30))));
        for (var mutation : mutations) {
            mvc.perform(put(PATH).header("Authorization", "Bearer " + token(WRITE, mutation)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("canary"))));
        }
        mvc.perform(put(PATH).header("Authorization", "Bearer " + "x".repeat(8193)))
                .andExpect(status().isUnauthorized());
        assertThat(CALLS.get()).isZero();
    }

    @Test void disabledAndInvalidConfigurationFailClosed() {
        var decoder = CoreServiceSecurity.decoder(new CoreServiceProperties(false, "", ""), Clock.systemUTC());
        assertThatThrownBy(() -> decoder.decode(token(READ, b -> {})))
                .isInstanceOf(org.springframework.security.oauth2.jwt.BadJwtException.class);
        assertThatThrownBy(() -> new CoreServiceProperties(true, "", KID)).isInstanceOf(IllegalArgumentException.class);
        var invalid = new CoreServiceProperties(true, "canary-invalid-key", KID);
        assertThatThrownBy(() -> CoreServiceSecurity.decoder(invalid, Clock.systemUTC()))
                .hasMessageNotContaining("canary").hasMessageContaining("RSA public key");
        assertThat(invalid.toString()).doesNotContain("canary");
    }

    @Test void publicMediaRoutesRemainDeniedAndHealthIsAvailable() throws Exception {
        mvc.perform(get("/v1/uploads/" + UUID.randomUUID()).header("Authorization", "Bearer " + token(READ, b -> {})))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        assertThat(CALLS.get()).isZero();
    }

    // Test-only operation probe: production upload handlers belong to M09/M11/M13.
    @RestController static class Probe {
        @Autowired MediaPersistenceService persistence;
        @Autowired MediaSecurityQueueProbe queue;
        MediaPersistenceService.Ensure command;
        @RequestMapping(path = "/internal/v1/uploads/{id}", method = {RequestMethod.GET, RequestMethod.DELETE})
        Map<String, Boolean> operation() { CALLS.incrementAndGet(); return Map.of("authorized", true); }
        @PutMapping("/internal/v1/uploads/{id}")
        Map<String, Boolean> ensure() { persistence.ensure(command); return operation(); }
        @PostMapping("/internal/v1/uploads/{id}/complete")
        Map<String, Boolean> complete() {
            queue.queue(command);
            return operation();
        }
        @PostMapping("/internal/v1/uploads/{id}/upload-url")
        Map<String, Boolean> uploadUrl() { return operation(); }
    }
    static KeyPair pair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    static JWTClaimsSet.Builder claims(String scope) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new JWTClaimsSet.Builder().issuer("libra-core-services").subject("libra-core")
                .audience("libra-media-internal").jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60))).claim("purpose", "service-access").claim("scope", scope);
    }
    static String token(String scope, Consumer<JWTClaimsSet.Builder> mutation) throws Exception {
        var builder = claims(scope); mutation.accept(builder);
        return sign(builder.build(), CORE, JWSAlgorithm.RS256, KID);
    }
    static String sign(JWTClaimsSet claims, KeyPair key, JWSAlgorithm algorithm, String kid) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner(key.getPrivate())); return jwt.serialize();
    }
}
