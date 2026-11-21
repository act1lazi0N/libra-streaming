package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.internalapi.infrastructure.CoreServiceProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.nio.charset.StandardCharsets;
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
import java.util.Map;
import java.util.UUID;

public final class BootstrapTokens {
    private final BootstrapProperties properties;
    private final Clock clock;
    private final JWSSigner signer;

    public BootstrapTokens(BootstrapProperties properties, CoreServiceProperties core, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        if (!properties.enabled()) { signer = null; return; }
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.privateKey())));
            var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.publicKey())));
            if (publicKey.getModulus().bitLength() < 2048 || privateKey.getModulus().bitLength() < 2048
                    || !publicKey.getModulus().equals(privateKey.getModulus())) { throw new IllegalArgumentException(); }
            if (core.publicKey() != null && !core.publicKey().isBlank()) {
                var coreKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(core.publicKey())));
                if (publicKey.getModulus().equals(coreKey.getModulus())) { throw new IllegalArgumentException(); }
            }
            var probe = Signature.getInstance("SHA256withRSA");
            byte[] message = "libra-bootstrap-key-check".getBytes(StandardCharsets.US_ASCII);
            probe.initSign(privateKey);
            probe.update(message);
            byte[] signed = probe.sign();
            probe.initVerify(publicKey);
            probe.update(message);
            if (!probe.verify(signed)) { throw new IllegalArgumentException(); }
            signer = new RSASSASigner(privateKey);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Bootstrap requires a matching dedicated RSA key pair of at least 2048 bits");
        }
    }

    public String issue() {
        if (signer == null) { throw new IllegalStateException("Bootstrap is disabled"); }
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        // Preserve the contract's array audience even when it has just one member.
        var claims = Map.<String, Object>of("iss", "libra-analytics-services", "sub", "libra-analytics",
                "aud", List.of("libra-core-internal"), "purpose", "service-access",
                "scope", "analytics.bootstrap:read", "jti", UUID.randomUUID().toString(),
                "iat", issued.getEpochSecond(), "exp", issued.plusSeconds(60).getEpochSecond());
        var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(properties.keyId())
                .type(JOSEObjectType.JWT).build(), new Payload(claims));
        try {
            token.sign(signer);
            return token.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Bootstrap token signing failed");
        }
    }
}
