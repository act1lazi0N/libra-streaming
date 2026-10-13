package com.libra.streaming.media.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Signs playback tickets the way Core's {@code PlaybackTickets.sign} does (RS256, {@code kid}, {@code typ: JWT}, the same
 * claims and types), with hooks to break exactly one property. Core's own signer lives in another service and is not on
 * Media's classpath; this is the contract mirror, not Core.
 */
public final class CoreTicketFixture {
    public static final String KID = "core-playback-test";
    private final KeyPair keys;

    public CoreTicketFixture() { this(2048); }

    public CoreTicketFixture(int bits) { keys = rsa(bits); }

    public KeyPair keyPair() { return keys; }

    public String publicKey() { return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()); }

    public PlaybackTicketProperties properties(boolean secureCookie) {
        return new PlaybackTicketProperties(true, publicKey(), KID, secureCookie);
    }

    /** A ticket Core would issue at {@code issued} for five minutes. */
    public Ticket ticket(UUID session, UUID content, UUID binding, UUID asset, long version, Instant issued) {
        return new Ticket(keys, new JWTClaimsSet.Builder().issuer(PlaybackTicketSecurity.ISSUER)
                .audience(List.of(PlaybackTicketSecurity.AUDIENCE)).subject(session.toString())
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(issued))
                .expirationTime(Date.from(issued.plusSeconds(300))).claim("purpose", PlaybackTicketSecurity.PURPOSE)
                .claim("contentId", content.toString()).claim("bindingId", binding.toString())
                .claim("assetId", asset.toString()).claim("assetVersion", version));
    }

    static KeyPair rsa(int bits) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    public static final class Ticket {
        private KeyPair signer;
        private final JWTClaimsSet.Builder claims;
        private final JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID)
                .type(JOSEObjectType.JWT);

        Ticket(KeyPair signer, JWTClaimsSet.Builder claims) {
            this.signer = signer;
            this.claims = claims;
        }

        /** Sets a claim; {@code null} removes it. */
        public Ticket claim(String name, Object value) { claims.claim(name, value); return this; }

        public Ticket expires(Instant expiry) { claims.expirationTime(Date.from(expiry)); return this; }

        public Ticket header(Consumer<JWSHeader.Builder> change) { change.accept(header); return this; }

        public Ticket signedBy(KeyPair other) { signer = other; return this; }

        public String sign() {
            try {
                var jwt = new SignedJWT(header.build(), claims.build());
                jwt.sign(new RSASSASigner(signer.getPrivate()));
                return jwt.serialize();
            } catch (Exception exception) { throw new AssertionError(exception); }
        }
    }
}
