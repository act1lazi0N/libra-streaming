package com.libra.streaming.core.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class IdentityController {
    private final IdentityAccountService accounts;
    private final IdentitySessionService sessions;
    private final IdentityStore store;
    private final IdentityRateLimiter limits;
    private final IdentityCookies cookies;

    public IdentityController(IdentityAccountService accounts, IdentitySessionService sessions,
            IdentityStore store, IdentityRateLimiter limits, IdentityCookies cookies) {
        this.accounts = accounts; this.sessions = sessions; this.store = store; this.limits = limits; this.cookies = cookies;
    }

    @GetMapping("/auth/csrf")
    CsrfView csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Accepted register(@Valid @RequestBody Register request, HttpServletRequest http) {
        emailLimit("register", request.email(), http);
        accounts.register(request.email(), request.displayName(), request.password());
        return new Accepted("ACCEPTED");
    }

    @PostMapping("/auth/login")
    SessionResult login(@Valid @RequestBody Login request, HttpServletRequest http, HttpServletResponse response) {
        limits.check("login-ip", http.getRemoteAddr(), 30, Duration.ofMinutes(1));
        limits.check("login-email", IdentityAccountService.normalizeEmail(request.email()), 10, Duration.ofMinutes(15));
        var result = sessions.login(request.email(), request.password());
        cookies.issue(response, result);
        return new SessionResult(result.accessExpiresAt(), result.sessionExpiresAt());
    }

    @PostMapping("/auth/refresh")
    SessionResult refresh(HttpServletRequest request, HttpServletResponse response) {
        limits.check("refresh-ip", request.getRemoteAddr(), 60, Duration.ofMinutes(1));
        var issued = sessions.refresh(IdentityCookies.read(request, cookies.refreshName()));
        if (issued.isEmpty()) {
            cookies.clear(response);
            throw new IdentityException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION");
        }
        cookies.issue(response, issued.get());
        return new SessionResult(issued.get().accessExpiresAt(), issued.get().sessionExpiresAt());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(IdentityCookies.read(request, cookies.refreshName()));
        cookies.clear(response);
    }

    @PostMapping("/auth/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll(@AuthenticationPrincipal IdentityPrincipal principal, HttpServletResponse response) {
        sessions.logoutAll(principal);
        cookies.clear(response);
    }

    @GetMapping("/me")
    IdentityStore.AccountView me(@AuthenticationPrincipal IdentityPrincipal principal, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return store.view(principal.accountId());
    }

    @GetMapping("/me/sessions")
    List<IdentityStore.SessionView> sessions(@AuthenticationPrincipal IdentityPrincipal principal,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return store.sessions(principal.accountId(), principal.sessionId(), limit, offset);
    }

    @DeleteMapping("/me/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal IdentityPrincipal principal, @PathVariable UUID id, HttpServletResponse response) {
        sessions.revokeOwned(principal, id);
        if (id.equals(principal.sessionId())) { cookies.clear(response); }
    }

    @PostMapping("/auth/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Accepted forgot(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        emailLimit("reset", request.email(), http);
        accounts.requestEmail(request.email(), "RESET");
        return new Accepted("ACCEPTED");
    }

    @PostMapping("/auth/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Accepted resend(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        emailLimit("verification", request.email(), http);
        accounts.requestEmail(request.email(), "VERIFY");
        return new Accepted("ACCEPTED");
    }

    @PostMapping("/auth/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        limits.check("verify-ip", http.getRemoteAddr(), 20, Duration.ofMinutes(1));
        accounts.verifyEmail(request.token());
    }

    @PostMapping("/auth/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody ResetPassword request, HttpServletRequest http, HttpServletResponse response) {
        limits.check("reset-token-ip", http.getRemoteAddr(), 20, Duration.ofMinutes(1));
        accounts.resetPassword(request.token(), request.newPassword());
        cookies.clear(response);
    }

    @PostMapping("/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(@AuthenticationPrincipal IdentityPrincipal principal, @Valid @RequestBody ChangePassword request,
            HttpServletResponse response) {
        limits.check("change-password", principal.accountId().toString(), 5, Duration.ofMinutes(15));
        accounts.changePassword(principal, request.currentPassword(), request.newPassword());
        cookies.clear(response);
    }

    @PostMapping("/admin/users/{id}/suspension")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void suspend(@AuthenticationPrincipal IdentityPrincipal principal, @PathVariable UUID id) {
        accounts.suspend(principal, id);
    }

    private void emailLimit(String scope, String email, HttpServletRequest request) {
        // Forwarded IP headers are deliberately not trusted. Ingress must enforce its own IP limits.
        limits.check(scope + "-ip", request.getRemoteAddr(), 10, Duration.ofMinutes(15));
        limits.check(scope + "-email", IdentityAccountService.normalizeEmail(email), 3, Duration.ofHours(1));
    }

    public record CsrfView(String headerName, String token) {
        @Override public String toString() { return "CsrfView[redacted]"; }
    }
    public record Accepted(String status) {}
    public record SessionResult(Instant accessExpiresAt, Instant sessionExpiresAt) {}
    public record Register(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 80) String displayName, @NotNull @Size(max = 256) String password) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record Login(@NotBlank @Email @Size(max = 254) String email, @NotNull @Size(max = 256) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
        @Override public String toString() { return "EmailRequest[redacted]"; }
    }
    public record TokenRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "TokenRequest[redacted]"; }
    }
    public record ResetPassword(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotNull @Size(max = 256) String newPassword) {
        @Override public String toString() { return "ResetPassword[redacted]"; }
    }
    public record ChangePassword(@NotNull @Size(max = 256) String currentPassword,
            @NotNull @Size(max = 256) String newPassword) {
        @Override public String toString() { return "ChangePassword[redacted]"; }
    }
}
