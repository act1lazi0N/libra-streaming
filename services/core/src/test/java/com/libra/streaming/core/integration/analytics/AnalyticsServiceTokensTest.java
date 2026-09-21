package com.libra.streaming.core.integration.analytics;

import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.libra.streaming.core.integration.analytics.AnalyticsServiceTokens.*;

class AnalyticsServiceTokensTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);
    @Test void issuesDedicatedShortLivedLeastPrivilegeServiceIdentity() throws Exception {
        var tokens = new AnalyticsServiceTokens(AnalyticsTestKeys.properties(URI.create("https://analytics.example.test")),
                AnalyticsTestKeys.identity(false), AnalyticsTestKeys.playback(), clock);
        for (Scope scope : Scope.values()) {
            String raw = tokens.issue(scope);
            var token = SignedJWT.parse(raw); var claims = token.getJWTClaimsSet();
            assertThat(token.verify(new RSASSAVerifier((RSAPublicKey) AnalyticsTestKeys.SERVICE.getPublic()))).isTrue();
            assertThat(token.verify(new RSASSAVerifier((RSAPublicKey) AnalyticsTestKeys.PLAYBACK.getPublic()))).isFalse();
            assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
            assertThat(token.getHeader().getKeyID()).isEqualTo("analytics-fixture");
            assertThat(claims.getIssuer()).isEqualTo(ISSUER); assertThat(claims.getSubject()).isEqualTo("libra-core");
            assertThat(claims.getAudience()).isEqualTo(List.of(AUDIENCE));
            assertThat(claims.getStringClaim("purpose")).isEqualTo(PURPOSE);
            assertThat(claims.getStringClaim("scope")).isEqualTo(scope.value());
            assertThat(claims.getIssueTime().toInstant()).isEqualTo(clock.instant());
            assertThat(claims.getExpirationTime().toInstant()).isEqualTo(clock.instant().plusSeconds(60));
            assertThat(claims.getClaims()).doesNotContainKeys("email", "accountId", "profileId", "sid");
            assertThat(SignedJWT.parse(tokens.issue(scope)).getJWTClaimsSet().getJWTID()).isNotEqualTo(claims.getJWTID());
        }
    }
    @Test void enabledConfigurationRejectsUnsafeOriginsAndBadOrReusedKeysWithoutLeakingThem() {
        for (String uri : List.of("file:///tmp/test", "https://user:secret@example.test", "https://example.test/path",
                "https://example.test?x=y", "https://example.test#fragment")) {
            assertThatThrownBy(() -> AnalyticsTestKeys.properties(URI.create(uri))).isInstanceOf(IllegalArgumentException.class);
        }
        var identity = AnalyticsTestKeys.identity(false); var playback = AnalyticsTestKeys.playback();
        assertThatThrownBy(() -> new AnalyticsServiceTokens(AnalyticsTestKeys.properties(URI.create("http://localhost:8083")),
                identity, playback, clock)).hasMessageContaining("HTTPS");
        for (AnalyticsProperties invalid : List.of(
                new AnalyticsProperties(true, URI.create("https://example.test"), "invalid", "invalid", "test"),
                new AnalyticsProperties(true, URI.create("https://example.test"), playback.privateKey(), playback.publicKey(), "test"),
                new AnalyticsProperties(true, URI.create("https://example.test"), AnalyticsTestKeys.privateKey(AnalyticsTestKeys.SERVICE), playback.publicKey(), "test"))) {
            assertThatThrownBy(() -> new AnalyticsServiceTokens(invalid, identity, playback, clock))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedicated RSA")
                    .hasMessageNotContaining(invalid.privateKey());
            assertThat(invalid.toString()).doesNotContain(invalid.privateKey());
        }
    }
    @Test void disabledIntegrationNeedsNoServiceSecretsAndCannotIssueTokens() {
        var tokens = new AnalyticsServiceTokens(new AnalyticsProperties(false, null, "", "", ""),
                AnalyticsTestKeys.identity(true), AnalyticsTestKeys.playback(), clock);
        assertThatThrownBy(() -> tokens.issue(Scope.RECOMMENDATIONS)).hasMessageContaining("disabled");
    }
}
