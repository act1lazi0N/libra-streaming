package com.libra.streaming.core.identity;

import java.time.Clock;
import java.time.Duration;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Component
public class IdentityCookies {
    private final IdentityProperties properties;
    private final Clock clock;
    public IdentityCookies(IdentityProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

    public String accessName() { return name("LIBRA_ACCESS"); }
    public String refreshName() { return name("LIBRA_REFRESH"); }
    public String csrfName() { return name("LIBRA_CSRF"); }
    private String name(String value) { return properties.cookieSecure() ? "__Host-" + value : value; }

    public static String read(HttpServletRequest request, String name) {
        String result = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals(name)) {
                    if (result != null) { return null; }
                    result = cookie.getValue();
                }
            }
        }
        return result;
    }

    public void issue(HttpServletResponse response, IdentitySessionService.IssuedSession session) {
        write(response, accessName(), session.access(), Duration.between(clock.instant(), session.accessExpiresAt()));
        write(response, refreshName(), session.refresh(), Duration.between(clock.instant(), session.sessionExpiresAt()));
        write(response, csrfName(), "", Duration.ZERO);
    }

    public void clear(HttpServletResponse response) {
        write(response, accessName(), "", Duration.ZERO);
        write(response, refreshName(), "", Duration.ZERO);
        write(response, csrfName(), "", Duration.ZERO);
    }

    public CookieCsrfTokenRepository csrfRepository() {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName(csrfName());
        repository.setCookiePath("/");
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax"));
        return repository;
    }

    private void write(HttpServletResponse response, String name, String value, Duration duration) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).httpOnly(true)
                .secure(properties.cookieSecure()).sameSite("Lax").path("/")
                .maxAge(duration.isNegative() ? Duration.ZERO : duration).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
