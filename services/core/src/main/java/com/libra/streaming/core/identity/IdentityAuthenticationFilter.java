package com.libra.streaming.core.identity;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.libra.streaming.core.api.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public class IdentityAuthenticationFilter extends OncePerRequestFilter {
    public static final Set<String> PUBLIC_ROUTES = Set.of("/v1/auth/csrf", "/v1/auth/register", "/v1/auth/login",
            "/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/forgot-password", "/v1/auth/reset-password",
            "/v1/auth/verify-email", "/v1/auth/resend-verification");
    private final JwtDecoder decoder;
    private final IdentityStore accounts;
    private final IdentityCookies cookies;
    private final ApiProblems problems;
    private final Clock clock;

    public IdentityAuthenticationFilter(JwtDecoder decoder, IdentityStore accounts, IdentityCookies cookies,
            ApiProblems problems, Clock clock) {
        this.decoder = decoder; this.accounts = accounts; this.cookies = cookies; this.problems = problems; this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_ROUTES.contains(request.getServletPath()) || request.getServletPath().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = IdentityCookies.read(request, cookies.accessName());
        if (token != null) {
            try {
                var jwt = decoder.decode(token);
                if (jwt.getSubject() == null || jwt.getClaimAsString("sid") == null) {
                    throw new IllegalArgumentException("Missing identity claims");
                }
                var principal = accounts.principal(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")),
                        clock.instant()).orElseThrow(() -> new IllegalArgumentException("Inactive session"));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException exception) {
                problems.write(HttpStatus.UNAUTHORIZED, request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
