package com.libra.streaming.core.integration.security;

import com.libra.streaming.core.integration.analytics.AnalyticsProperties;
import com.libra.streaming.core.playback.PlaybackProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import static org.assertj.core.api.Assertions.*;

/** Real cryptographic decoders only; no database, Docker or mocked signature validation. */
class InternalServiceSecurityAnalyticsTest {
    static final KeyPair PLAYBACK = pair(2048), OUTBOUND = pair(2048), MEDIA = pair(2048), ANALYTICS = pair(2048);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    final PlaybackProperties playback = new PlaybackProperties(privateKey(PLAYBACK), publicKey(PLAYBACK), "playback");
    final AnalyticsProperties outbound = new AnalyticsProperties(true, URI.create("https://analytics.example.test"),
            privateKey(OUTBOUND), publicKey(OUTBOUND), "outbound");
    final MediaServiceProperties media = new MediaServiceProperties(true, publicKey(MEDIA), "media");

    @Test void analyticsDecoderRejectsUndersizedAndReusedKeysAtConstruction() {
        for (KeyPair key : List.of(pair(1024), PLAYBACK, OUTBOUND, MEDIA)) {
            assertThatThrownBy(() -> InternalServiceSecurity.analyticsDecoder(
                    new AnalyticsServiceProperties(true, publicKey(key), "bootstrap"), media, playback, outbound, clock))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedicated RSA");
        }
    }
    @Test void compositeRejectsEqualEnabledKeyIds() {
        assertThatThrownBy(() -> InternalServiceSecurity.composite(media,
                new AnalyticsServiceProperties(true, publicKey(ANALYTICS), "media"), playback, outbound, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct key IDs");
    }
    @Test void disabledAnalyticsReturns401ForEveryToken() throws Exception {
        // BadJwtException maps to HTTP 401 in the internal resource-server chain.
        var decoder = InternalServiceSecurity.composite(media, new AnalyticsServiceProperties(false, "", ""),
                playback, outbound, clock);
        try (var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new org.springframework.mock.web.MockServletContext());
            context.register(DisabledSecurity.class);
            context.refresh();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                    .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
            for (String kid : Arrays.asList("bootstrap", "media", "unknown", null)) {
                var claims = new JWTClaimsSet.Builder().issuer("libra-analytics-services").subject("libra-analytics")
                        .audience("libra-core-internal").jwtID(UUID.randomUUID().toString())
                        .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(60)))
                        .claim("purpose", "service-access").claim("scope", "analytics.bootstrap:read").build();
                var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
                jwt.sign(new RSASSASigner(ANALYTICS.getPrivate()));
                assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(BadJwtException.class);
                for (String path : List.of("snapshots/catalog", "snapshots/profiles", "events/playback")) {
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/internal/v1/analytics/" + path).header("Authorization", "Bearer " + jwt.serialize()))
                            .andReturn().getResponse();
                    assertThat(response.getStatus()).isEqualTo(401);
                    assertThat(response.getContentType()).startsWith("application/problem+json");
                    assertThat(response.getContentAsString().contains(jwt.serialize())).as("token is not disclosed").isFalse();
                }
            }
            assertThatThrownBy(() -> decoder.decode("malformed-token")).isInstanceOf(BadJwtException.class);
        }
    }
    @org.junit.jupiter.api.Test
    void analyticsKeyMustDifferFromCoresOwnMediaControlKey() {
        // Review finding: Core holds the private half of its Media control key, so it must never pass as Analytics'.
        var control = pair(2048);
        var reused = new AnalyticsServiceProperties(true, publicKey(control), "analytics-bootstrap");
        var enabledControl = new com.libra.streaming.core.integration.media.MediaControlProperties(true,
                "http://media:8082", privateKey(control), publicKey(control), "media-control");
        assertThatThrownBy(() -> InternalServiceSecurity.requireDistinctFromMediaControl(reused, enabledControl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedicated RSA");
        var distinct = new AnalyticsServiceProperties(true, publicKey(pair(2048)), "analytics-bootstrap");
        InternalServiceSecurity.requireDistinctFromMediaControl(distinct, enabledControl);
        InternalServiceSecurity.requireDistinctFromMediaControl(reused,
                new com.libra.streaming.core.integration.media.MediaControlProperties(false, null, null, null, null));
    }

    // Mock servlet transport only; production chain and RSA decoders are real. No PostgreSQL.
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class DisabledSecurity {
        @org.springframework.context.annotation.Bean
        org.springframework.security.web.SecurityFilterChain chain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            var fixture = new InternalServiceSecurityAnalyticsTest();
            return new InternalServiceSecurity().internalServiceChain(http, fixture.media,
                    new AnalyticsServiceProperties(false, "", ""), fixture.playback, fixture.outbound,
                    new com.libra.streaming.core.integration.media.MediaControlProperties(false, null, null, null, null),
                    fixture.clock, new com.libra.streaming.core.api.ApiProblems(new tools.jackson.databind.ObjectMapper()));
        }
    }
    static KeyPair pair(int bits) {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(bits); return generator.generateKeyPair(); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
    static String publicKey(KeyPair key) { return Base64.getEncoder().encodeToString(key.getPublic().getEncoded()); }
    static String privateKey(KeyPair key) { return Base64.getEncoder().encodeToString(key.getPrivate().getEncoded()); }
}
