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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:media_auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false", "libra.media.storage.enabled=false"})
@Import(CoreServiceSecurityTest.Probe.class)
class CoreServiceSecurityTest {
    static final KeyPair CORE = pair();
    static final String KID = "core-media-test";
    static final String PATH = "/internal/v1/uploads/" + UUID.randomUUID();
    static final String READ = "core.media.uploads:read";
    static final String WRITE = "core.media.uploads:write";
    static final AtomicInteger CALLS = new AtomicInteger();
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("libra.media.core-service.enabled", () -> true);
        registry.add("libra.media.core-service.public-key", () -> Base64.getEncoder().encodeToString(CORE.getPublic().getEncoded()));
        registry.add("libra.media.core-service.key-id", () -> KID);
    }
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        CALLS.set(0);
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
        @RequestMapping(path = "/internal/v1/uploads/{id}", method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE})
        Map<String, Boolean> operation() { CALLS.incrementAndGet(); return Map.of("authorized", true); }
        @PostMapping({"/internal/v1/uploads/{id}/complete", "/internal/v1/uploads/{id}/upload-url"})
        Map<String, Boolean> write() { return operation(); }
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
