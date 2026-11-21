package com.libra.streaming.core.integration.security;

import com.libra.streaming.core.api.ApiProblems;
import com.libra.streaming.core.integration.analytics.AnalyticsProperties;
import com.libra.streaming.core.integration.media.MediaControlProperties;
import com.libra.streaming.core.playback.PlaybackProperties;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MediaServiceProperties.class, AnalyticsServiceProperties.class, MediaControlProperties.class})
public class InternalServiceSecurity {
    static final String MEDIA_SUBJECT = "libra-media";
    static final String ANALYTICS_SUBJECT = "libra-analytics";
    static final String ANALYTICS_ISSUER = "libra-analytics-services";
    static final String ANALYTICS_SCOPE = "analytics.bootstrap:read";
    /** Each caller can only ever hold its own scope, whatever its token claims (RA02.4). */
    private static final Map<String, Set<String>> SCOPES = Map.of(
            MEDIA_SUBJECT, Set.of("media.bindings:read"), ANALYTICS_SUBJECT, Set.of(ANALYTICS_SCOPE));

    @Bean @Order(1)
    SecurityFilterChain internalServiceChain(HttpSecurity http, MediaServiceProperties properties,
            AnalyticsServiceProperties analyticsService, PlaybackProperties playback, AnalyticsProperties analytics,
            MediaControlProperties mediaControl, Clock clock, ApiProblems problems) throws Exception {
        requireDistinctFromMediaControl(analyticsService, mediaControl);
        JwtDecoder decoder = composite(properties, analyticsService, playback, analytics, clock);
        return http.securityMatcher("/internal/**").sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                // This isolated chain accepts Bearer headers only and exposes no cookie-authenticated operations.
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authorities()))
                        .authenticationEntryPoint((request, response, exception) -> problems.write(HttpStatus.UNAUTHORIZED, request, response))
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, request, response)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/internal/v1/media/bindings/*").hasAuthority("SCOPE_media.bindings:read")
                        .requestMatchers(HttpMethod.GET, "/internal/v1/analytics/snapshots/catalog",
                                "/internal/v1/analytics/snapshots/profiles", "/internal/v1/analytics/events/playback")
                                .hasAuthority("SCOPE_" + ANALYTICS_SCOPE)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(HttpStatus.UNAUTHORIZED, request, response))
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, request, response)))
                .build();
    }

    /** Authorities are the token's scopes intersected with the scopes its verified subject may hold. */
    static JwtAuthenticationConverter authorities() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<String> allowed = SCOPES.getOrDefault(jwt.getSubject(), Set.of());
            String scope = jwt.getClaimAsString("scope");
            List<GrantedAuthority> granted = new ArrayList<>();
            if (scope != null) {
                for (String value : scope.split(" ")) {
                    if (allowed.contains(value)) { granted.add(new SimpleGrantedAuthority("SCOPE_" + value)); }
                }
            }
            return granted;
        });
        return converter;
    }

    /** Selects the pinned decoder by key ID; an unknown or missing key ID is rejected before any signature check. */
    static JwtDecoder composite(MediaServiceProperties media, AnalyticsServiceProperties analyticsService,
            PlaybackProperties playback, AnalyticsProperties analytics, Clock clock) {
        JwtDecoder mediaDecoder = decoder(media, playback, analytics, clock);
        JwtDecoder analyticsDecoder = analyticsDecoder(analyticsService, media, playback, analytics, clock);
        if (media.enabled() && analyticsService.enabled() && media.keyId().equals(analyticsService.keyId())) {
            throw new IllegalArgumentException("Media and Analytics service keys require distinct key IDs");
        }
        return token -> {
            String kid;
            try { kid = SignedJWT.parse(token).getHeader().getKeyID(); }
            catch (java.text.ParseException | RuntimeException malformed) { throw new BadJwtException("Malformed service token"); }
            if (kid != null && media.enabled() && kid.equals(media.keyId())) { return mediaDecoder.decode(token); }
            if (kid != null && analyticsService.enabled() && kid.equals(analyticsService.keyId())) {
                return analyticsDecoder.decode(token);
            }
            throw new BadJwtException("Unknown service key");
        };
    }

    static JwtDecoder decoder(MediaServiceProperties properties, PlaybackProperties playback, AnalyticsProperties analytics, Clock clock) {
        if (!properties.enabled()) { return token -> { throw new BadJwtException("Service authentication is disabled"); }; }
        RSAPublicKey key;
        try {
            var factory = KeyFactory.getInstance("RSA");
            key = publicKey(factory, properties.publicKey());
            if (key.getModulus().bitLength() < 2048 || key.getModulus().equals(publicKey(factory, playback.publicKey()).getModulus())
                    || (analytics.enabled() && key.getModulus().equals(publicKey(factory, analytics.publicKey()).getModulus()))) {
                throw new IllegalArgumentException();
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Media service requires a dedicated RSA public key of at least 2048 bits");
        }
        return pinned(key, "libra-media-services", MEDIA_SUBJECT, properties.keyId(), clock);
    }

    static JwtDecoder analyticsDecoder(AnalyticsServiceProperties properties, MediaServiceProperties media,
            PlaybackProperties playback, AnalyticsProperties analytics, Clock clock) {
        if (!properties.enabled()) { return token -> { throw new BadJwtException("Service authentication is disabled"); }; }
        RSAPublicKey key;
        try {
            var factory = KeyFactory.getInstance("RSA");
            key = publicKey(factory, properties.publicKey());
            // Never the playback key, Core's own outbound Analytics key or Media's key: one key, one identity.
            if (key.getModulus().bitLength() < 2048 || key.getModulus().equals(publicKey(factory, playback.publicKey()).getModulus())
                    || (analytics.enabled() && key.getModulus().equals(publicKey(factory, analytics.publicKey()).getModulus()))
                    || (media.enabled() && key.getModulus().equals(publicKey(factory, media.publicKey()).getModulus()))) {
                throw new IllegalArgumentException();
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Analytics service requires a dedicated RSA public key of at least 2048 bits");
        }
        return pinned(key, ANALYTICS_ISSUER, ANALYTICS_SUBJECT, properties.keyId(), clock);
    }

    /**
     * Core holds the private half of its Media control key; accepting it as Analytics' key would let Core (or anyone
     * holding that key) sign bootstrap reads as Analytics.
     */
    static void requireDistinctFromMediaControl(AnalyticsServiceProperties analyticsService, MediaControlProperties control) {
        if (!analyticsService.enabled() || !control.enabled()) { return; }
        try {
            var factory = KeyFactory.getInstance("RSA");
            if (publicKey(factory, analyticsService.publicKey()).getModulus()
                    .equals(publicKey(factory, control.publicKey()).getModulus())) { throw new IllegalArgumentException(); }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Analytics service requires a dedicated RSA public key of at least 2048 bits");
        }
    }

    private static JwtDecoder pinned(RSAPublicKey key, String issuer, String subject, String keyId, Clock clock) {
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO); timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = List.of("libra-core-internal").equals(jwt.getAudience()) && subject.equals(jwt.getSubject())
                    && "service-access".equals(jwt.getClaimAsString("purpose")) && keyId.equals(jwt.getHeaders().get("kid"))
                    && jwt.getId() != null && !jwt.getId().isBlank() && jwt.getId().length() <= 128
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && !jwt.getIssuedAt().isAfter(clock.instant())
                    && jwt.getExpiresAt().isAfter(clock.instant())
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(Duration.ofSeconds(60)) <= 0;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer), claims));
        return decoder;
    }

    private static RSAPublicKey publicKey(KeyFactory factory, String encoded) throws java.security.GeneralSecurityException {
        return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
}
