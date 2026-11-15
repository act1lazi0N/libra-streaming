package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Test credentials shaped exactly like Core's {@code AnalyticsServiceTokens.issue}: RS256, {@code typ=JWT}, the
 * configured {@code kid}, second-precision {@code iat} and a 60-second lifetime.
 */
public final class ServiceTokens {
    public static final String KID = "core-analytics-test";
    public static final KeyPair CORE = pair(2048);

    private ServiceTokens() {}

    public static KeyPair pair(int bits) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public static String publicKey(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }

    public static JWTClaimsSet.Builder claims(String scope, Instant now) {
        Instant issued = now.truncatedTo(ChronoUnit.SECONDS);
        return new JWTClaimsSet.Builder().issuer("libra-core-services").subject("libra-core")
                .audience("libra-recommendation-analytics").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issued)).expirationTime(Date.from(issued.plusSeconds(60)))
                .claim("purpose", "service-access").claim("scope", scope);
    }

    public static String token(String scope, Consumer<JWTClaimsSet.Builder> mutation) {
        var builder = claims(scope, Instant.now());
        mutation.accept(builder);
        return sign(builder.build(), CORE, JWSAlgorithm.RS256, KID);
    }

    public static String token(String scope) { return token(scope, builder -> {}); }

    public static String sign(JWTClaimsSet claims, KeyPair key, JWSAlgorithm algorithm, String kid) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(kid).build(), claims);
            jwt.sign(new RSASSASigner(key.getPrivate()));
            return jwt.serialize();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
