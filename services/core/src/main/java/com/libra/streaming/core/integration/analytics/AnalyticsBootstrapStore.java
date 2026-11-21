package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.CatalogModels.Binding;
import com.libra.streaming.core.catalog.CatalogModels.Kind;
import com.libra.streaming.core.catalog.CatalogModels.Metadata;
import com.libra.streaming.core.catalog.CatalogModels.Revision;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Read side of core-internal-analytics.v1 (RA02.4): versioned snapshots of the catalog and profile state that
 * Analytics would have built from {@code core.catalog.v1} and {@code core.profiles.v1}, and a replay of the
 * {@code core.playback.v1} events kept in the outbox. Every page is one repeatable-read snapshot of committed data.
 */
@Repository
public class AnalyticsBootstrapStore {
    public static final int MAX_LIMIT = 500;
    private static final Pattern CURSOR = Pattern.compile("(\\d{1,19})\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    /** The node exactly as the latest catalog event would carry it, at the node's current version. */
    public record CatalogItem(UUID contentId, long version, Kind kind, UUID parentId, Integer ordinal, Revision published,
            Binding active, Instant publishedAt) {}
    public record CatalogPage(List<CatalogItem> items, UUID next) {}
    /** A live profile, or the tombstone of a deleted one (taken from its {@code ProfileDeleted} event). */
    public record ProfileItem(UUID profileId, UUID accountId, long version, boolean deleted) {}
    public record ProfilePage(List<ProfileItem> items, UUID next) {}
    public record PlaybackPage(List<EventEnvelope> events, String next) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AnalyticsBootstrapStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CatalogPage catalog(UUID after, int limit) {
        checkLimit(limit);
        // Same shape as CatalogService.publicationEvent: revision and active binding only while published.
        List<CatalogItem> items = jdbc.query("""
                SELECT c.id, c.kind, c.parent_id, c.ordinal, c.version, c.published_revision, c.published_at,
                    r.metadata, r.created_at AS revision_created_at,
                    b.id AS binding_id, b.asset_id, b.asset_version, b.projection_version, b.state, b.duration_seconds
                FROM catalog_contents c
                LEFT JOIN catalog_revisions r ON r.content_id = c.id AND r.revision = c.published_revision
                LEFT JOIN catalog_media_bindings b ON c.published_revision IS NOT NULL AND b.id = c.active_binding
                """ + (after == null ? "" : "WHERE c.id > ?") + " ORDER BY c.id LIMIT ?", (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            Revision published = rs.getObject("published_revision") == null ? null : new Revision(
                    rs.getLong("published_revision"), mapper.readValue(rs.getString("metadata"), Metadata.class),
                    rs.getTimestamp("revision_created_at").toInstant());
            Binding active = rs.getObject("binding_id") == null ? null : new Binding(rs.getObject("binding_id", UUID.class),
                    id, rs.getObject("asset_id", UUID.class), rs.getLong("asset_version"), rs.getLong("projection_version"),
                    rs.getString("state"), rs.getObject("duration_seconds", Integer.class));
            Timestamp publishedAt = rs.getTimestamp("published_at");
            return new CatalogItem(id, rs.getLong("version"), Kind.valueOf(rs.getString("kind")),
                    rs.getObject("parent_id", UUID.class), rs.getObject("ordinal", Integer.class), published, active,
                    publishedAt == null ? null : publishedAt.toInstant());
        }, args(after, limit));
        return new CatalogPage(items, items.size() == limit ? items.getLast().contentId() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProfilePage profiles(UUID after, int limit) {
        checkLimit(limit);
        // Core deletes profile rows; the outbox keeps each deletion's ProfileDeleted event, the tombstone source.
        // Two statements instead of "? IS NULL OR id > ?": a generic plan would otherwise scan from the start.
        String live = after == null ? "" : " WHERE id > ?";
        String tombstones = after == null ? "" : " AND o.aggregate_id > ?";
        Object[] args = after == null ? new Object[] {limit} : new Object[] {after, after, limit};
        List<ProfileItem> items = jdbc.query("""
                SELECT id, account_id, version, FALSE AS deleted FROM profiles""" + live + """

                UNION ALL
                SELECT o.aggregate_id, CAST(o.payload ->> 'accountId' AS uuid), o.aggregate_version, TRUE
                FROM outbox_events o
                WHERE o.topic = 'core.profiles.v1' AND o.event_type = 'ProfileDeleted'
                    AND NOT EXISTS (SELECT 1 FROM profiles p WHERE p.id = o.aggregate_id)""" + tombstones + """

                ORDER BY 1 LIMIT ?
                """, (rs, row) -> new ProfileItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                        rs.getBoolean(4)), args);
        return new ProfilePage(items, items.size() == limit ? items.getLast().profileId() : null);
    }

    /**
     * Playback events in (created_at, event_id) order. The order is the insert time, not the commit time: an event
     * whose transaction commits after a page passed its position is not in this replay, but the outbox publisher
     * still sends it to the live topic, which Analytics consumes as well.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlaybackPage playback(String after, int limit) {
        checkLimit(limit);
        Timestamp afterTime = null;
        UUID afterId = null;
        if (after != null) {
            var matcher = CURSOR.matcher(after);
            if (!matcher.matches()) { throw DomainException.invalid(); }
            try {
                afterTime = Timestamp.from(Instant.EPOCH.plus(Long.parseLong(matcher.group(1)), ChronoUnit.MICROS));
                afterId = UUID.fromString(matcher.group(2));
            } catch (RuntimeException invalid) { throw DomainException.invalid(); }
        }
        record Row(EventEnvelope event, Instant createdAt) {}
        List<Row> rows = jdbc.query("""
                SELECT event_id, event_type, schema_version, aggregate_id, aggregate_version, occurred_at, correlation_id,
                    payload, created_at
                FROM outbox_events
                WHERE topic = 'core.playback.v1'""" + (afterTime == null ? "" : " AND (created_at, event_id) > (?, ?)") + """

                ORDER BY created_at, event_id LIMIT ?
                """, (rs, row) -> new Row(new EventEnvelope(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getInt("schema_version"), rs.getObject("aggregate_id", UUID.class), rs.getLong("aggregate_version"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getObject("correlation_id", UUID.class),
                        mapper.readTree(rs.getString("payload"))), rs.getTimestamp("created_at").toInstant()),
                afterTime == null ? new Object[] {limit} : new Object[] {afterTime, afterId, limit});
        String next = null;
        if (rows.size() == limit) {
            Row last = rows.getLast();
            next = ChronoUnit.MICROS.between(Instant.EPOCH, last.createdAt()) + "." + last.event().eventId();
        }
        return new PlaybackPage(rows.stream().map(Row::event).toList(), next);
    }

    private static Object[] args(UUID after, int limit) {
        return after == null ? new Object[] {limit} : new Object[] {after, limit};
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) { throw DomainException.invalid(); }
    }
}
