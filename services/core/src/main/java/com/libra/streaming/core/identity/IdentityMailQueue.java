package com.libra.streaming.core.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityMailQueue {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdentityMailQueue(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional
    public List<Claim> claim() {
        var now = Timestamp.from(clock.instant());
        var cutoff = Timestamp.from(now.toInstant().minus(Duration.ofDays(1)));
        // Recheck age after a concurrent request releases its row lock.
        jdbc.update("""
                DELETE FROM identity_rate_limits WHERE window_start < ? AND bucket_key IN (
                    SELECT bucket_key FROM identity_rate_limits WHERE window_start < ? LIMIT 500
                )
                """, cutoff, cutoff);
        jdbc.update("""
                UPDATE identity_mail_queue SET state = 'EXPIRED', encrypted_payload = NULL, lease_id = NULL, lease_until = NULL
                WHERE expires_at <= ? AND encrypted_payload IS NOT NULL
                """, now);
        jdbc.update("""
                UPDATE identity_mail_queue SET state = 'DEAD', lease_id = NULL, lease_until = NULL,
                    last_error_code = 'DELIVERY_OUTCOME_UNKNOWN'
                WHERE state = 'PROCESSING' AND lease_until <= ? AND attempts >= 5
                """, now);
        UUID lease = UUID.randomUUID();
        return jdbc.query("""
                WITH candidates AS (
                    SELECT id FROM identity_mail_queue
                    WHERE expires_at > ? AND attempts < 5 AND
                        ((state = 'PENDING' AND available_at <= ?) OR (state = 'PROCESSING' AND lease_until <= ?))
                    ORDER BY created_at, id LIMIT 10 FOR UPDATE SKIP LOCKED
                )
                UPDATE identity_mail_queue q SET state = 'PROCESSING', attempts = attempts + 1,
                    lease_id = ?, lease_until = ?
                FROM candidates c WHERE q.id = c.id
                RETURNING q.id, q.encrypted_payload, q.attempts
                """, (rs, row) -> new Claim(rs.getObject("id", UUID.class), lease,
                        rs.getString("encrypted_payload"), rs.getInt("attempts")),
                now, now, now, lease, Timestamp.from(clock.instant().plus(Duration.ofMinutes(5))));
    }

    public boolean stillValid(Claim claim) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM identity_mail_queue
                    WHERE id = ? AND lease_id = ? AND state = 'PROCESSING' AND expires_at > ? AND lease_until > ?)
                """, Boolean.class, claim.id(), claim.lease(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    @Transactional
    public void complete(Claim claim) {
        jdbc.update("""
                UPDATE identity_mail_queue SET state = 'SENT', sent_at = ?, encrypted_payload = NULL,
                    lease_id = NULL, lease_until = NULL, last_error_code = NULL
                WHERE id = ? AND lease_id = ? AND state = 'PROCESSING'
                """, Timestamp.from(clock.instant()), claim.id(), claim.lease());
    }

    @Transactional
    public void failed(Claim claim, boolean malformed) {
        boolean dead = malformed || claim.attempts() >= 5;
        jdbc.update("""
                UPDATE identity_mail_queue SET state = ?, available_at = ?, lease_id = NULL,
                    lease_until = NULL, last_error_code = ?
                WHERE id = ? AND lease_id = ? AND state = 'PROCESSING'
                """, dead ? "DEAD" : "PENDING",
                Timestamp.from(clock.instant().plusSeconds(Math.min(900, 30L << (claim.attempts() - 1)))),
                malformed ? "INVALID_ENCRYPTED_PAYLOAD" : "DELIVERY_OUTCOME_UNKNOWN", claim.id(), claim.lease());
    }

    public record Claim(UUID id, UUID lease, String encryptedPayload, int attempts) {
        @Override public String toString() { return "MailClaim[redacted]"; }
    }
}
