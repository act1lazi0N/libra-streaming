package com.libra.streaming.core.playback;

import com.libra.streaming.core.entitlement.application.EntitlementOperations;
import com.libra.streaming.core.identity.IdentityAccess;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NextEpisodeService {
    private final JdbcTemplate jdbc;
    private final IdentityAccess access;
    private final EntitlementOperations entitlements;
    private final Clock clock;

    public NextEpisodeService(JdbcTemplate jdbc, IdentityAccess access, EntitlementOperations entitlements, Clock clock) {
        this.jdbc = jdbc; this.access = access; this.entitlements = entitlements; this.clock = clock;
    }

    @Transactional
    public NextEpisode next(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        access.lockCurrent(actor, false);
        entitlements.requireEligible(actor, profileId, contentId);
        var candidates = jdbc.query("""
                SELECT e.id FROM catalog_contents current_episode
                JOIN catalog_contents current_season ON current_season.id = current_episode.parent_id
                JOIN catalog_contents s ON s.parent_id = current_season.parent_id AND s.kind = 'SEASON'
                JOIN catalog_contents series ON series.id = s.parent_id
                JOIN catalog_contents e ON e.parent_id = s.id AND e.kind = 'EPISODE'
                JOIN catalog_revisions r ON r.content_id = e.id AND r.revision = e.published_revision
                JOIN catalog_media_bindings b ON b.content_id = e.id AND b.id = e.active_binding
                LEFT JOIN subscriptions premium ON premium.account_id = ?
                WHERE current_episode.id = ? AND current_episode.kind = 'EPISODE'
                    AND s.published_revision IS NOT NULL AND series.published_revision IS NOT NULL
                    AND b.state = 'READY' AND b.duration_seconds IS NOT NULL
                    AND (r.metadata->>'accessTier' = 'FREE' OR premium.expires_at > ?)
                    AND (s.ordinal > current_season.ordinal OR
                        (s.ordinal = current_season.ordinal AND e.ordinal > current_episode.ordinal))
                ORDER BY s.ordinal, e.ordinal, e.id LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), actor.accountId(), contentId, Timestamp.from(clock.instant()));
        return new NextEpisode(candidates.isEmpty() ? null : candidates.getFirst());
    }

    public record NextEpisode(UUID contentId) {}
}
