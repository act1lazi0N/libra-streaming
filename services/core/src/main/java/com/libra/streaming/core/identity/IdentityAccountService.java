package com.libra.streaming.core.identity;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import com.libra.streaming.core.profiles.application.ProfileOperations;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdentityAccountService {
    private final JdbcTemplate jdbc;
    private final IdentityStore accounts;
    private final IdentitySessionService sessions;
    private final IdentitySecrets secrets;
    private final IdentityProperties properties;
    private final PasswordEncoder passwords;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProfileOperations profiles;

    public IdentityAccountService(JdbcTemplate jdbc, IdentityStore accounts, IdentitySessionService sessions,
            IdentitySecrets secrets, IdentityProperties properties, PasswordEncoder passwords, ObjectMapper mapper, Clock clock,
            ProfileOperations profiles) {
        this.jdbc = jdbc; this.accounts = accounts; this.sessions = sessions; this.secrets = secrets;
        this.properties = properties; this.passwords = passwords; this.mapper = mapper; this.clock = clock;
        this.profiles = profiles;
    }

    @Transactional
    public void register(String email, String displayName, String password) {
        validatePassword(password);
        String encoded = passwords.encode(password);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO identity_accounts (id, email, display_name, password_hash, role)
                VALUES (?, ?, ?, ?, 'USER') ON CONFLICT (email) DO NOTHING
                """, id, normalizeEmail(email), displayName.strip(), encoded);
        if (inserted == 1) {
            profiles.initialize(id, displayName);
            queueToken(accounts.lock(id), "VERIFY");
            accounts.audit(id, id, "REGISTERED");
        }
    }

    @Transactional
    public void requestEmail(String email, String purpose) {
        accounts.byEmail(normalizeEmail(email)).ifPresent(found -> {
            IdentityAccount account = accounts.lock(found.id());
            if (purpose.equals("RESET") || (!account.emailVerified() && account.status().equals("ACTIVE"))) {
                queueToken(account, purpose);
            }
        });
    }

    @Transactional
    public void verifyEmail(String token) {
        IdentityAccount account = lockTokenAccount(token, "VERIFY");
        if (!account.status().equals("ACTIVE")) { throw IdentityException.invalidToken(); }
        consumeToken(token, "VERIFY");
        jdbc.update("UPDATE identity_accounts SET email_verified = TRUE WHERE id = ?", account.id());
        invalidateTokens(account.id(), "VERIFY");
        accounts.audit(account.id(), account.id(), "EMAIL_VERIFIED");
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        validatePassword(newPassword);
        IdentityAccount account = lockTokenAccount(token, "RESET");
        consumeToken(token, "RESET");
        replacePassword(account.id(), newPassword);
        accounts.audit(account.id(), account.id(), "PASSWORD_RESET");
    }

    @Transactional
    public void changePassword(IdentityPrincipal principal, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        IdentityAccount account = accounts.lock(principal.accountId());
        sessions.requireCurrent(principal);
        if (!passwords.matches(currentPassword, account.passwordHash())) {
            throw IdentityException.unauthenticated();
        }
        replacePassword(account.id(), newPassword);
        accounts.audit(account.id(), account.id(), "PASSWORD_CHANGED");
    }

    @Transactional
    public void suspend(IdentityPrincipal actor, UUID targetId) {
        IdentityAccount administrator = accounts.lock(actor.accountId());
        sessions.requireCurrent(actor);
        if (!administrator.role().equals("ADMIN") || actor.accountId().equals(targetId)) {
            throw new IdentityException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (!jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM identity_accounts WHERE id = ?)", Boolean.class, targetId)) {
            throw new IdentityException(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        accounts.lock(targetId);
        jdbc.update("UPDATE identity_accounts SET status = 'SUSPENDED' WHERE id = ?", targetId);
        sessions.revokeAll(targetId);
        accounts.audit(actor.accountId(), targetId, "ACCOUNT_SUSPENDED");
    }

    @Transactional
    public void bootstrapAdministrator() {
        if (properties.bootstrapEmail().isBlank()) { return; }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(724091402)", Object.class);
        if (jdbc.queryForObject("SELECT count(*) FROM identity_bootstrap", Integer.class) > 0) { return; }
        validatePassword(properties.bootstrapPassword());
        String email = normalizeEmail(properties.bootstrapEmail());
        if (!email.matches("[^\\s@]+@[^\\s@]+") || email.length() > 254) {
            throw new IllegalArgumentException("Invalid bootstrap administrator email");
        }
        if (accounts.byEmail(email).isPresent()) {
            throw new IllegalStateException("Bootstrap email already belongs to an account; automatic promotion is forbidden");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts (id, email, display_name, password_hash, role) VALUES (?, ?, ?, ?, 'ADMIN')",
                id, email, "Administrator", passwords.encode(properties.bootstrapPassword()));
        profiles.initialize(id, "Administrator");
        queueToken(accounts.lock(id), "VERIFY");
        jdbc.update("INSERT INTO identity_bootstrap (account_id) VALUES (?)", id);
        accounts.audit(id, id, "ADMIN_BOOTSTRAPPED");
    }

    private void replacePassword(UUID accountId, String password) {
        jdbc.update("UPDATE identity_accounts SET password_hash = ? WHERE id = ?", passwords.encode(password), accountId);
        sessions.revokeAll(accountId);
        invalidateTokens(accountId, "RESET");
    }

    private IdentityAccount lockTokenAccount(String token, String purpose) {
        if (!IdentitySessionService.validTokenShape(token)) { throw IdentityException.invalidToken(); }
        var ids = jdbc.query("SELECT account_id FROM identity_email_tokens WHERE token_hash = ? AND purpose = ?",
                (rs, row) -> rs.getObject(1, UUID.class), IdentitySecrets.hash(token), purpose);
        if (ids.isEmpty()) { throw IdentityException.invalidToken(); }
        return accounts.lock(ids.getFirst());
    }

    private void consumeToken(String token, String purpose) {
        int updated = jdbc.update("""
                UPDATE identity_email_tokens SET consumed_at = ?
                WHERE token_hash = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(clock.instant()), IdentitySecrets.hash(token), purpose, Timestamp.from(clock.instant()));
        if (updated != 1) { throw IdentityException.invalidToken(); }
    }

    private void invalidateTokens(UUID accountId, String purpose) {
        jdbc.update("UPDATE identity_email_tokens SET consumed_at = ? WHERE account_id = ? AND purpose = ? AND consumed_at IS NULL",
                Timestamp.from(clock.instant()), accountId, purpose);
        jdbc.update("""
                UPDATE identity_mail_queue SET state = 'EXPIRED', encrypted_payload = NULL, lease_id = NULL, lease_until = NULL
                WHERE account_id = ? AND purpose = ? AND state IN ('PENDING', 'PROCESSING', 'DEAD')
                """, accountId, purpose);
    }

    private void queueToken(IdentityAccount account, String purpose) {
        if (!purpose.equals("VERIFY") && !purpose.equals("RESET")) { throw new IllegalArgumentException("Invalid email purpose"); }
        invalidateTokens(account.id(), purpose);
        String token = secrets.token();
        Instant expires = clock.instant().plus(purpose.equals("VERIFY") ? Duration.ofHours(24) : Duration.ofMinutes(30));
        jdbc.update("INSERT INTO identity_email_tokens (token_hash, account_id, purpose, expires_at) VALUES (?, ?, ?, ?)",
                IdentitySecrets.hash(token), account.id(), purpose, Timestamp.from(expires));
        UUID mailId = UUID.randomUUID();
        String path = purpose.equals("VERIFY") ? "/verify-email" : "/reset-password";
        String link = properties.publicBaseUrl().resolve(path).toASCIIString() + "?token=" + token;
        var payload = new MailPayload(account.email(), purpose.equals("VERIFY") ? "Verify your LIBRA email" : "Reset your LIBRA password",
                "Use this one-time link: " + link + "\nExpires at " + expires + ". If you did not request this, ignore this message.");
        jdbc.update("INSERT INTO identity_mail_queue (id, account_id, purpose, encrypted_payload, expires_at) VALUES (?, ?, ?, ?, ?)",
                mailId, account.id(), purpose, secrets.encrypt(mailId, mapper.writeValueAsString(payload)), Timestamp.from(expires));
    }

    static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    static void validatePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IdentityException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION");
        }
    }

    public record MailPayload(String recipient, String subject, String body) {
        @Override public String toString() { return "MailPayload[redacted]"; }
    }
}
