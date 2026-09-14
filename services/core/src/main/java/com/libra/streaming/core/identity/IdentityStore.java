package com.libra.streaming.core.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class IdentityStore {
    private static final RowMapper<IdentityAccount> ACCOUNT = (rs, row) -> new IdentityAccount(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
            rs.getString("password_hash"), rs.getString("role"), rs.getString("status"), rs.getBoolean("email_verified"));
    private final JdbcTemplate jdbc;

    public IdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<IdentityAccount> byEmail(String email) {
        return jdbc.query("SELECT * FROM identity_accounts WHERE email = ?", ACCOUNT, email).stream().findFirst();
    }

    IdentityAccount lock(UUID accountId) {
        return jdbc.query("SELECT * FROM identity_accounts WHERE id = ? FOR UPDATE", ACCOUNT, accountId)
                .stream().findFirst().orElseThrow(IdentityException::unauthenticated);
    }

    public Optional<IdentityPrincipal> principal(UUID accountId, UUID sessionId, Instant now) {
        return jdbc.query("""
                SELECT a.id, a.role, a.email_verified FROM identity_accounts a
                JOIN identity_sessions s ON s.account_id = a.id
                WHERE a.id = ? AND s.id = ? AND a.status = 'ACTIVE'
                    AND s.revoked_at IS NULL AND s.expires_at > ?
                """, (rs, row) -> new IdentityPrincipal(rs.getObject("id", UUID.class), sessionId,
                        rs.getString("role"), rs.getBoolean("email_verified")),
                accountId, sessionId, java.sql.Timestamp.from(now)).stream().findFirst();
    }

    public AccountView view(UUID accountId) {
        return jdbc.query("SELECT * FROM identity_accounts WHERE id = ?", ACCOUNT, accountId).stream()
                .map(account -> new AccountView(account.id(), account.email(), account.displayName(),
                        account.role(), account.emailVerified())).findFirst().orElseThrow(IdentityException::unauthenticated);
    }

    public List<SessionView> sessions(UUID accountId, UUID currentSession, int limit, int offset) {
        return jdbc.query("""
                SELECT id, created_at, expires_at FROM identity_sessions
                WHERE account_id = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """, (rs, row) -> new SessionView(rs.getObject("id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("id", UUID.class).equals(currentSession)), accountId, limit, offset);
    }

    void audit(UUID actor, UUID accountId, String action) {
        jdbc.update("INSERT INTO identity_audit (id, actor_id, account_id, action) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), actor, accountId, action);
    }

    public record AccountView(UUID id, String email, String displayName, String role, boolean emailVerified) {}
    public record SessionView(UUID id, Instant createdAt, Instant expiresAt, boolean current) {}
}
