package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.web.servlet.ResultActions;
import static com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens.CORE;
import static com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens.KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RA01.4: every refused credential is answered before the handler, with a fixed problem body, no write and no trace
 * of the credential in the response or the log.
 */
class CoreServiceRejectionIntegrationTest extends InternalApiFixture {
    final List<String> presented = new ArrayList<>();

    /** Positive control: the chain accepts an unmodified token, so the refusals below are caused by the mutation. */
    @BeforeEach
    void anUnmodifiedTokenIsAccepted() throws Exception {
        mvc.perform(post(STATISTICS).header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE))))
                .andExpect(status().isServiceUnavailable());
        Mockito.clearInvocations(freshness);
    }

    @AfterEach
    void refusalsReachNoHandlerAndLeaveNoTrace(CapturedOutput output) {
        verifyNoInteractions(freshness);
        assertThat(presented).isNotEmpty();
        for (String credential : presented) { assertThat(output.getAll()).doesNotContain(credential); }
        assertThat(output.getAll()).doesNotContain("canary").doesNotContain(ServiceTokens.publicKey(CORE));
    }

    @Test
    void wrongIdentityClaimsAreRefused() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> mutations = List.of(
                b -> b.issuer("libra-core"), b -> b.issuer("libra-core-services "), b -> b.issuer(null),
                b -> b.subject("libra-media"), b -> b.subject("browser-admin"), b -> b.subject(null),
                b -> b.audience("libra-web"), b -> b.audience("libra-media-internal"), b -> b.audience((String) null),
                b -> b.audience(List.of("libra-recommendation-analytics", "libra-web")),
                b -> b.claim("purpose", "playback"), b -> b.claim("purpose", null),
                b -> b.jwtID(null), b -> b.jwtID(" "), b -> b.jwtID("x".repeat(129)));
        for (var mutation : mutations) { refused(ServiceTokens.token(STATISTICS_SCOPE, mutation)); }
    }

    @Test
    void scopesMustBeExactlyOneKnownString() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> mutations = List.of(
                b -> b.claim("scope", null), b -> b.claim("scope", ""), b -> b.claim("scope", "ROLE_ADMIN"),
                b -> b.claim("scope", "statistics:write"), b -> b.claim("scope", "STATISTICS:READ"),
                b -> b.claim("scope", "statistics:read "), b -> b.claim("scope", "*"),
                b -> b.claim("scope", STATISTICS_SCOPE + " " + RECOMMENDATIONS_SCOPE),
                b -> b.claim("scope", List.of(STATISTICS_SCOPE)), b -> b.claim("scope", Map.of("value", STATISTICS_SCOPE)),
                b -> b.claim("scope", "core.media.uploads:read"),
                b -> b.claim("scope", null).claim("scp", List.of(STATISTICS_SCOPE)));
        for (var mutation : mutations) { refused(ServiceTokens.token(STATISTICS_SCOPE, mutation)); }
    }

    @Test
    void expiredFutureAndOverlongTokensAreRefused() throws Exception {
        Instant now = Instant.now();
        List<Consumer<JWTClaimsSet.Builder>> mutations = List.of(
                b -> b.issueTime(Date.from(now.minusSeconds(120))).expirationTime(Date.from(now.minusSeconds(60))),
                b -> b.expirationTime(Date.from(now.minusSeconds(1))),
                b -> b.issueTime(Date.from(now.plusSeconds(30))).expirationTime(Date.from(now.plusSeconds(90))),
                b -> b.expirationTime(Date.from(now.plusSeconds(120))),
                b -> b.expirationTime(Date.from(now.plusSeconds(3600))),
                b -> b.notBeforeTime(Date.from(now.plusSeconds(30))),
                b -> b.issueTime(null), b -> b.expirationTime(null),
                b -> b.claim("iat", "canary"), b -> b.claim("exp", "canary"), b -> b.claim("nbf", "canary"));
        for (var mutation : mutations) { refused(ServiceTokens.token(STATISTICS_SCOPE, mutation)); }
    }

    @Test
    void malformedClaimTypesAreRefusedWithoutEchoingThem() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> mutations = List.of(
                b -> b.claim("iss", 42), b -> b.claim("sub", List.of("libra-core")), b -> b.claim("aud", List.of(42)),
                b -> b.claim("jti", 42), b -> b.claim("purpose", List.of("service-access")),
                b -> b.claim("iss", "canary-issuer"), b -> b.claim("sub", "canary-subject"));
        for (var mutation : mutations) { refused(ServiceTokens.token(STATISTICS_SCOPE, mutation)); }
    }

    @Test
    void forgedSignaturesAlgorithmsAndKeyIdsAreRefused() throws Exception {
        var claims = ServiceTokens.claims(STATISTICS_SCOPE, Instant.now()).build();
        String valid = ServiceTokens.sign(claims, CORE, JWSAlgorithm.RS256, KID);
        String[] parts = valid.split("\\.");
        String tamperedPayload = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ServiceTokens.claims(RECOMMENDATIONS_SCOPE, Instant.now()).build().toString()
                        .getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        for (String token : List.of(
                ServiceTokens.sign(claims, ServiceTokens.pair(2048), JWSAlgorithm.RS256, KID),
                ServiceTokens.sign(claims, CORE, JWSAlgorithm.RS384, KID),
                ServiceTokens.sign(claims, CORE, JWSAlgorithm.RS512, KID),
                ServiceTokens.sign(claims, CORE, JWSAlgorithm.PS256, KID),
                ServiceTokens.sign(claims, CORE, JWSAlgorithm.RS256, "other-key"),
                ServiceTokens.sign(claims, CORE, JWSAlgorithm.RS256, null),
                tamperedPayload, parts[0] + "." + parts[1] + ".", parts[0] + "." + parts[1],
                parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "AAAA",
                new PlainJWT(claims).serialize())) {
            refused(token);
        }
        // The tampered payload asked for the other scope: it must not reach that operation either.
        presented.add(tamperedPayload);
        problem(mvc.perform(post(RECOMMENDATIONS).header("Authorization", bearer(tamperedPayload))));
    }

    @Test
    void missingAmbiguousAndMisplacedCredentialsAreRefused() throws Exception {
        String valid = ServiceTokens.token(STATISTICS_SCOPE);
        presented.add(valid);
        problem(mvc.perform(post(STATISTICS)));
        problem(mvc.perform(post(STATISTICS).cookie(new Cookie("libra_access", valid), new Cookie("libra_playback", valid))
                .header("X-Role", "ADMIN").header("X-User-ID", UUID.randomUUID().toString())
                .header("X-Forwarded-User", "libra-core")));
        problem(mvc.perform(post(STATISTICS).param("access_token", valid)));
        problem(mvc.perform(post(STATISTICS).contentType("application/x-www-form-urlencoded").content("access_token=" + valid)));
        for (String header : List.of(valid, "bearer", "Bearer", "Bearer ", "Basic " + valid, "Token " + valid,
                "Bearer canary-invalid-token", "Bearer " + "x".repeat(8193), "Bearer " + valid + " " + valid)) {
            problem(mvc.perform(post(STATISTICS).header("Authorization", header)));
        }
        for (String other : List.of("Bearer " + valid, "Basic canary", "Bearer canary")) {
            problem(mvc.perform(post(STATISTICS).header("Authorization", "Bearer " + valid, other)));
            problem(mvc.perform(post(STATISTICS).header("Authorization", other, "Bearer " + valid)));
        }
    }

    @Test
    void aRefusedCallIsRefusedWhateverItsBodyOrCorrelationHeaderSays() throws Exception {
        String expired = ServiceTokens.token(STATISTICS_SCOPE,
                b -> b.issueTime(Date.from(Instant.now().minusSeconds(120))).expirationTime(Date.from(Instant.now().minusSeconds(60))));
        presented.add(expired);
        mvc.perform(post(STATISTICS).header("Authorization", bearer(expired)).header("X-Correlation-ID", "canary<script>")
                        .contentType("application/json").content("{\"canary\":\"" + "x".repeat(100_000) + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(content().string(not(containsString("canary"))));
        String correlation = UUID.randomUUID().toString();
        mvc.perform(post(STATISTICS).header("Authorization", bearer(expired)).header("X-Correlation-ID", correlation))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("correlationId").value(correlation));
    }

    private void refused(String token) throws Exception {
        presented.add(token);
        problem(mvc.perform(post(STATISTICS).header("Authorization", bearer(token))));
    }

    private static void problem(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("type").value("about:blank")).andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("title").value("Unauthorized"))
                .andExpect(jsonPath("detail").value("A valid service credential is required."))
                .andExpect(jsonPath("code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("correlationId").value(matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(content().string(not(containsString("canary"))));
    }
}
