package com.libra.streaming.media.security;

import com.nimbusds.jose.JWSObject;
import jakarta.servlet.http.HttpServletRequest;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Protected HLS delivery: every master playlist, rendition playlist and segment request must carry exactly one playback
 * ticket cookie that Core signed for the session named in the path. The ticket is read only from that cookie; a bearer
 * header, a query parameter or a second cookie of the same name is refused. The asset binding is checked by the
 * delivery service against Media's own READY record.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaybackTicketProperties.class)
public class PlaybackTicketSecurity {
    public static final String ISSUER = "libra-core-playback";
    public static final String AUDIENCE = "libra-media";
    public static final String PURPOSE = "media-playback";
    /** Core never issues a ticket for longer; see Core's playback contract. */
    static final Duration MAX_LIFETIME = Duration.ofSeconds(300);
    private static final int MAX_TICKET_LENGTH = 8192;

    @Bean @Order(2)
    SecurityFilterChain playbackStreamChain(HttpSecurity http, PlaybackTicketProperties properties, Clock clock,
            MediaProblems problems) throws Exception {
        return http.securityMatcher("/v1/streams/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                // Only GET is routed and it changes nothing; every other method is denied below.
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(request -> ticket(request, properties.cookieName()))
                        .jwt(jwt -> jwt.decoder(decoder(properties, clock)))
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(HttpStatus.UNAUTHORIZED, response, false))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(HttpStatus.FORBIDDEN, response, false)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/v1/streams/{sessionId}/{name}")
                            .access((authentication, context) -> new AuthorizationDecision(
                                    sessionMatches(authentication, context)))
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(HttpStatus.UNAUTHORIZED, response, false))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(HttpStatus.FORBIDDEN, response, false)))
                .build();
    }

    /** The ticket's subject is the playback session; a ticket opens only its own session's path. */
    static boolean sessionMatches(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        return authentication.get() instanceof JwtAuthenticationToken token
                && token.getToken().getSubject() != null
                && token.getToken().getSubject().equals(context.getVariables().get("sessionId"));
    }

    /**
     * The one ticket cookie, or null when there is none (the request is then anonymous and gets 401). A request that
     * also sends an Authorization header, repeats the cookie or sends an empty or oversized value is refused outright,
     * so no ambiguity is ever resolved by picking one of several credentials.
     */
    static String ticket(HttpServletRequest request, String cookieName) {
        if (request.getHeader("Authorization") != null) { throw invalid(); }
        String found = null;
        var cookies = request.getCookies();
        for (var cookie : cookies == null ? new jakarta.servlet.http.Cookie[0] : cookies) {
            if (!cookieName.equals(cookie.getName())) { continue; }
            if (found != null) { throw invalid(); }
            found = cookie.getValue();
            if (found == null || found.isEmpty() || found.length() > MAX_TICKET_LENGTH) { throw invalid(); }
        }
        return found;
    }

    /**
     * Verifies Core's ticket with only Core's pinned public key: RS256, the pinned key ID, type JWT, the exact issuer,
     * sole audience and purpose, canonical UUID identifiers, a positive integer asset version, integer-second timestamps
     * and a lifetime of at most five minutes that has started and has not reached its expiry (equality is expired).
     * Nothing in the token chooses a key or URL.
     */
    static JwtDecoder decoder(PlaybackTicketProperties properties, Clock clock) {
        if (!properties.enabled()) {
            return token -> { throw new BadJwtException("Playback delivery is disabled"); };
        }
        RSAPublicKey key;
        try {
            key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(properties.publicKey())));
            if (key.getModulus().bitLength() < 2048) { throw new IllegalArgumentException(); }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Playback delivery requires an RSA public key of at least 2048 bits");
        }
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            var now = clock.instant();
            var issued = jwt.getIssuedAt();
            var expires = jwt.getExpiresAt();
            boolean valid = properties.keyId().equals(jwt.getHeaders().get("kid"))
                    && "JWT".equals(jwt.getHeaders().get("typ"))
                    && ISSUER.equals(jwt.getClaims().get("iss"))
                    && List.of(AUDIENCE).equals(jwt.getClaims().get("aud"))
                    && PURPOSE.equals(jwt.getClaims().get("purpose"))
                    && uuid(jwt.getClaims().get("sub")) && uuid(jwt.getClaims().get("jti"))
                    && uuid(jwt.getClaims().get("contentId")) && uuid(jwt.getClaims().get("bindingId"))
                    && uuid(jwt.getClaims().get("assetId"))
                    && jwt.getClaims().get("assetVersion") instanceof Long version && version > 0
                    && issued != null && expires != null && integralSeconds(jwt.getTokenValue())
                    && !issued.isAfter(now) && expires.isAfter(now) && expires.isAfter(issued)
                    && Duration.between(issued, expires).compareTo(MAX_LIFETIME) <= 0;
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, claims));
        return decoder;
    }

    /**
     * Core writes {@code iat} and {@code exp} as JSON integers; Nimbus would silently truncate {@code 1791878699.9} or
     * read {@code 1.7918787E9}, letting a claimed lifetime over five minutes or an issue time after now pass.
     * Validators run only after the signature verified, so this re-reads a payload already known to be Core's.
     */
    private static boolean integralSeconds(String token) {
        try {
            var payload = JWSObject.parse(token).getPayload().toJSONObject();
            return payload != null && payload.get("iat") instanceof Long && payload.get("exp") instanceof Long;
        } catch (ParseException malformed) { return false; }
    }

    /** A canonical lowercase UUID, exactly as Core writes one. */
    private static boolean uuid(Object value) {
        if (!(value instanceof String text) || text.length() != 36) { return false; }
        try { return UUID.fromString(text).toString().equals(text); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
