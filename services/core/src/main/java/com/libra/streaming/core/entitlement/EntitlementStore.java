package com.libra.streaming.core.entitlement;

import com.libra.streaming.core.identity.IdentityException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class EntitlementStore {
    private final JdbcTemplate jdbc;

    EntitlementStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Snapshot current(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        // One statement observes one committed snapshot, including publication and its exact active binding.
        // Never combine a public metadata read with a later candidate/media read.
        return jdbc.query("""
                SELECT a.status = 'ACTIVE' AS account_active, a.email_verified,
                    s.expires_at AS session_expiry, s.revoked_at IS NULL AS session_not_revoked,
                    p.id IS NOT NULL AS profile_owned,
                    r.content_id IS NOT NULL
                        AND (c.parent_id IS NULL OR parent.published_revision IS NOT NULL)
                        AND (parent.parent_id IS NULL OR grandparent.published_revision IS NOT NULL) AS visible,
                    c.kind, c.published_revision, r.metadata->>'accessTier' AS access_tier,
                    b.id AS binding_id, b.asset_id, b.asset_version, b.state AS media_state, b.duration_seconds,
                    premium.expires_at AS premium_expiry
                FROM identity_accounts a
                LEFT JOIN identity_sessions s ON s.account_id = a.id AND s.id = ?
                LEFT JOIN profiles p ON p.account_id = a.id AND p.id = ?
                LEFT JOIN catalog_contents c ON c.id = ?
                LEFT JOIN catalog_revisions r ON r.content_id = c.id AND r.revision = c.published_revision
                LEFT JOIN catalog_contents parent ON parent.id = c.parent_id
                LEFT JOIN catalog_contents grandparent ON grandparent.id = parent.parent_id
                LEFT JOIN catalog_media_bindings b ON b.content_id = c.id AND b.id = c.active_binding
                LEFT JOIN subscriptions premium ON premium.account_id = a.id
                WHERE a.id = ?
                """, (rs, row) -> new Snapshot(rs.getBoolean("account_active"), rs.getBoolean("email_verified"),
                        rs.getBoolean("session_not_revoked"), instant(rs, "session_expiry"), rs.getBoolean("profile_owned"),
                        rs.getBoolean("visible"), rs.getString("kind"), rs.getObject("published_revision", Long.class),
                        rs.getString("access_tier"), rs.getObject("binding_id", UUID.class), rs.getObject("asset_id", UUID.class),
                        rs.getObject("asset_version", Long.class), rs.getString("media_state"),
                        rs.getObject("duration_seconds", Integer.class), instant(rs, "premium_expiry")),
                actor.sessionId(), profileId, contentId, actor.accountId()).stream().findFirst()
                .orElseThrow(IdentityException::unauthenticated);
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    record Snapshot(boolean accountActive, boolean emailVerified, boolean sessionNotRevoked, Instant sessionExpiry,
            boolean profileOwned, boolean visible, String kind, Long publishedRevision, String accessTier,
            UUID bindingId, UUID assetId, Long assetVersion, String mediaState, Integer durationSeconds,
            Instant premiumExpiry) {}
}
