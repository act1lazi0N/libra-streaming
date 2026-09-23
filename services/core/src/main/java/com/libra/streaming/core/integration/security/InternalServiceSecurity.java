package com.libra.streaming.core.integration.security;

import com.libra.streaming.core.api.ApiProblems;
import com.libra.streaming.core.integration.analytics.AnalyticsProperties;
import com.libra.streaming.core.playback.PlaybackProperties;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaServiceProperties.class)
public class InternalServiceSecurity {
    @Bean @Order(1)
    SecurityFilterChain internalServiceChain(HttpSecurity http, MediaServiceProperties properties,
            PlaybackProperties playback, AnalyticsProperties analytics, Clock clock, ApiProblems problems) throws Exception {
        JwtDecoder decoder = decoder(properties, playback, analytics, clock);
        return http.securityMatcher("/internal/**").sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                // This isolated chain accepts Bearer headers only and exposes no cookie-authenticated operations.
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint((request, response, exception) -> problems.write(HttpStatus.UNAUTHORIZED, request, response))
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, request, response)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/internal/v1/media/bindings/*").hasAuthority("SCOPE_media.bindings:read")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(HttpStatus.UNAUTHORIZED, request, response))
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, request, response)))
                .build();
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
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO); timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = List.of("libra-core-internal").equals(jwt.getAudience()) && "libra-media".equals(jwt.getSubject())
                    && "service-access".equals(jwt.getClaimAsString("purpose")) && properties.keyId().equals(jwt.getHeaders().get("kid"))
                    && jwt.getId() != null && !jwt.getId().isBlank() && jwt.getId().length() <= 128
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && !jwt.getIssuedAt().isAfter(clock.instant())
                    && jwt.getExpiresAt().isAfter(clock.instant())
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(Duration.ofSeconds(60)) <= 0;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator("libra-media-services"), claims));
        return decoder;
    }
    private static RSAPublicKey publicKey(KeyFactory factory, String encoded) throws java.security.GeneralSecurityException {
        return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
}
