package com.libra.streaming.core.playback;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.catalog.CatalogModels.Tier;
import com.libra.streaming.core.entitlement.EntitlementService.EligibleContent;
import com.libra.streaming.core.identity.IdentityProperties;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

/** An independent Media contract fixture, not production HLS delivery or a Media implementation. */
class PlaybackTicketContractTest {
    static KeyPair keys;
    static KeyPair foreignKeys;
    final Instant now = Instant.parse("2026-09-18T12:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final UUID session = UUID.randomUUID();
    final EligibleContent content = new EligibleContent(UUID.randomUUID(), UUID.randomUUID(), Tier.FREE, 1,
            UUID.randomUUID(), UUID.randomUUID(), 2, 120, now, now.plusSeconds(3600), null);

    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys = generator.generateKeyPair(); foreignKeys = generator.generateKeyPair();
    }

    @Test void publicOnlyMediaVerifierAcceptsExactSessionAndBindingAcrossSignerRestart() throws Exception {
        var issuer = issuer(keys);
        String ticket = issuer.sign(session, content, issuer.expiry(content));
        var publicSet = JWKSet.parse(issuer.publicKeys());
        assertThat(publicSet.getKeys()).hasSize(1);
        assertThat(publicSet.getKeys().getFirst().isPrivate()).isFalse();
        assertThat(verify(ticket, session, content, clock).getSubject()).isEqualTo(session.toString());
        assertThat(verify(ticket, session, content, clock).getClaimAsString("assetId")).isEqualTo(content.assetId().toString());
        var recreated = issuer(keys);
        assertThat(verify(recreated.sign(session, content, now.plusSeconds(300)), session, content, clock)).isNotNull();
    }

    @Test void wrongSignatureTamperingSessionAssetVersionOrExpiryCannotAuthorizeDelivery() {
        String ticket = issuer(keys).sign(session, content, now.plusSeconds(300));
        assertThatThrownBy(() -> verify(ticket, UUID.randomUUID(), content, clock)).isInstanceOf(JwtException.class);
        var otherAsset = new EligibleContent(content.profileId(), content.contentId(), Tier.FREE, 1, content.bindingId(),
                content.assetId(), 3, 120, now, content.sessionExpiresAt(), null);
        assertThatThrownBy(() -> verify(ticket, session, otherAsset, clock)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verify(ticket, session, content, Clock.fixed(now.plusSeconds(300), ZoneOffset.UTC)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verify(issuer(foreignKeys).sign(session, content, now.plusSeconds(300)), session, content, clock))
                .isInstanceOf(JwtException.class);
        var parts = ticket.split("\\.");
        var payload = new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replace(session.toString(), UUID.randomUUID().toString())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = String.join(".", parts);
        assertThatThrownBy(() -> verify(tampered, session, content, clock)).isInstanceOf(JwtException.class);
    }

    @Test void validSignatureDoesNotWaivePurposeAudienceIssuerKidOrLifetimeChecks() {
        for (String fault : List.of("purpose", "audience", "issuer", "kid", "future", "long", "missingExpiry", "missingIssued", "binding")) {
            var claims = JwtClaimsSet.builder().issuer(fault.equals("issuer") ? "other" : PlaybackTickets.ISSUER)
                    .audience(List.of(fault.equals("audience") ? "libra-web" : PlaybackTickets.AUDIENCE))
                    .subject(session.toString()).claim("purpose", fault.equals("purpose") ? "access" : PlaybackTickets.PURPOSE)
                    .claim("contentId", content.contentId().toString())
                    .claim("bindingId", fault.equals("binding") ? UUID.randomUUID().toString() : content.bindingId().toString())
                    .claim("assetId", content.assetId().toString()).claim("assetVersion", content.assetVersion());
            if (!fault.equals("missingIssued")) { claims.issuedAt(fault.equals("future") ? now.plusSeconds(1) : now); }
            if (!fault.equals("missingExpiry")) { claims.expiresAt(now.plusSeconds(fault.equals("long") ? 301 : 300)); }
            var key = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate())
                    .keyID(fault.equals("kid") ? "unknown" : "test-key").build();
            var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                    .keyId(key.getKeyID()).build(), claims.build())).getTokenValue();
            assertThatThrownBy(() -> verify(token, session, content, clock)).as(fault).isInstanceOf(JwtException.class);
        }
    }

    @Test void secureCookiesHaveExactExternalPathAndNeverExposeKeysOrTicketInToString() {
        var issuer = issuer(keys);
        String token = issuer.sign(session, content, now.plusSeconds(300));
        assertThat(issuer.cookie(session, token, now.plusSeconds(300)))
                .contains("__Secure-LIBRA_PLAYBACK=", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=300",
                        "Path=/api/media/v1/streams/" + session + "/").doesNotContain("Domain=");
        assertThat(issuer.clearCookie(session)).contains("Max-Age=0");
        assertThat(properties(keys).toString()).doesNotContain(Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        assertThat(new PlaybackModels.Issued(null, token).toString()).doesNotContain(token);
    }

    @Test void missingMalformedMismatchedOrWeakSigningKeysFailClosed() throws Exception {
        assertThatThrownBy(() -> new PlaybackProperties("", "", "key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaybackTickets(new PlaybackProperties("invalid", "invalid", "key"), identity(), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaybackTickets(new PlaybackProperties(properties(keys).privateKey(),
                properties(foreignKeys).publicKey(), "key"), identity(), clock)).isInstanceOf(IllegalArgumentException.class);
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(1024);
        var weak = generator.generateKeyPair();
        assertThatThrownBy(() -> issuer(weak)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void subsecondPremiumBoundaryCannotProduceAnAlreadyExpiredTicket() {
        var eligible = new EligibleContent(content.profileId(), content.contentId(), Tier.PREMIUM, 1, content.bindingId(),
                content.assetId(), 2, 120, now, content.sessionExpiresAt(), now.plusMillis(500));
        assertThatThrownBy(() -> issuer(keys).expiry(eligible)).hasMessage("PLAYBACK_EXPIRING");
    }

    private PlaybackTickets issuer(KeyPair pair) { return new PlaybackTickets(properties(pair), identity(), clock); }
    private static PlaybackProperties properties(KeyPair pair) {
        return new PlaybackProperties(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), "test-key");
    }
    private IdentityProperties identity() {
        return new IdentityProperties(TestIdentityProperties.key(), TestIdentityProperties.key(), "libra-core", "libra-web",
                URI.create("https://localhost"), false, true, "mail@example.test", "", "");
    }

    private Jwt verify(String token, UUID expectedSession, EligibleContent expected, Clock mediaClock) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> policy = jwt -> {
            Instant time = mediaClock.instant();
            boolean valid = "test-key".equals(jwt.getHeaders().get("kid"))
                    && PlaybackTickets.ISSUER.equals(jwt.getClaimAsString("iss"))
                    && List.of(PlaybackTickets.AUDIENCE).equals(jwt.getAudience())
                    && PlaybackTickets.PURPOSE.equals(jwt.getClaimAsString("purpose"))
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && !jwt.getIssuedAt().isAfter(time) && jwt.getExpiresAt().isAfter(time)
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(Duration.ofSeconds(300)) <= 0
                    && expectedSession.toString().equals(jwt.getSubject())
                    && expected.contentId().toString().equals(jwt.getClaimAsString("contentId"))
                    && expected.bindingId().toString().equals(jwt.getClaimAsString("bindingId"))
                    && expected.assetId().toString().equals(jwt.getClaimAsString("assetId"))
                    && Long.valueOf(expected.assetVersion()).equals(jwt.getClaim("assetVersion"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(policy);
        return decoder.decode(token);
    }
}
