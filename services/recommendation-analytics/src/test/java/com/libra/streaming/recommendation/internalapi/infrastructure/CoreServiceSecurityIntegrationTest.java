package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens.CORE;
import static com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens.KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RA01.3: Core's service credential reaches exactly its operation; no browser or playback credential does. */
class CoreServiceSecurityIntegrationTest extends InternalApiFixture {
    static final Path FIXTURES = Path.of("..", "..", "contracts", "fixtures", "analytics-v1", "http");
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlers;
    boolean writes;

    @Override boolean expectsCheckpointWrites() { return writes; }

    @Test
    void eachScopeReachesOnlyItsOwnOperation() throws Exception {
        mvc.perform(post(STATISTICS).header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ANALYTICS_NOT_READY"));
        verify(freshness).updatedAt(Projection.STATISTICS);
        mvc.perform(post(RECOMMENDATIONS).header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ANALYTICS_NOT_READY"));
        verify(freshness).updatedAt(Projection.RECOMMENDATIONS);

        mvc.perform(post(STATISTICS).header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE))))
                .andExpect(status().isForbidden()).andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("code").value("ACCESS_DENIED"));
        mvc.perform(post(RECOMMENDATIONS).header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ACCESS_DENIED"));
        verify(freshness, times(1)).updatedAt(Projection.STATISTICS);
        verify(freshness, times(1)).updatedAt(Projection.RECOMMENDATIONS);
    }

    @Test
    void aTokenEncodedTheWayCoreEncodesItIsAccepted() throws Exception {
        // Same library calls as Core's AnalyticsServiceTokens.issue, so header and claim encoding match its wire form.
        var key = new RSAKey.Builder((RSAPublicKey) CORE.getPublic()).privateKey(CORE.getPrivate()).keyID(KID).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer("libra-core-services").subject("libra-core")
                .audience(List.of("libra-recommendation-analytics")).id(UUID.randomUUID().toString())
                .issuedAt(issued).expiresAt(issued.plusSeconds(60))
                .claim("purpose", "service-access").claim("scope", STATISTICS_SCOPE).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(KID).type("JWT").build(), claims)).getTokenValue();
        String correlation = UUID.randomUUID().toString();
        // Core sends Accept: application/json; the error body is a problem document regardless.
        String body = mvc.perform(post(STATISTICS).header("Authorization", bearer(token))
                        .header("X-Correlation-ID", correlation).accept("application/json")
                        .contentType("application/json").content("{\"from\":\"2026-11-01\",\"to\":\"2026-11-02\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var mapper = new ObjectMapper();
        String fixture = Files.readString(FIXTURES.resolve("problem-not-ready.json")).replace("{{correlationId}}", correlation);
        assertThat(mapper.readValue(body, Map.class)).isEqualTo(mapper.readValue(fixture, Map.class));
        verify(freshness).updatedAt(Projection.STATISTICS);
    }

    @Test
    void anAuthorizedCallNeverGetsZerosWhileNoProjectionIsServed() throws Exception {
        writes = true;
        String statistics = bearer(ServiceTokens.token(STATISTICS_SCOPE));
        String recommendations = bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE));
        freshness.confirm(InputTopic.PLAYBACK, Instant.now().minusSeconds(30));
        String body = mvc.perform(post(STATISTICS).header("Authorization", statistics))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        var mapper = new ObjectMapper();
        Map<?, ?> actual = mapper.readValue(body, Map.class);
        String fixture = Files.readString(FIXTURES.resolve("problem-unavailable.json"))
                .replace("{{correlationId}}", (String) actual.get("correlationId"));
        assertThat(actual).isEqualTo(mapper.readValue(fixture, Map.class));
        // Recommendations depend on all three topics: one confirmed input is not enough.
        mvc.perform(post(RECOMMENDATIONS).header("Authorization", recommendations))
                .andExpect(jsonPath("code").value("ANALYTICS_NOT_READY"));
        freshness.confirm(InputTopic.CATALOG, Instant.now().minusSeconds(30));
        freshness.confirm(InputTopic.PROFILES, Instant.now().minusSeconds(30));
        mvc.perform(post(RECOMMENDATIONS).header("Authorization", recommendations))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("ANALYTICS_UNAVAILABLE"));
        // Answering did not move any checkpoint.
        List<Map<String, Object>> confirmed = rows();
        mvc.perform(post(STATISTICS).header("Authorization", statistics)).andExpect(status().isServiceUnavailable());
        assertThat(rows()).isEqualTo(confirmed);
    }

    @Test
    void browserAccessTokensAreNotServiceCredentials() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // Core's browser identity: HS256, issuer libra-core, audience libra-web, a user subject and a role.
        var browser = new JWTClaimsSet.Builder().issuer("libra-core").audience("libra-web")
                .subject(UUID.randomUUID().toString()).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                .claim("role", "ADMIN").claim("scope", STATISTICS_SCOPE).build();
        String hmac = hmac(browser, new byte[32]);
        // Algorithm confusion: the pinned public key used as an HMAC secret, over otherwise perfect service claims.
        String confused = hmac(ServiceTokens.claims(STATISTICS_SCOPE, now).build(), CORE.getPublic().getEncoded());
        // The same browser claims signed with the trusted service key still carry the wrong identity.
        String signedByCore = ServiceTokens.sign(browser, CORE, JWSAlgorithm.RS256, KID);
        for (String token : List.of(hmac, confused, signedByCore)) {
            mvc.perform(post(STATISTICS).header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
            mvc.perform(post(STATISTICS).cookie(new Cookie("libra_access", token)))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(freshness);
    }

    @Test
    void playbackTicketsAreNotServiceCredentials() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var ticket = new JWTClaimsSet.Builder().issuer("libra-core-playback").subject(UUID.randomUUID().toString())
                .audience("libra-media-streams").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                .claim("purpose", "playback").claim("sessionId", UUID.randomUUID().toString())
                .claim("scope", STATISTICS_SCOPE).build();
        var playbackKey = ServiceTokens.pair(2048);
        // A service-shaped token whose only fault is the purpose: a ticket key reuse would look like this.
        var servicePurposePlayback = ServiceTokens.claims(STATISTICS_SCOPE, now).claim("purpose", "playback").build();
        // Media's own inbound credential from Core: right issuer and key family, another audience.
        var mediaAudience = ServiceTokens.claims(STATISTICS_SCOPE, now).audience("libra-media-internal").build();
        for (String token : List.of(
                ServiceTokens.sign(ticket, playbackKey, JWSAlgorithm.RS256, "core-playback"),
                ServiceTokens.sign(ticket, playbackKey, JWSAlgorithm.RS256, KID),
                ServiceTokens.sign(ticket, CORE, JWSAlgorithm.RS256, KID),
                ServiceTokens.sign(servicePurposePlayback, CORE, JWSAlgorithm.RS256, KID),
                ServiceTokens.sign(mediaAudience, CORE, JWSAlgorithm.RS256, KID))) {
            mvc.perform(post(STATISTICS).header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"));
            mvc.perform(post(STATISTICS).cookie(new Cookie("libra_playback", token)))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(freshness);
    }

    @Test
    void unknownOperationsAndMethodsAnswerOnlyToAnAuthenticatedService() throws Exception {
        String token = bearer(ServiceTokens.token(STATISTICS_SCOPE));
        for (String path : List.of("/internal", "/internal/v1/statistics", "/internal/v1/statistics/query/extra",
                "/internal/v2/statistics/query", "/internal/v1/events")) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization", token)).andExpect(status().isNotFound())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("code").value("NOT_FOUND")).andExpect(jsonPath("$.length()").value(6));
        }
        for (var method : List.of(get(STATISTICS), put(STATISTICS), delete(STATISTICS), patch(STATISTICS))) {
            mvc.perform(method.header("Authorization", token)).andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("code").value("METHOD_NOT_ALLOWED"));
        }
        mvc.perform(get(STATISTICS)).andExpect(status().isUnauthorized());
        verifyNoInteractions(freshness);
    }

    @Test
    void aServiceTokenOpensNothingOutsideTheInternalApi() throws Exception {
        String token = bearer(ServiceTokens.token(STATISTICS_SCOPE));
        for (String path : List.of("/", "/v1/statistics", "/actuator/env", "/actuator/metrics")) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().is4xxClientError())
                    .andExpect(content().string(""));
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        verifyNoInteractions(freshness);
    }

    @Test
    void everyInternalHandlerIsBoundToExactlyOneScope() throws Exception {
        // The chain lets an authenticated service reach unknown paths for a 404. A handler added without its own
        // scope rule would therefore be open to both scopes; this fails until the rule exists.
        int checked = 0;
        for (var mapping : handlers.getHandlerMethods().keySet()) {
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/internal/")) { continue; }
                int allowed = 0;
                for (String scope : List.of(STATISTICS_SCOPE, RECOMMENDATIONS_SCOPE)) {
                    int status = mvc.perform(post(path).header("Authorization", bearer(ServiceTokens.token(scope))))
                            .andReturn().getResponse().getStatus();
                    if (status != 403) { allowed++; }
                }
                assertThat(allowed).as(path).isEqualTo(1);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(2);
    }

    private static String hmac(JWTClaimsSet claims, byte[] secret) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }
}
