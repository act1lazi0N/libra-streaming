package com.libra.streaming.core.history;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoryService {
    private final JdbcTemplate jdbc;
    private final IdentityAccess access;
    private final Clock clock;

    public HistoryService(JdbcTemplate jdbc, IdentityAccess access, Clock clock) {
        this.jdbc = jdbc; this.access = access; this.clock = clock;
    }

    /** Caller holds the account lock, so admission/clear/profile deletion have one server order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long opened(UUID profileId, UUID contentId, UUID sessionId, long durationMs) {
        var positions = jdbc.query("SELECT position_ms, completed FROM watch_history WHERE profile_id = ? AND content_id = ?",
                (rs, row) -> rs.getBoolean("completed") ? 0L : Math.min(rs.getLong("position_ms"), durationMs), profileId, contentId);
        long position = positions.isEmpty() ? 0 : positions.getFirst();
        jdbc.update("""
                INSERT INTO watch_history(profile_id, content_id, latest_session_id, position_ms, duration_ms, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (profile_id, content_id) DO UPDATE SET latest_session_id = EXCLUDED.latest_session_id,
                    position_ms = EXCLUDED.position_ms, duration_ms = EXCLUDED.duration_ms, completed = FALSE,
                    updated_at = EXCLUDED.updated_at
                """, profileId, contentId, sessionId, position, durationMs, Timestamp.from(clock.instant()));
        return position;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accepted(UUID sessionId, long positionMs, long durationMs, Instant now) {
        // Only the latest-opened session can write shared resume. Older sessions still retain their own telemetry.
        jdbc.update("""
                UPDATE watch_history SET position_ms = ?, completed = ?, updated_at = ? WHERE latest_session_id = ?
                """, positionMs, positionMs * 100 >= durationMs * 95, Timestamp.from(now), sessionId);
    }

    @Transactional
    public List<HistoryView> list(IdentityPrincipal actor, UUID profileId, boolean continuing, int limit, int offset) {
        access.lockCurrent(actor, false);
        requireOwned(actor, profileId);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) { throw DomainException.invalid(); }
        // Hidden titles are absent. Current publication is used, never retained draft metadata.
        return jdbc.query("""
                SELECT h.*, r.metadata->>'title' AS title FROM watch_history h
                JOIN catalog_contents c ON c.id = h.content_id
                JOIN catalog_revisions r ON r.content_id = c.id AND r.revision = c.published_revision
                LEFT JOIN catalog_contents p ON p.id = c.parent_id
                LEFT JOIN catalog_contents g ON g.id = p.parent_id
                WHERE h.profile_id = ? AND (c.parent_id IS NULL OR p.published_revision IS NOT NULL)
                    AND (p.parent_id IS NULL OR g.published_revision IS NOT NULL)
                    AND (? = FALSE OR (h.completed = FALSE AND h.position_ms > 0))
                ORDER BY h.updated_at DESC, h.content_id LIMIT ? OFFSET ?
                """, (rs, row) -> new HistoryView(rs.getObject("content_id", UUID.class), rs.getString("title"),
                        rs.getLong("position_ms"), rs.getLong("duration_ms"), rs.getBoolean("completed"),
                        rs.getTimestamp("updated_at").toInstant()), profileId, continuing, limit, offset);
    }

    @Transactional
    public void clear(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        access.lockCurrent(actor, false);
        requireOwned(actor, profileId);
        if (contentId == null) {
            jdbc.update("UPDATE playback_sessions SET state = 'ENDED' WHERE profile_id = ?", profileId);
            jdbc.update("DELETE FROM watch_history WHERE profile_id = ?", profileId);
        } else {
            jdbc.update("UPDATE playback_sessions SET state = 'ENDED' WHERE profile_id = ? AND content_id = ?", profileId, contentId);
            jdbc.update("DELETE FROM watch_history WHERE profile_id = ? AND content_id = ?", profileId, contentId);
        }
    }

    private void requireOwned(IdentityPrincipal actor, UUID profileId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM profiles WHERE id = ? AND account_id = ?)",
                Boolean.class, profileId, actor.accountId()))) { throw DomainException.missing(); }
    }

    public record HistoryView(UUID contentId, String title, long positionMs, long durationMs, boolean completed, Instant updatedAt) {}
}
