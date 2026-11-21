package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.internalapi.infrastructure.CoreServiceProperties;
import com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BootstrapTokensTest {
    static final KeyPair KEY = ServiceTokens.pair(2048);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:02:03.987Z"), ZoneOffset.UTC);

    static BootstrapProperties properties(KeyPair privatePair, KeyPair publicPair, URI origin) {
        return new BootstrapProperties(true, origin, Base64.getEncoder().encodeToString(privatePair.getPrivate().getEncoded()),
                ServiceTokens.publicKey(publicPair), "bootstrap-test", 1, 200);
    }

    static BootstrapTokens tokens(BootstrapProperties properties) {
        return new BootstrapTokens(properties, new CoreServiceProperties(false, "", ""), CLOCK);
    }

    @Test
    void signsExactlyTheRequiredHeaderAndClaimsWithFreshIds() throws Exception {
        var tokens = tokens(properties(KEY, KEY, URI.create("http://core")));
        String encoded = tokens.issue();
        var jwt = SignedJWT.parse(encoded);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) KEY.getPublic()))).isTrue();
        assertThat(jwt.getHeader().toJSONObject()).containsExactlyInAnyOrderEntriesOf(
                Map.of("alg", "RS256", "kid", "bootstrap-test", "typ", "JWT"));
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getClaims()).containsOnlyKeys("iss", "sub", "aud", "purpose", "scope", "jti", "iat", "exp");
        assertThat(claims.getIssuer()).isEqualTo("libra-analytics-services");
        assertThat(claims.getSubject()).isEqualTo("libra-analytics");
        assertThat(claims.getAudience()).containsExactly("libra-core-internal");
        assertThat(claims.getStringClaim("purpose")).isEqualTo("service-access");
        assertThat(claims.getStringClaim("scope")).isEqualTo("analytics.bootstrap:read");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-11-16T01:02:03Z"));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(claims.getIssueTime().toInstant().plusSeconds(60));
        assertThat(UUID.fromString(claims.getJWTID()).toString()).isEqualTo(claims.getJWTID());
        assertThat(SignedJWT.parse(tokens.issue()).getJWTClaimsSet().getJWTID()).isNotEqualTo(claims.getJWTID());
        var raw = JsonMapper.builder().build().readTree(jwt.getPayload().toString());
        assertThat(raw.get("aud").isArray()).isTrue();
        assertThat(raw.get("iat").isIntegralNumber()).isTrue();
        assertThat(raw.get("exp").longValue() - raw.get("iat").longValue()).isEqualTo(60);
    }

    @Test
    void rejectsWeakMismatchedAndReusedKeysWithoutDisclosingMaterial() {
        var weak = ServiceTokens.pair(1024);
        var other = ServiceTokens.pair(2048);
        for (var properties : List.of(properties(weak, weak, URI.create("http://core")),
                properties(KEY, other, URI.create("http://core")))) {
            assertThatThrownBy(() -> tokens(properties)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap requires a matching dedicated RSA key pair of at least 2048 bits")
                    .hasMessageNotContaining(properties.privateKey()).hasMessageNotContaining(properties.publicKey());
        }
        var properties = properties(KEY, KEY, URI.create("http://core"));
        assertThatThrownBy(() -> new BootstrapTokens(properties,
                new CoreServiceProperties(false, properties.publicKey(), "core-test"), CLOCK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(properties.privateKey())
                .hasMessageNotContaining(properties.publicKey());
        assertThat(new BootstrapTokens(properties, new CoreServiceProperties(true, ServiceTokens.publicKey(other), "core"), CLOCK)
                .issue()).isNotBlank();
    }

    @Test
    void rejectsMalformedEncodingsAndAllowsDisabledConstruction() {
        var bad = new BootstrapProperties(true, URI.create("http://core"), "private-canary", "public-canary", "kid", 1, 200);
        assertThatThrownBy(() -> tokens(bad)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-canary").hasMessageNotContaining("public-canary");
        var disabled = tokens(new BootstrapProperties(false, null, "", "", "", 1, 200));
        assertThatThrownBy(disabled::issue).isInstanceOf(IllegalStateException.class).hasMessage("Bootstrap is disabled");
    }
}
