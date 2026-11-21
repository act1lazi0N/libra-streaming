package com.libra.streaming.recommendation.playback.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.playback.application.PlaybackProjection;
import com.libra.streaming.recommendation.playback.application.PlaybackStore;
import com.libra.streaming.recommendation.playback.domain.Qualification;
import com.libra.streaming.recommendation.playback.domain.SessionTotals;
import com.libra.streaming.recommendation.playback.domain.StoredFact;
import com.libra.streaming.recommendation.profiles.application.ProfileErasure;
import com.libra.streaming.recommendation.profiles.infrastructure.ProfileLocks;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPlaybackStore implements PlaybackStore, ProfileErasure {
    private final JdbcTemplate jdbc;

    JdbcPlaybackStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public StoredFact append(PlaybackProgress fact) {
        // Shared profile lock: a concurrent tombstone either commits first (seen below) or erases this link after.
        ProfileLocks.shared(jdbc, fact.profileId());
        boolean deleted = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM profiles WHERE profile_id = ? AND deleted)", Boolean.class, fact.profileId()));
        UUID profileLink = deleted ? null : fact.profileId();
        Timestamp occurredAt = Timestamp.from(PlaybackProjection.storedInstant(fact.envelope().occurredAt()));
        int inserted = jdbc.update("""
                INSERT INTO playback_facts (session_id, sequence, event_id, account_id, profile_id, content_id, asset_id,
                    asset_version, position_ms, accepted_watched_ms, total_watched_ms, qualified, state, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, sequence) DO NOTHING
                """, fact.sessionId(), fact.sequence(), fact.eventId(), fact.accountId(), profileLink, fact.contentId(),
                fact.assetId(), fact.assetVersion(), fact.positionMs(), fact.acceptedWatchedMs(), fact.totalWatchedMs(),
                fact.qualified(), fact.state().name(), occurredAt);
        // The event ID is new (the receipt said so), so an occupied sequence belongs to a different event.
        if (inserted == 0) { throw EventRejection.conflict("SEQUENCE_CONFLICT"); }
        Long qualifiedSequence = fact.qualified() ? fact.sequence() : null;
        Timestamp qualifiedAt = fact.qualified() ? occurredAt : null;
        // All SET expressions read the row as it was before this statement, so their order does not matter.
        // RETURNING old/new (PostgreSQL 18) reads the qualifying instant before and after under the row lock this
        // statement holds, so a concurrent writer of the same session cannot slip between the read and the update.
        List<Qualification> changed = jdbc.query("""
                INSERT INTO playback_sessions AS stored (session_id, account_id, profile_id, content_id, fact_count,
                    accepted_watched_ms, max_sequence, max_total_watched_ms, qualified_sequence, qualified_at,
                    first_occurred_at, last_occurred_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET fact_count = stored.fact_count + 1,
                    accepted_watched_ms = stored.accepted_watched_ms + EXCLUDED.accepted_watched_ms,
                    max_sequence = GREATEST(stored.max_sequence, EXCLUDED.max_sequence),
                    max_total_watched_ms = CASE WHEN EXCLUDED.max_sequence > stored.max_sequence
                        THEN EXCLUDED.max_total_watched_ms ELSE stored.max_total_watched_ms END,
                    qualified_sequence = CASE WHEN EXCLUDED.qualified_sequence < stored.qualified_sequence
                        OR stored.qualified_sequence IS NULL THEN COALESCE(EXCLUDED.qualified_sequence, stored.qualified_sequence)
                        ELSE stored.qualified_sequence END,
                    qualified_at = CASE WHEN EXCLUDED.qualified_sequence < stored.qualified_sequence
                        OR stored.qualified_sequence IS NULL THEN COALESCE(EXCLUDED.qualified_at, stored.qualified_at)
                        ELSE stored.qualified_at END,
                    first_occurred_at = LEAST(stored.first_occurred_at, EXCLUDED.first_occurred_at),
                    last_occurred_at = GREATEST(stored.last_occurred_at, EXCLUDED.last_occurred_at)
                WHERE stored.account_id = EXCLUDED.account_id AND stored.content_id = EXCLUDED.content_id
                    AND (stored.profile_id IS NULL OR EXCLUDED.profile_id IS NULL OR stored.profile_id = EXCLUDED.profile_id)
                RETURNING old.qualified_at AS qualified_before, new.qualified_at AS qualified_after
                """, (row, index) -> new Qualification(instant(row.getTimestamp("qualified_before")),
                        instant(row.getTimestamp("qualified_after"))),
                fact.sessionId(), fact.accountId(), profileLink, fact.contentId(), fact.acceptedWatchedMs(),
                fact.sequence(), fact.totalWatchedMs(), qualifiedSequence, qualifiedAt, occurredAt, occurredAt);
        // A session belongs to one account, profile and content from admission on.
        if (changed.isEmpty()) { throw EventRejection.conflict("SESSION_IDENTITY_CHANGED"); }
        return new StoredFact(profileLink, changed.getFirst());
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    @Override
    public Optional<SessionTotals> session(UUID sessionId) {
        return jdbc.query("SELECT * FROM playback_sessions WHERE session_id = ?", (row, index) -> {
            Timestamp qualifiedAt = row.getTimestamp("qualified_at");
            return new SessionTotals(row.getObject("session_id", UUID.class), row.getObject("account_id", UUID.class),
                    row.getObject("profile_id", UUID.class), row.getObject("content_id", UUID.class),
                    row.getInt("fact_count"), row.getLong("accepted_watched_ms"), row.getLong("max_sequence"),
                    row.getLong("max_total_watched_ms"), (Long) row.getObject("qualified_sequence"),
                    qualifiedAt == null ? null : qualifiedAt.toInstant(), row.getTimestamp("first_occurred_at").toInstant(),
                    row.getTimestamp("last_occurred_at").toInstant());
        }, sessionId).stream().findFirst();
    }

    /** Facts stay for aggregate statistics; only the profile link goes (RA01.1, "Profiles — tombstone"). */
    @Override
    public void erase(UUID profileId) {
        jdbc.update("UPDATE playback_facts SET profile_id = NULL WHERE profile_id = ?", profileId);
        jdbc.update("UPDATE playback_sessions SET profile_id = NULL WHERE profile_id = ?", profileId);
    }
}
