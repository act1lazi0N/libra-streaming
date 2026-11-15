package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens.CORE;
import static com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens.KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure verifier rules; the HTTP chain around them is proven in {@code CoreServiceSecurityIntegrationTest}. */
class CoreServiceDecoderTest {
    static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    static final String SCOPE = CoreServiceSecurity.STATISTICS;
    final JwtDecoder decoder = CoreServiceSecurity.decoder(
            new CoreServiceProperties(true, ServiceTokens.publicKey(CORE), KID), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsCoreTokenAtTheMaximumLifetime() {
        var jwt = decoder.decode(sign(ServiceTokens.claims(SCOPE, NOW).build()));
        assertThat(jwt.getSubject()).isEqualTo("libra-core");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo(SCOPE);
        // The last valid instant of a token issued 59 seconds ago.
        decoder.decode(sign(ServiceTokens.claims(SCOPE, NOW.minusSeconds(59)).build()));
    }

    @Test
    void expiryFutureIssuanceAndLifetimeUseZeroLeeway() {
        for (JWTClaimsSet invalid : List.of(
                ServiceTokens.claims(SCOPE, NOW.minusSeconds(60)).build(),
                ServiceTokens.claims(SCOPE, NOW).expirationTime(Date.from(NOW.plusSeconds(61))).build(),
                ServiceTokens.claims(SCOPE, NOW.plusSeconds(1)).build(),
                ServiceTokens.claims(SCOPE, NOW).expirationTime(Date.from(NOW)).build(),
                ServiceTokens.claims(SCOPE, NOW).expirationTime(Date.from(NOW.minusSeconds(1))).build(),
                ServiceTokens.claims(SCOPE, NOW).notBeforeTime(Date.from(NOW.plusSeconds(1))).build(),
                ServiceTokens.claims(SCOPE, NOW).issueTime(null).build(),
                ServiceTokens.claims(SCOPE, NOW).expirationTime(null).build())) {
            assertThatThrownBy(() -> decoder.decode(sign(invalid))).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void disabledModeAcceptsNothingAndNeedsNoKey() {
        var disabled = CoreServiceSecurity.decoder(new CoreServiceProperties(false, "", ""), Clock.systemUTC());
        assertThatThrownBy(() -> disabled.decode(ServiceTokens.token(SCOPE))).isInstanceOf(BadJwtException.class);
        var ignoredKey = CoreServiceSecurity.decoder(
                new CoreServiceProperties(false, ServiceTokens.publicKey(CORE), KID), Clock.systemUTC());
        assertThatThrownBy(() -> ignoredKey.decode(ServiceTokens.token(SCOPE))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void enabledModeRefusesMissingWeakOrForeignKeyMaterialWithoutEchoingIt() throws Exception {
        for (String keyId : new String[] {null, "", " ", "has space", "x".repeat(65), "a/b"}) {
            assertThatThrownBy(() -> new CoreServiceProperties(true, ServiceTokens.publicKey(CORE), keyId))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String key : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> new CoreServiceProperties(true, key, KID)).isInstanceOf(IllegalArgumentException.class);
        }
        var ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        String canary = "canary-invalid-key";
        for (String key : List.of(canary, Base64.getEncoder().encodeToString(canary.getBytes()),
                ServiceTokens.publicKey(ServiceTokens.pair(1024)),
                Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(CORE.getPrivate().getEncoded()))) {
            var properties = new CoreServiceProperties(true, key, KID);
            assertThat(properties.toString()).doesNotContain(key).doesNotContain(KID);
            assertThatThrownBy(() -> CoreServiceSecurity.decoder(properties, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class).hasNoCause()
                    .hasMessage("Core service authentication requires an RSA public key of at least 2048 bits");
        }
    }

    private static String sign(JWTClaimsSet claims) { return ServiceTokens.sign(claims, CORE, JWSAlgorithm.RS256, KID); }
}
