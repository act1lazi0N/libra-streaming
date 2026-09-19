package com.libra.streaming.core.community;

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
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchlistService {
    private final JdbcTemplate jdbc;
    private final IdentityAccess access;
    private final CommunityCatalog catalog;
    private final Clock clock;
    public WatchlistService(JdbcTemplate jdbc, IdentityAccess access, CommunityCatalog catalog, Clock clock) {
        this.jdbc = jdbc; this.access = access; this.catalog = catalog; this.clock = clock;
    }

    @Transactional
    public void add(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        access.lockCurrent(actor, false);
        owned(actor, profileId);
        catalog.lockPublishedTarget(contentId);
        jdbc.update("""
                INSERT INTO watchlist_entries(profile_id, content_id, added_at) VALUES (?, ?, ?)
                ON CONFLICT (profile_id, content_id) DO NOTHING
                """, profileId, contentId, Timestamp.from(clock.instant()));
    }

    @Transactional
    public void remove(IdentityPrincipal actor, UUID profileId, UUID contentId) {
        access.lockCurrent(actor, false);
        owned(actor, profileId);
        jdbc.update("DELETE FROM watchlist_entries WHERE profile_id = ? AND content_id = ?", profileId, contentId);
    }

    @Transactional
    public List<Entry> list(IdentityPrincipal actor, UUID profileId, int limit, int offset) {
        access.lockCurrent(actor, false);
        owned(actor, profileId);
        CommunityCatalog.page(limit, offset);
        return jdbc.query("""
                SELECT w.content_id, w.added_at, c.kind, r.metadata->>'title' AS title
                FROM watchlist_entries w JOIN catalog_contents c ON c.id = w.content_id
                JOIN catalog_revisions r ON r.content_id = c.id AND r.revision = c.published_revision
                WHERE w.profile_id = ? ORDER BY w.added_at DESC, w.content_id LIMIT ? OFFSET ?
                """, (rs, row) -> new Entry(rs.getObject("content_id", UUID.class), rs.getString("kind"),
                        rs.getString("title"), rs.getTimestamp("added_at").toInstant()), profileId, limit, offset);
    }

    private void owned(IdentityPrincipal actor, UUID profileId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM profiles WHERE id = ? AND account_id = ?)",
                Boolean.class, profileId, actor.accountId()))) { throw DomainException.missing(); }
    }
    public record Entry(UUID contentId, String kind, String title, Instant addedAt) {}
}
