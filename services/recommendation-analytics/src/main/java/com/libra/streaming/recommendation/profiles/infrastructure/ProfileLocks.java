package com.libra.streaming.recommendation.profiles.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Transaction-scoped PostgreSQL advisory locks per profile. A tombstone takes the exclusive lock and every writer of
 * a profile link takes the shared one, so a link written concurrently with the deletion is either erased by it or
 * sees the tombstone. The key folds the UUID to 64 bits; a rare collision only serializes two unrelated profiles.
 */
public final class ProfileLocks {
    private ProfileLocks() {}

    public static void exclusive(JdbcTemplate jdbc, UUID profileId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, key(profileId));
    }

    public static void shared(JdbcTemplate jdbc, UUID profileId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(?)::text", String.class, key(profileId));
    }

    static long key(UUID profileId) {
        return profileId.getMostSignificantBits() ^ profileId.getLeastSignificantBits();
    }
}
