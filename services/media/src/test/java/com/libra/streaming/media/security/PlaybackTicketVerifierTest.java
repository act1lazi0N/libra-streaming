package com.libra.streaming.media.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import static org.assertj.core.api.Assertions.*;

/** The ticket verifier alone, with a fixed clock: every property Core's contract names is checked independently. */
class PlaybackTicketVerifierTest {
    static final CoreTicketFixture CORE = new CoreTicketFixture();
    static final CoreTicketFixture FOREIGN = new CoreTicketFixture();
    final Instant now = Instant.parse("2026-10-13T08:00:00Z");
    final UUID session = UUID.randomUUID();
    final UUID content = UUID.randomUUID();
    final UUID binding = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();

    JwtDecoder decoderAt(Instant instant) {
        return PlaybackTicketSecurity.decoder(CORE.properties(true), Clock.fixed(instant, ZoneOffset.UTC));
    }

    CoreTicketFixture.Ticket ticket() { return CORE.ticket(session, content, binding, asset, 2, now); }

    @Test
    void aTicketCoreWouldIssueIsAcceptedForItsWholeLifetimeAndCarriesTheExactSelection() {
        String token = ticket().sign();
        for (var instant : List.of(now, now.plusSeconds(150), now.plusSeconds(299), now.plusMillis(299_999))) {
            var jwt = decoderAt(instant).decode(token);
            assertThat(jwt.getSubject()).isEqualTo(session.toString());
            assertThat(jwt.getClaimAsString("contentId")).isEqualTo(content.toString());
            assertThat(jwt.getClaimAsString("bindingId")).isEqualTo(binding.toString());
            assertThat(jwt.getClaimAsString("assetId")).isEqualTo(asset.toString());
            assertThat(jwt.<Long>getClaim("assetVersion")).isEqualTo(2L);
        }
    }

    @Test
    void expiryIsStrictAndATicketCannotBeUsedBeforeItWasIssuedOrLiveLongerThanFiveMinutes() {
        String token = ticket().sign();
        assertThatThrownBy(() -> decoderAt(now.plusSeconds(300)).decode(token)).as("equality is expired")
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoderAt(now.plusSeconds(301)).decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoderAt(now.minusSeconds(1)).decode(token)).as("issued in the future")
                .isInstanceOf(JwtException.class);
        var decoder = decoderAt(now);
        assertThatThrownBy(() -> decoder.decode(ticket().expires(now.plusSeconds(301)).sign())).as("301 s lifetime")
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(ticket().expires(now).sign())).as("exp equal to iat")
                .isInstanceOf(JwtException.class);
        assertThat(decoder.decode(ticket().expires(now.plusSeconds(1)).sign())).as("a capped, shorter ticket")
                .isNotNull();
    }

    @Test
    void aValidSignatureWaivesNoClaim() {
        var other = UUID.randomUUID();
        Map<String, UnaryOperator<CoreTicketFixture.Ticket>> faults = new LinkedHashMap<>();
        faults.put("issuer", t -> t.claim("iss", "libra-core"));
        faults.put("identity issuer", t -> t.claim("iss", "libra-core-services"));
        faults.put("no issuer", t -> t.claim("iss", null));
        faults.put("audience", t -> t.claim("aud", List.of("libra-web")));
        faults.put("extra audience", t -> t.claim("aud", List.of("libra-media", "libra-web")));
        faults.put("internal audience", t -> t.claim("aud", List.of("libra-media-internal")));
        faults.put("no audience", t -> t.claim("aud", null));
        faults.put("purpose", t -> t.claim("purpose", "service-access"));
        faults.put("no purpose", t -> t.claim("purpose", null));
        faults.put("no subject", t -> t.claim("sub", null));
        faults.put("subject not a UUID", t -> t.claim("sub", "session"));
        faults.put("subject upper case", t -> t.claim("sub", session.toString().toUpperCase()));
        faults.put("no jti", t -> t.claim("jti", null));
        faults.put("jti not a UUID", t -> t.claim("jti", "1"));
        faults.put("no issued at", t -> t.claim("iat", null));
        faults.put("no expiry", t -> t.claim("exp", null));
        for (var claim : List.of("contentId", "bindingId", "assetId")) {
            faults.put("no " + claim, t -> t.claim(claim, null));
            faults.put(claim + " not a UUID", t -> t.claim(claim, "x"));
            faults.put(claim + " not canonical", t -> t.claim(claim, other.toString().toUpperCase()));
            faults.put(claim + " as a number", t -> t.claim(claim, 1));
        }
        faults.put("no version", t -> t.claim("assetVersion", null));
        faults.put("version zero", t -> t.claim("assetVersion", 0L));
        faults.put("negative version", t -> t.claim("assetVersion", -2L));
        faults.put("version as text", t -> t.claim("assetVersion", "2"));
        faults.put("fractional version", t -> t.claim("assetVersion", 2.5));
        faults.put("unknown key ID", t -> t.header(h -> h.keyID("other")));
        faults.put("no key ID", t -> t.header(h -> h.keyID(null)));
        faults.put("no type", t -> t.header(h -> h.type(null)));
        faults.put("access token type", t -> t.header(h -> h.type(new JOSEObjectType("at+jwt"))));
        faults.put("foreign signer", t -> t.signedBy(FOREIGN.keyPair()));
        var decoder = decoderAt(now);
        for (var fault : faults.entrySet()) {
            String token = fault.getValue().apply(ticket()).sign();
            assertThatThrownBy(() -> decoder.decode(token)).as(fault.getKey()).isInstanceOf(JwtException.class);
        }
        assertThat(faults).hasSize(38);
    }

    @Test
    void tamperingUnsignedAndSymmetricTokensAreRefused() throws Exception {
        var decoder = decoderAt(now);
        String token = ticket().sign();
        var parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replace(asset.toString(),
                UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
        String tampered = String.join(".", parts);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);

        var claims = JWTClaimsSet.parse(payload);
        String unsigned = new PlainJWT(claims).serialize();
        assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
        // An identity-style HS256 token keyed with the public key's bytes: the classic algorithm confusion.
        var hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(CoreTicketFixture.KID)
                .type(JOSEObjectType.JWT).build(), claims);
        hmac.sign(new MACSigner(Base64.getDecoder().decode(CORE.publicKey())));
        assertThatThrownBy(() -> decoder.decode(hmac.serialize())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void disabledOrMisconfiguredVerificationFailsClosed() {
        var disabled = new PlaybackTicketProperties(false, null, null, true);
        assertThatThrownBy(() -> PlaybackTicketSecurity.decoder(disabled, Clock.systemUTC()).decode(ticket().sign()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> new PlaybackTicketProperties(true, "", CoreTicketFixture.KID, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaybackTicketProperties(true, CORE.publicKey(), "bad kid", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaybackTicketSecurity.decoder(
                new PlaybackTicketProperties(true, "not-a-key", CoreTicketFixture.KID, true), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        var weak = new CoreTicketFixture(1024);
        assertThatThrownBy(() -> PlaybackTicketSecurity.decoder(weak.properties(true), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CORE.properties(true).toString()).doesNotContain(CORE.publicKey());
        assertThat(CORE.properties(true).cookieName()).isEqualTo("__Secure-LIBRA_PLAYBACK");
        assertThat(CORE.properties(false).cookieName()).isEqualTo("LIBRA_PLAYBACK");
    }

    @Test
    void theTicketComesFromExactlyOneCookieAndNowhereElse() {
        String name = "__Secure-LIBRA_PLAYBACK";
        assertThat(PlaybackTicketSecurity.ticket(request(), name)).isNull();
        assertThat(PlaybackTicketSecurity.ticket(request(new Cookie("LIBRA_PLAYBACK", "t")), name)).isNull();
        assertThat(PlaybackTicketSecurity.ticket(request(new Cookie("other", "x"), new Cookie(name, "t")), name))
                .isEqualTo("t");
        var duplicated = request(new Cookie(name, "t"), new Cookie(name, "t"));
        assertThatThrownBy(() -> PlaybackTicketSecurity.ticket(duplicated, name))
                .isInstanceOf(OAuth2AuthenticationException.class);
        var empty = request(new Cookie(name, ""));
        assertThatThrownBy(() -> PlaybackTicketSecurity.ticket(empty, name))
                .isInstanceOf(OAuth2AuthenticationException.class);
        var oversized = request(new Cookie(name, "a".repeat(8193)));
        assertThatThrownBy(() -> PlaybackTicketSecurity.ticket(oversized, name))
                .isInstanceOf(OAuth2AuthenticationException.class);
        var bearer = request(new Cookie(name, "t"));
        bearer.addHeader("Authorization", "Bearer t");
        assertThatThrownBy(() -> PlaybackTicketSecurity.ticket(bearer, name))
                .isInstanceOf(OAuth2AuthenticationException.class);
        var bearerOnly = request();
        bearerOnly.addHeader("Authorization", "Bearer t");
        assertThatThrownBy(() -> PlaybackTicketSecurity.ticket(bearerOnly, name))
                .isInstanceOf(OAuth2AuthenticationException.class);
        var query = request();
        query.setParameter("access_token", "t");
        assertThat(PlaybackTicketSecurity.ticket(query, name)).isNull();
    }

    @Test
    void theChainOpensOnlyThePathOfTheTicketsOwnSession() {
        var jwt = decoderAt(now).decode(ticket().sign());
        var request = request();
        Supplier<Authentication> verified = () -> new JwtAuthenticationToken(jwt);
        assertThat(PlaybackTicketSecurity.sessionMatches(verified,
                new RequestAuthorizationContext(request, Map.of("sessionId", session.toString())))).isTrue();
        assertThat(PlaybackTicketSecurity.sessionMatches(verified,
                new RequestAuthorizationContext(request, Map.of("sessionId", UUID.randomUUID().toString())))).isFalse();
        assertThat(PlaybackTicketSecurity.sessionMatches(verified, new RequestAuthorizationContext(request,
                Map.of("sessionId", session.toString().toUpperCase())))).isFalse();
        assertThat(PlaybackTicketSecurity.sessionMatches(verified, new RequestAuthorizationContext(request, Map.of())))
                .isFalse();
        Supplier<Authentication> anonymous = () -> new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(PlaybackTicketSecurity.sessionMatches(anonymous,
                new RequestAuthorizationContext(request, Map.of("sessionId", session.toString())))).isFalse();
    }

    private static MockHttpServletRequest request(Cookie... cookies) {
        var request = new MockHttpServletRequest("GET", "/v1/streams/s/master.m3u8");
        if (cookies.length > 0) { request.setCookies(cookies); }
        return request;
    }
}
