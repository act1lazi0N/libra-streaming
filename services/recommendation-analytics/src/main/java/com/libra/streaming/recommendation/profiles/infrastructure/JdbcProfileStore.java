package com.libra.streaming.recommendation.profiles.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.profiles.application.ProfileStore;
import com.libra.streaming.recommendation.profiles.domain.ProfileState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcProfileStore implements ProfileStore {
    private final JdbcTemplate jdbc;

    JdbcProfileStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Write live(UUID profileId, UUID accountId, long version, Instant changedAt) {
        int changed = jdbc.update("""
                INSERT INTO profiles AS stored (profile_id, account_id, version, deleted, changed_at)
                VALUES (?, ?, ?, FALSE, ?)
                ON CONFLICT (profile_id) DO UPDATE SET version = EXCLUDED.version, changed_at = EXCLUDED.changed_at
                WHERE NOT stored.deleted AND stored.version < EXCLUDED.version AND stored.account_id = EXCLUDED.account_id
                """, profileId, accountId, version, Timestamp.from(changedAt));
        if (changed == 1) { return Write.APPLIED; }
        ProfileState stored = find(profileId).orElseThrow();
        requireAccount(stored, accountId);
        return stored.deleted() ? Write.TOMBSTONED : Write.STALE;
    }

    @Override
    public Write tombstone(UUID profileId, UUID accountId, long version, Instant changedAt) {
        ProfileLocks.exclusive(jdbc, profileId);
        // Terminal whatever the version: Core deletes at the final version plus one, so no live version can follow.
        int changed = jdbc.update("""
                INSERT INTO profiles AS stored (profile_id, account_id, version, deleted, changed_at)
                VALUES (?, ?, ?, TRUE, ?)
                ON CONFLICT (profile_id) DO UPDATE SET deleted = TRUE,
                    version = GREATEST(stored.version, EXCLUDED.version), changed_at = EXCLUDED.changed_at
                WHERE NOT stored.deleted AND stored.account_id = EXCLUDED.account_id
                """, profileId, accountId, version, Timestamp.from(changedAt));
        if (changed == 1) { return Write.APPLIED; }
        requireAccount(find(profileId).orElseThrow(), accountId);
        return Write.TOMBSTONED;
    }

    @Override
    public Optional<ProfileState> find(UUID profileId) {
        return jdbc.query("SELECT * FROM profiles WHERE profile_id = ?", (row, index) -> new ProfileState(
                row.getObject("profile_id", UUID.class), row.getObject("account_id", UUID.class), row.getLong("version"),
                row.getBoolean("deleted"), row.getTimestamp("changed_at").toInstant()), profileId).stream().findFirst();
    }

    private static void requireAccount(ProfileState stored, UUID accountId) {
        // Core never moves a profile to another account.
        if (!stored.accountId().equals(accountId)) { throw EventRejection.conflict("PROFILE_ACCOUNT_CHANGED"); }
    }
}
