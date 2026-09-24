package com.libra.streaming.media.security;

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
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreServiceProperties.class)
public class CoreServiceSecurity {
    private static final String READ = "core.media.uploads:read";
    private static final String WRITE = "core.media.uploads:write";

    @Bean @Order(1)
    SecurityFilterChain coreServiceChain(HttpSecurity http, CoreServiceProperties properties,
            Clock clock, MediaProblems problems) throws Exception {
        var resolver = new DefaultBearerTokenResolver();
        return http.securityMatcher("/internal/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                // Only Authorization Bearer is accepted in this isolated chain, never browser cookies.
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(request -> {
                    String value = resolver.resolve(request);
                    if (value != null && value.length() > 8192) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                    }
                    return value;
                }).jwt(jwt -> jwt.decoder(decoder(properties, clock)))
                        .authenticationEntryPoint((request, response, exception) -> problems.write(HttpStatus.UNAUTHORIZED, response))
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, response)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/internal/v1/uploads/*").hasAuthority("SCOPE_" + READ)
                        .requestMatchers(HttpMethod.PUT, "/internal/v1/uploads/*").hasAuthority("SCOPE_" + WRITE)
                        .requestMatchers(HttpMethod.POST, "/internal/v1/uploads/*/upload-url", "/internal/v1/uploads/*/complete")
                            .hasAuthority("SCOPE_" + WRITE)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(HttpStatus.UNAUTHORIZED, response))
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, response)))
                .build();
    }

    static JwtDecoder decoder(CoreServiceProperties properties, Clock clock) {
        if (!properties.enabled()) { return token -> { throw new BadJwtException("Service authentication is disabled"); }; }
        RSAPublicKey key;
        try {
            key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(properties.publicKey())));
            if (key.getModulus().bitLength() < 2048) { throw new IllegalArgumentException(); }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Core service authentication requires an RSA public key of at least 2048 bits");
        }
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            var now = clock.instant();
            boolean valid = "libra-core-services".equals(jwt.getClaims().get("iss"))
                    && "libra-core".equals(jwt.getClaims().get("sub"))
                    && List.of("libra-media-internal").equals(jwt.getClaims().get("aud"))
                    && "service-access".equals(jwt.getClaims().get("purpose"))
                    && properties.keyId().equals(jwt.getHeaders().get("kid"))
                    && jwt.getClaims().get("jti") instanceof String id && !id.isBlank() && id.length() <= 128
                    && jwt.getClaims().get("scope") instanceof String scope && Set.of(READ, WRITE).contains(scope)
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && !jwt.getIssuedAt().isAfter(now) && jwt.getExpiresAt().isAfter(now)
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(Duration.ofSeconds(60)) <= 0;
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, claims));
        return decoder;
    }
}
