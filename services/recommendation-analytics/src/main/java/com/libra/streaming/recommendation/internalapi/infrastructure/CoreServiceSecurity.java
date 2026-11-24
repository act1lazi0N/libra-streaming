package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.internalapi.api.Problems;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Service authentication for {@code /internal/**}: only Core's dedicated RS256 service JWT is a credential here.
 * The rules mirror Media's receiver of the same Core issuer and are fixed by analytics-internal.v1.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreServiceProperties.class)
public class CoreServiceSecurity {
    static final String ISSUER = "libra-core-services";
    static final String SUBJECT = "libra-core";
    static final String AUDIENCE = "libra-recommendation-analytics";
    static final String PURPOSE = "service-access";
    static final String RECOMMENDATIONS = "recommendations:read";
    static final String STATISTICS = "statistics:read";
    static final Duration MAX_LIFETIME = Duration.ofSeconds(60);
    private static final int MAX_TOKEN_LENGTH = 8192;

    @Bean @Order(1)
    SecurityFilterChain coreServiceChain(HttpSecurity http, CoreServiceProperties properties, Clock clock,
            Problems problems) throws Exception {
        var resolver = new DefaultBearerTokenResolver();
        return http.securityMatcher("/internal/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                // Only Authorization Bearer is accepted in this isolated chain, never browser cookies.
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(request -> {
                    var headers = request.getHeaders("Authorization");
                    if (headers != null && headers.hasMoreElements()) {
                        headers.nextElement();
                        if (headers.hasMoreElements()) {
                            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                        }
                    }
                    String value = resolver.resolve(request);
                    if (value != null && value.length() > MAX_TOKEN_LENGTH) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                    }
                    return value;
                }).jwt(jwt -> jwt.decoder(decoder(properties, clock)))
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(Problems.Code.AUTHENTICATION_REQUIRED, request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(Problems.Code.ACCESS_DENIED, request, response)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/internal/v1/recommendations/query",
                                "/internal/v2/recommendations/query")
                            .hasAuthority("SCOPE_" + RECOMMENDATIONS)
                        .requestMatchers(HttpMethod.POST, "/internal/v1/statistics/query",
                                "/internal/v2/statistics/query")
                            .hasAuthority("SCOPE_" + STATISTICS)
                        // Unknown operations and methods answer 404/405, but only to an authenticated service.
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(Problems.Code.AUTHENTICATION_REQUIRED, request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(Problems.Code.ACCESS_DENIED, request, response)))
                .build();
    }

    static JwtDecoder decoder(CoreServiceProperties properties, Clock clock) {
        if (!properties.enabled()) { return token -> { throw new BadJwtException("Service authentication is disabled"); }; }
        RSAPublicKey key;
        try {
            key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(properties.publicKey())));
            if (key.getModulus().bitLength() < 2048) { throw new IllegalArgumentException(); }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException | ClassCastException exception) {
            // The cause is dropped on purpose: its message can quote the configured key material.
            throw new IllegalArgumentException("Core service authentication requires an RSA public key of at least 2048 bits");
        }
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            var now = clock.instant();
            boolean valid = ISSUER.equals(jwt.getClaims().get("iss"))
                    && SUBJECT.equals(jwt.getClaims().get("sub"))
                    && List.of(AUDIENCE).equals(jwt.getClaims().get("aud"))
                    && PURPOSE.equals(jwt.getClaims().get("purpose"))
                    && properties.keyId().equals(jwt.getHeaders().get("kid"))
                    && jwt.getClaims().get("jti") instanceof String id && !id.isBlank() && id.length() <= 128
                    && jwt.getClaims().get("scope") instanceof String scope
                    && Set.of(RECOMMENDATIONS, STATISTICS).contains(scope)
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && !jwt.getIssuedAt().isAfter(now) && jwt.getExpiresAt().isAfter(now)
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(MAX_LIFETIME) <= 0;
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, claims));
        return decoder;
    }
}
