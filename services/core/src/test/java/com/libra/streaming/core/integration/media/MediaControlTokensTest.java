package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.integration.analytics.AnalyticsProperties;
import com.libra.streaming.core.integration.security.MediaServiceProperties;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.libra.streaming.core.integration.media.MediaControlTestKeys.*;

class MediaControlTokensTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    @Test void signsOneNarrowScopeWithDedicatedIdentityAndSixtySecondLifetime() throws Exception {
        var tokens = tokens(properties("https://media.example.test"), false, clock);
        for (var scope : MediaControlTokens.Scope.values()) {
            var jwt = SignedJWT.parse(tokens.issue(scope));
            assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) SERVICE.getPublic()))).isTrue();
            assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) PLAYBACK.getPublic()))).isFalse();
            assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
            assertThat(jwt.getHeader().getKeyID()).isEqualTo("core-media-test");
            var claims = jwt.getJWTClaimsSet();
            assertThat(claims.getIssuer()).isEqualTo("libra-core-services");
            assertThat(claims.getSubject()).isEqualTo("libra-core");
            assertThat(claims.getAudience()).containsExactly("libra-media-internal");
            assertThat(claims.getStringClaim("purpose")).isEqualTo("service-access");
            assertThat(claims.getStringClaim("scope")).isEqualTo(scope.value());
            assertThat(claims.getIssueTime().toInstant()).isEqualTo(clock.instant());
            assertThat(claims.getExpirationTime().toInstant()).isEqualTo(clock.instant().plusSeconds(60));
            assertThat(claims.getClaims()).doesNotContainKeys("accountId", "profileId", "role", "email", "sid");
            assertThat(SignedJWT.parse(tokens.issue(scope)).getJWTClaimsSet().getJWTID()).isNotEqualTo(claims.getJWTID());
        }
    }

    @Test void rejectsUnsafeOriginsBadKeysAndAllConfiguredCrossPurposeKeyReuse() {
        for (String origin : List.of("file:///tmp/test", "https://user:canary@host", "https://host/path", "https://host?q=canary",
                "https://host#canary", "https://user:canary@[bad")) {
            assertThatThrownBy(() -> properties(origin)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("canary");
        }
        assertThatThrownBy(() -> tokens(properties("http://media:8082"), false, clock)).hasMessageContaining("HTTPS");
        for (var invalid : List.of(new MediaControlProperties(true, "https://media.test", "canary", "canary", "test"),
                new MediaControlProperties(true, "https://media.test", privateKey(SERVICE), publicKey(PLAYBACK), "test"),
                new MediaControlProperties(true, "https://media.test", privateKey(PLAYBACK), publicKey(PLAYBACK), "test"))) {
            assertThatThrownBy(() -> tokens(invalid, false, clock)).hasMessageContaining("dedicated RSA").hasMessageNotContaining("canary");
            assertThat(invalid.toString()).doesNotContain(invalid.privateKey());
        }
        var config = properties("https://media.test");
        assertThatThrownBy(() -> new MediaControlTokens(config, identity(false), playback(),
                new AnalyticsProperties(true, URI.create("https://analytics.test"), privateKey(SERVICE), publicKey(SERVICE), "test"),
                new MediaServiceProperties(false, "", ""), clock)).hasMessageContaining("dedicated RSA");
        assertThatThrownBy(() -> new MediaControlTokens(config, identity(false), playback(),
                new AnalyticsProperties(false, null, "", "", ""),
                new MediaServiceProperties(true, publicKey(SERVICE), "test"), clock)).hasMessageContaining("dedicated RSA");
    }

    @Test void disabledConfigurationCannotMintCredentials() {
        var disabled = new MediaControlProperties(false, "", "", "", "");
        assertThatThrownBy(() -> tokens(disabled, false, clock).issue(MediaControlTokens.Scope.READ)).hasMessage("Media control is disabled");
    }
}
