package com.libra.streaming.core.playback;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.entitlement.application.EntitlementOperations.EligibleContent;
import com.libra.streaming.core.identity.IdentityProperties;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Core signs; Media receives only the public key and independently validates every HLS request. */
@Component
@EnableConfigurationProperties(PlaybackProperties.class)
public class PlaybackTickets {
    public static final String ISSUER = "libra-core-playback";
    public static final String AUDIENCE = "libra-media";
    public static final String PURPOSE = "media-playback";
    private final JwtEncoder encoder;
    private final PlaybackProperties properties;
    private final IdentityProperties identity;
    private final Clock clock;
    private final Map<String, Object> publicKeys;

    public PlaybackTickets(PlaybackProperties properties, IdentityProperties identity, Clock clock) {
        this.properties = properties; this.identity = identity; this.clock = clock;
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.privateKey())));
            var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.publicKey())));
            if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalArgumentException();
            }
            // Check the pair, including the public exponent; no startup fallback or ephemeral production keys.
            var probe = Signature.getInstance("SHA256withRSA");
            byte[] message = "libra-playback-key-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            probe.initSign(privateKey); probe.update(message);
            byte[] signed = probe.sign();
            probe.initVerify(publicKey); probe.update(message);
            if (!probe.verify(signed)) { throw new IllegalArgumentException(); }
            var key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(properties.keyId()).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            publicKeys = new JWKSet(key.toPublicJWK()).toJSONObject();
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Playback requires a matching RSA key pair of at least 2048 bits");
        }
    }

    public Instant expiry(EligibleContent eligible) {
        Instant now = clock.instant();
        Instant expiry = now.plusSeconds(300);
        if (expiry.isAfter(eligible.sessionExpiresAt())) { expiry = eligible.sessionExpiresAt(); }
        if (eligible.premiumExpiresAt() != null && expiry.isAfter(eligible.premiumExpiresAt())) {
            expiry = eligible.premiumExpiresAt();
        }
        expiry = expiry.truncatedTo(ChronoUnit.SECONDS);
        if (!expiry.isAfter(now)) { throw DomainException.conflict("PLAYBACK_EXPIRING"); }
        return expiry;
    }

    public String sign(UUID sessionId, EligibleContent eligible, Instant expiresAt) {
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(sessionId.toString()).id(UUID.randomUUID().toString()).issuedAt(issued).expiresAt(expiresAt)
                .claim("purpose", PURPOSE).claim("contentId", eligible.contentId().toString())
                .claim("bindingId", eligible.bindingId().toString()).claim("assetId", eligible.assetId().toString())
                .claim("assetVersion", eligible.assetVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId()).type("JWT").build(), claims)).getTokenValue();
    }

    public Map<String, Object> publicKeys() { return publicKeys; }

    public String cookie(UUID sessionId, String ticket, Instant expiresAt) {
        return ResponseCookie.from(cookieName(), ticket).httpOnly(true).secure(identity.cookieSecure())
                .sameSite("Lax").path(streamPath(sessionId))
                .maxAge(Math.max(0, Duration.between(clock.instant(), expiresAt).toSeconds())).build().toString();
    }

    public String clearCookie(UUID sessionId) { return cookie(sessionId, "", Instant.EPOCH); }
    public String cookieName() { return identity.cookieSecure() ? "__Secure-LIBRA_PLAYBACK" : "LIBRA_PLAYBACK"; }
    public static String streamPath(UUID id) { return "/api/media/v1/streams/" + id + "/"; }
}
