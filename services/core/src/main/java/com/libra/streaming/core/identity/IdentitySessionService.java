package com.libra.streaming.core.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentitySessionService {
    private final JdbcTemplate jdbc;
    private final IdentityStore accounts;
    private final IdentitySecrets secrets;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final IdentityProperties properties;
    private final Clock clock;
    private final String dummyHash;

    public IdentitySessionService(JdbcTemplate jdbc, IdentityStore accounts, IdentitySecrets secrets,
            PasswordEncoder passwords, JwtEncoder jwt, IdentityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.secrets = secrets;
        this.passwords = passwords;
        this.jwt = jwt;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwords.encode(secrets.token());
    }

    @Transactional
    public IssuedSession login(String email, String password) {
        var found = accounts.byEmail(IdentityAccountService.normalizeEmail(email));
        if (found.isEmpty()) {
            passwords.matches(password, dummyHash);
            throw IdentityException.unauthenticated();
        }
        IdentityAccount account = accounts.lock(found.get().id());
        boolean matches = passwords.matches(password, account.passwordHash());
        if (!matches || !account.status().equals("ACTIVE")) { throw IdentityException.unauthenticated(); }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        UUID sessionId = UUID.randomUUID();
        Instant expiry = now.plus(Duration.ofDays(30));
        jdbc.update("INSERT INTO identity_sessions (id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                sessionId, account.id(), Timestamp.from(now), Timestamp.from(expiry));
        accounts.audit(account.id(), account.id(), "LOGIN");
        return issue(account.id(), sessionId, expiry);
    }

    @Transactional
    public Optional<IssuedSession> refresh(String rawToken) {
        if (!validTokenShape(rawToken)) { return Optional.empty(); }
        String hash = IdentitySecrets.hash(rawToken);
        var bindings = jdbc.query("""
                SELECT s.account_id, s.id FROM identity_refresh_tokens t
                JOIN identity_sessions s ON s.id = t.session_id WHERE t.token_hash = ?
                """, (rs, row) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, hash);
        if (bindings.isEmpty()) { return Optional.empty(); }
        UUID accountId = bindings.getFirst()[0];
        UUID sessionId = bindings.getFirst()[1];
        IdentityAccount account = accounts.lock(accountId);
        var state = jdbc.queryForMap("""
                SELECT t.consumed_at, s.revoked_at, s.expires_at FROM identity_refresh_tokens t
                JOIN identity_sessions s ON s.id = t.session_id WHERE t.token_hash = ? FOR UPDATE OF t, s
                """, hash);
        if (state.get("consumed_at") != null) {
            revoke(sessionId);
            accounts.audit(accountId, accountId, "REFRESH_REPLAY_REVOKED");
            // Returning normally commits revocation before the controller returns 401.
            return Optional.empty();
        }
        Instant expiry = ((Timestamp) state.get("expires_at")).toInstant();
        if (state.get("revoked_at") != null || !expiry.isAfter(clock.instant()) || !account.status().equals("ACTIVE")) {
            return Optional.empty();
        }
        jdbc.update("UPDATE identity_refresh_tokens SET consumed_at = ? WHERE token_hash = ?",
                Timestamp.from(clock.instant()), hash);
        return Optional.of(issue(accountId, sessionId, expiry));
    }

    @Transactional
    public void logout(String rawToken) {
        if (!validTokenShape(rawToken)) { return; }
        var rows = jdbc.query("""
                SELECT s.account_id, s.id FROM identity_sessions s JOIN identity_refresh_tokens t
                ON t.session_id = s.id WHERE t.token_hash = ?
                """, (rs, row) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                IdentitySecrets.hash(rawToken));
        if (!rows.isEmpty()) {
            accounts.lock(rows.getFirst()[0]);
            revoke(rows.getFirst()[1]);
            accounts.audit(rows.getFirst()[0], rows.getFirst()[0], "LOGOUT");
        }
    }

    @Transactional
    public void revokeOwned(IdentityPrincipal principal, UUID sessionId) {
        accounts.lock(principal.accountId());
        requireCurrent(principal);
        int changed = jdbc.update("""
                UPDATE identity_sessions SET revoked_at = COALESCE(revoked_at, ?)
                WHERE id = ? AND account_id = ?
                """, Timestamp.from(clock.instant()), sessionId, principal.accountId());
        if (changed == 0) { throw new IdentityException(HttpStatus.NOT_FOUND, "NOT_FOUND"); }
        accounts.audit(principal.accountId(), principal.accountId(), "SESSION_REVOKED");
    }

    @Transactional
    public void logoutAll(IdentityPrincipal principal) {
        accounts.lock(principal.accountId());
        requireCurrent(principal);
        revokeAll(principal.accountId());
        accounts.audit(principal.accountId(), principal.accountId(), "LOGOUT_ALL");
    }

    void requireCurrent(IdentityPrincipal principal) {
        if (accounts.principal(principal.accountId(), principal.sessionId(), clock.instant()).isEmpty()) {
            throw IdentityException.unauthenticated();
        }
    }

    void revokeAll(UUID accountId) {
        jdbc.update("UPDATE identity_sessions SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL",
                Timestamp.from(clock.instant()), accountId);
    }

    private void revoke(UUID sessionId) {
        jdbc.update("UPDATE identity_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ?",
                Timestamp.from(clock.instant()), sessionId);
    }

    private IssuedSession issue(UUID accountId, UUID sessionId, Instant sessionExpiry) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant accessExpiry = now.plus(Duration.ofMinutes(10));
        if (accessExpiry.isAfter(sessionExpiry)) { accessExpiry = sessionExpiry; }
        String refresh = secrets.token();
        jdbc.update("INSERT INTO identity_refresh_tokens (token_hash, session_id) VALUES (?, ?)",
                IdentitySecrets.hash(refresh), sessionId);
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(accountId.toString()).issuedAt(now).expiresAt(accessExpiry).id(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString()).claim("purpose", "access").build();
        String access = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedSession(access, refresh, accessExpiry, sessionExpiry);
    }

    static boolean validTokenShape(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    public record IssuedSession(String access, String refresh, Instant accessExpiresAt, Instant sessionExpiresAt) {
        @Override public String toString() { return "IssuedSession[redacted]"; }
    }
}
