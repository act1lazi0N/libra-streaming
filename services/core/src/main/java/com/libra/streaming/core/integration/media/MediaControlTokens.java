package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.identity.IdentityProperties;
import com.libra.streaming.core.integration.analytics.AnalyticsProperties;
import com.libra.streaming.core.integration.security.MediaServiceProperties;
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

@Component
@EnableConfigurationProperties(MediaControlProperties.class)
public class MediaControlTokens {
    public enum Scope {
        READ("core.media.uploads:read"), WRITE("core.media.uploads:write");
        private final String value;
        Scope(String value) { this.value = value; }
        public String value() { return value; }
    }
    private final JwtEncoder encoder;
    private final MediaControlProperties properties;
    private final Clock clock;

    public MediaControlTokens(MediaControlProperties properties, IdentityProperties identity,
            PlaybackProperties playback, AnalyticsProperties analytics, MediaServiceProperties inbound, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        if (!properties.enabled()) { encoder = null; return; }
        if (!identity.localDevelopment() && !"https".equals(properties.origin().getScheme())) {
            throw new IllegalArgumentException("Media control requires HTTPS outside explicit local development");
        }
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.privateKey())));
            var publicKey = publicKey(factory, properties.publicKey());
            if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())
                    || publicKey.getModulus().equals(publicKey(factory, playback.publicKey()).getModulus())
                    || (analytics.enabled() && publicKey.getModulus().equals(publicKey(factory, analytics.publicKey()).getModulus()))
                    || (inbound.enabled() && publicKey.getModulus().equals(publicKey(factory, inbound.publicKey()).getModulus()))) {
                throw new IllegalArgumentException();
            }
            var probe = Signature.getInstance("SHA256withRSA");
            byte[] message = "libra-media-control-key-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            probe.initSign(privateKey); probe.update(message);
            byte[] signed = probe.sign();
            probe.initVerify(publicKey); probe.update(message);
            if (!probe.verify(signed)) { throw new IllegalArgumentException(); }
            var key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(properties.keyId()).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Media control requires a matching dedicated RSA key pair of at least 2048 bits");
        }
    }

    public String issue(Scope scope) {
        if (encoder == null) { throw new IllegalStateException("Media control is disabled"); }
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer("libra-core-services").subject("libra-core")
                .audience(List.of("libra-media-internal")).id(UUID.randomUUID().toString())
                .issuedAt(issued).expiresAt(issued.plusSeconds(60))
                .claim("purpose", "service-access").claim("scope", scope.value()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId()).type("JWT").build(), claims)).getTokenValue();
    }

    private static RSAPublicKey publicKey(KeyFactory factory, String encoded) throws java.security.GeneralSecurityException {
        return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
}
