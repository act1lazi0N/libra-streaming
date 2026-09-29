package com.libra.streaming.core.history.infrastructure;

import com.libra.streaming.core.history.application.HistoryOperations.HistoryView;
import com.libra.streaming.core.history.application.HistoryStore;
import com.libra.streaming.core.history.domain.ResumeState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcHistoryStore implements HistoryStore {
    private final JdbcTemplate jdbc;

    public JdbcHistoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ResumeState previous(UUID profileId, UUID contentId) {
        return jdbc.query("SELECT position_ms, completed FROM watch_history WHERE profile_id = ? AND content_id = ?",
                (rs, row) -> new ResumeState(rs.getLong("position_ms"), rs.getBoolean("completed")),
                profileId, contentId).stream().findFirst().orElse(null);
    }

    @Override
    public void opened(UUID profileId, UUID contentId, UUID sessionId,
            long positionMs, long durationMs, Instant now) {
        jdbc.update("""
                INSERT INTO watch_history(profile_id, content_id, latest_session_id, position_ms, duration_ms, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (profile_id, content_id) DO UPDATE SET latest_session_id = EXCLUDED.latest_session_id,
                    position_ms = EXCLUDED.position_ms, duration_ms = EXCLUDED.duration_ms, completed = FALSE,
                    updated_at = EXCLUDED.updated_at
                """, profileId, contentId, sessionId, positionMs, durationMs, Timestamp.from(now));
    }

    @Override
    public void accepted(UUID sessionId, long positionMs, boolean completed, Instant now) {
        // Only the latest-opened session can write shared resume.
        jdbc.update("UPDATE watch_history SET position_ms = ?, completed = ?, updated_at = ? WHERE latest_session_id = ?",
                positionMs, completed, Timestamp.from(now), sessionId);
    }

    @Override
    public boolean owned(UUID accountId, UUID profileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM profiles WHERE id = ? AND account_id = ?)",
                Boolean.class, profileId, accountId));
    }

    @Override
    public List<HistoryView> list(UUID profileId, boolean continuing, int limit, int offset) {
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

    @Override
    public void clear(UUID profileId, UUID contentId) {
        if (contentId == null) {
            jdbc.update("UPDATE playback_sessions SET state = 'ENDED' WHERE profile_id = ?", profileId);
            jdbc.update("DELETE FROM watch_history WHERE profile_id = ?", profileId);
        } else {
            jdbc.update("UPDATE playback_sessions SET state = 'ENDED' WHERE profile_id = ? AND content_id = ?",
                    profileId, contentId);
            jdbc.update("DELETE FROM watch_history WHERE profile_id = ? AND content_id = ?", profileId, contentId);
        }
    }
}
