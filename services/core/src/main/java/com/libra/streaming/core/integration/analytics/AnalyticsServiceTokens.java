package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.identity.IdentityProperties;
import com.libra.streaming.core.playback.PlaybackProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Outbound Core service identity only; never accepts browser identity as service authority. */
@Component
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsServiceTokens {
    public static final String ISSUER = "libra-core-services";
    public static final String AUDIENCE = "libra-recommendation-analytics";
    public static final String PURPOSE = "service-access";
    public enum Scope {
        RECOMMENDATIONS("recommendations:read"), STATISTICS("statistics:read");
        private final String value;
        Scope(String value) { this.value = value; }
        public String value() { return value; }
    }
    private final JwtEncoder encoder;
    private final AnalyticsProperties properties;
    private final Clock clock;

    public AnalyticsServiceTokens(AnalyticsProperties properties, IdentityProperties identity,
            PlaybackProperties playback, Clock clock) {
        this.properties = properties; this.clock = clock;
        if (!properties.enabled()) { encoder = null; return; }
        if (!identity.localDevelopment() && !"https".equals(properties.baseUrl().getScheme())) {
            throw new IllegalArgumentException("Analytics requires HTTPS outside explicit local development");
        }
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.privateKey())));
            var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.publicKey())));
            var playbackKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(playback.publicKey())));
            if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())
                    || publicKey.getModulus().equals(playbackKey.getModulus())) { throw new IllegalArgumentException(); }
            var probe = Signature.getInstance("SHA256withRSA");
            byte[] message = "libra-analytics-key-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            probe.initSign(privateKey); probe.update(message);
            byte[] signed = probe.sign();
            probe.initVerify(publicKey); probe.update(message);
            if (!probe.verify(signed)) { throw new IllegalArgumentException(); }
            var key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(properties.keyId()).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Analytics requires a matching dedicated RSA key pair of at least 2048 bits");
        }
    }

    public String issue(Scope scope) {
        if (encoder == null) { throw new IllegalStateException("Analytics integration is disabled"); }
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject("libra-core").audience(List.of(AUDIENCE))
                .id(UUID.randomUUID().toString()).issuedAt(issued).expiresAt(issued.plusSeconds(60))
                .claim("purpose", PURPOSE).claim("scope", scope.value()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId()).type("JWT").build(), claims)).getTokenValue();
    }
}
