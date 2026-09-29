package com.libra.streaming.core.subscriptions.infrastructure;

import com.libra.streaming.core.subscriptions.application.SubscriptionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSubscriptionStore implements SubscriptionStore {
    private static final RowMapper<Purchase> PURCHASE = (rs, row) -> new Purchase(
            rs.getObject("id", UUID.class), rs.getString("plan"), rs.getBoolean("simulated"),
            rs.getTimestamp("purchased_at").toInstant(), rs.getTimestamp("term_start").toInstant(),
            rs.getTimestamp("expires_at").toInstant());
    private final JdbcTemplate jdbc;

    public JdbcSubscriptionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Instant expiry(UUID accountId) {
        return jdbc.query("SELECT expires_at FROM subscriptions WHERE account_id = ?",
                (rs, row) -> rs.getTimestamp("expires_at").toInstant(), accountId)
                .stream().findFirst().orElse(null);
    }

    @Override
    public Optional<Purchase> byIdempotencyKey(UUID accountId, UUID key) {
        return jdbc.query("SELECT * FROM subscription_purchases WHERE account_id = ? AND idempotency_key = ?",
                PURCHASE, accountId, key).stream().findFirst();
    }

    @Override
    public void save(UUID accountId, UUID key, Purchase purchase) {
        jdbc.update("""
                INSERT INTO subscriptions(account_id, expires_at) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET expires_at = EXCLUDED.expires_at
                """, accountId, Timestamp.from(purchase.expiresAt()));
        jdbc.update("""
                INSERT INTO subscription_purchases(id, account_id, idempotency_key, plan, simulated,
                    purchased_at, term_start, expires_at) VALUES (?, ?, ?, ?, TRUE, ?, ?, ?)
                """, purchase.id(), accountId, key, purchase.plan(), Timestamp.from(purchase.purchasedAt()),
                Timestamp.from(purchase.termStart()), Timestamp.from(purchase.expiresAt()));
    }

    @Override
    public List<Purchase> purchases(UUID accountId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM subscription_purchases WHERE account_id = ?
                ORDER BY purchased_at DESC, id DESC LIMIT ? OFFSET ?
                """, PURCHASE, accountId, limit, offset);
    }
}
