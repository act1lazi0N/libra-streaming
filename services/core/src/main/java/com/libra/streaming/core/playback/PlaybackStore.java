package com.libra.streaming.core.playback;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.entitlement.application.EntitlementOperations.EligibleContent;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import static com.libra.streaming.core.playback.PlaybackModels.*;

@Repository
class PlaybackStore {
    private static final RowMapper<Session> SESSION = (rs, row) -> new Session(rs.getObject("id", UUID.class),
            rs.getObject("profile_id", UUID.class), rs.getObject("content_id", UUID.class),
            rs.getObject("binding_id", UUID.class), rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"),
            rs.getLong("duration_ms"), rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("last_progress_at").toInstant(), rs.getLong("sequence"),
            rs.getLong("position_ms"), rs.getLong("watched_ms"), State.valueOf(rs.getString("state")));
    private final JdbcTemplate jdbc;

    PlaybackStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lockCatalog(UUID contentId) {
        // Hierarchy is immutable. Lock all ancestors and the playable row before the fresh policy read.
        // Catalog publication and Media projection writers lock the same content row.
        jdbc.query("""
                SELECT id FROM catalog_contents WHERE id IN (
                    SELECT c.id FROM catalog_contents c WHERE c.id = ?
                    UNION SELECT c.parent_id FROM catalog_contents c WHERE c.id = ?
                    UNION SELECT p.parent_id FROM catalog_contents c
                        JOIN catalog_contents p ON p.id = c.parent_id WHERE c.id = ?
                ) ORDER BY id FOR SHARE
                """, (rs, row) -> rs.getObject(1, UUID.class), contentId, contentId, contentId);
    }

    Session owned(IdentityPrincipal actor, UUID id) {
        return jdbc.query("""
                SELECT * FROM playback_sessions WHERE id = ? AND account_id = ? AND auth_session_id = ? FOR UPDATE
                """, SESSION, id, actor.accountId(), actor.sessionId()).stream().findFirst().orElseThrow(DomainException::missing);
    }

    void insert(UUID id, IdentityPrincipal actor, EligibleContent content, Instant now, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO playback_sessions(id, account_id, profile_id, auth_session_id, content_id, binding_id,
                    asset_id, asset_version, published_revision, duration_ms, opened_at, expires_at, last_progress_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.accountId(), content.profileId(), actor.sessionId(), content.contentId(), content.bindingId(),
                content.assetId(), content.assetVersion(), content.publishedRevision(), content.durationSeconds() * 1000L,
                Timestamp.from(now), Timestamp.from(expiresAt), Timestamp.from(now));
    }

    void renew(UUID id, Instant expiry) {
        jdbc.update("UPDATE playback_sessions SET expires_at = ? WHERE id = ?", Timestamp.from(expiry), id);
    }

    void position(UUID id, long position) {
        jdbc.update("UPDATE playback_sessions SET position_ms = ? WHERE id = ?", position, id);
    }

    void progress(UUID id, Progress progress, long watched, Instant now) {
        jdbc.update("""
                UPDATE playback_sessions SET sequence = ?, position_ms = ?, watched_ms = ?, state = ?, last_progress_at = ?
                WHERE id = ?
                """, progress.sequence(), progress.positionMs(), watched, progress.state().name(), Timestamp.from(now), id);
    }

    record Session(UUID id, UUID profileId, UUID contentId, UUID bindingId, UUID assetId, long assetVersion,
            long durationMs, Instant expiresAt, Instant lastProgressAt, long sequence, long positionMs,
            long watchedMs, State state) {}
}
