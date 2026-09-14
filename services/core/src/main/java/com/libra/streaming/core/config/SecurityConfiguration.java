package com.libra.streaming.core.config;

import com.libra.streaming.core.api.ApiProblems;
import com.libra.streaming.core.identity.*;
import java.time.Clock;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiProblems problems, JwtDecoder decoder,
            IdentityStore accounts, IdentityCookies cookies, Clock clock) throws Exception {
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(cookies.csrfRepository()))
                .addFilterBefore(new IdentityAuthenticationFilter(decoder, accounts, cookies, problems, clock),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(IdentityAuthenticationFilter.PUBLIC_ROUTES.toArray(String[]::new)).permitAll()
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/v1/me", "/v1/me/**", "/v1/auth/change-password", "/v1/auth/logout-all").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(HttpStatus.UNAUTHORIZED, request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(HttpStatus.FORBIDDEN, request, response)))
                .build();
    }
}
