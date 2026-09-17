package com.libra.streaming.core.subscriptions;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {
    private static final String PLAN = "PREMIUM_30_DAYS";
    private static final Duration TERM = Duration.ofDays(30);
    private static final RowMapper<PurchaseView> PURCHASE = (rs, row) -> new PurchaseView(
            rs.getObject("id", UUID.class), rs.getString("plan"), rs.getBoolean("simulated"),
            rs.getTimestamp("purchased_at").toInstant(), rs.getTimestamp("term_start").toInstant(),
            rs.getTimestamp("expires_at").toInstant());
    private final JdbcTemplate jdbc;
    private final IdentityAccess access;
    private final Clock clock;

    public SubscriptionService(JdbcTemplate jdbc, IdentityAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public StatusView status(IdentityPrincipal actor) {
        access.lockCurrent(actor, false);
        Instant expiry = expiry(actor.accountId());
        Instant now = clock.instant();
        return new StatusView(expiry == null ? "NONE" : expiry.isAfter(now) ? "ACTIVE" : "EXPIRED",
                expiry, true);
    }

    @Transactional
    public PurchaseView activate(IdentityPrincipal actor, UUID key, String plan) {
        // Serialize even first purchases using the existing account row, before feature reads.
        IdentityPrincipal current = access.lockCurrent(actor, false);
        if (!current.emailVerified()) {
            throw new DomainException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED");
        }
        if (key == null || plan == null || plan.isBlank() || plan.length() > 32) {
            throw DomainException.invalid();
        }
        var previous = jdbc.query("SELECT * FROM subscription_purchases WHERE account_id = ? AND idempotency_key = ?",
                PURCHASE, actor.accountId(), key).stream().findFirst();
        if (previous.isPresent()) {
            if (!previous.get().plan().equals(plan)) { throw DomainException.conflict("IDEMPOTENCY_CONFLICT"); }
            return previous.get();
        }
        if (!PLAN.equals(plan)) { throw DomainException.invalid(); }
        // PostgreSQL stores microseconds; the initial response must equal a later persisted replay.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant existing = expiry(actor.accountId());
        Instant start = existing != null && existing.isAfter(now) ? existing : now;
        Instant expires = start.plus(TERM);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO subscriptions(account_id, expires_at) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET expires_at = EXCLUDED.expires_at
                """, actor.accountId(), Timestamp.from(expires));
        jdbc.update("""
                INSERT INTO subscription_purchases(id, account_id, idempotency_key, plan, simulated,
                    purchased_at, term_start, expires_at) VALUES (?, ?, ?, ?, TRUE, ?, ?, ?)
                """, id, actor.accountId(), key, PLAN, Timestamp.from(now), Timestamp.from(start), Timestamp.from(expires));
        return new PurchaseView(id, PLAN, true, now, start, expires);
    }

    @Transactional
    public List<PurchaseView> purchases(IdentityPrincipal actor, int limit, int offset) {
        access.lockCurrent(actor, false);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) { throw DomainException.invalid(); }
        return jdbc.query("""
                SELECT * FROM subscription_purchases WHERE account_id = ?
                ORDER BY purchased_at DESC, id DESC LIMIT ? OFFSET ?
                """, PURCHASE, actor.accountId(), limit, offset);
    }

    private Instant expiry(UUID accountId) {
        return jdbc.query("SELECT expires_at FROM subscriptions WHERE account_id = ?",
                (rs, row) -> rs.getTimestamp("expires_at").toInstant(), accountId).stream().findFirst().orElse(null);
    }

    public record StatusView(String status, Instant expiresAt, boolean simulated) {}
    public record PurchaseView(UUID id, String plan, boolean simulated, Instant purchasedAt,
            Instant termStart, Instant expiresAt) {}
}
