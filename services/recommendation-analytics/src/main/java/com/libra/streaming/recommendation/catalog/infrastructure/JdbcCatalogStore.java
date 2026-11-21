package com.libra.streaming.recommendation.catalog.infrastructure;

import com.libra.streaming.recommendation.catalog.application.CatalogStore;
import com.libra.streaming.recommendation.catalog.domain.CatalogNode;
import com.libra.streaming.recommendation.catalog.domain.Placement;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class JdbcCatalogStore implements CatalogStore {
    /** Core's tree is movie/series/season/episode; a longer chain cannot be real and the walk stops there. */
    private static final int MAX_DEPTH = 8;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    JdbcCatalogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Write upsert(CatalogNode node) {
        var revision = node.published();
        var active = node.active();
        // One statement: concurrent writers of a node (live consumer, bootstrap, redrive) serialize on the row and
        // the WHERE clause keeps the newest version whatever order they commit in.
        // A node that states its publication time (a CatalogPublished event or a snapshot) gives it at its version.
        Long publishedAtVersion = node.publishedAt() == null ? null : node.version();
        int changed = jdbc.update("""
                INSERT INTO catalog_nodes AS stored (content_id, version, kind, parent_id, ordinal, published, active,
                    genres, access_tier, active_state, duration_seconds, published_at, published_at_version, changed_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS text[]), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (content_id) DO UPDATE SET version = EXCLUDED.version, ordinal = EXCLUDED.ordinal,
                    published = EXCLUDED.published, active = EXCLUDED.active, genres = EXCLUDED.genres,
                    access_tier = EXCLUDED.access_tier, active_state = EXCLUDED.active_state,
                    duration_seconds = EXCLUDED.duration_seconds,
                    published_at = CASE WHEN ? AND EXCLUDED.published IS NOT NULL THEN stored.published_at
                        ELSE EXCLUDED.published_at END,
                    published_at_version = CASE WHEN ? AND EXCLUDED.published IS NOT NULL THEN stored.published_at_version
                        ELSE EXCLUDED.published_at_version END,
                    changed_at = EXCLUDED.changed_at
                WHERE stored.version < EXCLUDED.version AND stored.kind = EXCLUDED.kind
                    AND stored.parent_id IS NOT DISTINCT FROM EXCLUDED.parent_id
                """, node.contentId(), node.version(), node.kind().name(), node.parentId(), node.ordinal(),
                revision == null ? null : mapper.writeValueAsString(revision),
                active == null ? null : mapper.writeValueAsString(active),
                revision == null ? "{}" : array(revision.metadata().genres()),
                revision == null ? null : revision.metadata().accessTier(),
                active == null ? null : active.state(), active == null ? null : active.durationSeconds(),
                timestamp(node.publishedAt()), publishedAtVersion, timestamp(node.changedAt()),
                node.inheritsPublishedAt(), node.inheritsPublishedAt());
        if (changed == 1) { return Write.APPLIED; }
        if (publishedAtVersion != null) {
            // A late publication still supplies a fact the newer state lacks: while the node is published, the
            // publication time is that of the highest publication version not above the stored version. An
            // unpublication in between implies a later publication, which overrides this one when it arrives, so
            // the stored value converges whatever the arrival order. The rest of the stale state is ignored.
            jdbc.update("""
                    UPDATE catalog_nodes SET published_at = ?, published_at_version = ?
                    WHERE content_id = ? AND published IS NOT NULL AND version > ? AND kind = ?
                        AND parent_id IS NOT DISTINCT FROM ?
                        AND (published_at_version IS NULL OR published_at_version < ?)
                    """, timestamp(node.publishedAt()), publishedAtVersion, node.contentId(), publishedAtVersion,
                    node.kind().name(), node.parentId(), publishedAtVersion);
        }
        Object[] stored = jdbc.query("SELECT kind, parent_id FROM catalog_nodes WHERE content_id = ?",
                (row, index) -> new Object[] {row.getString("kind"), row.getObject("parent_id", UUID.class)},
                node.contentId()).getFirst();
        // Core never moves a node or changes its kind; a different shape is corrupt input, not an older version.
        if (!node.kind().name().equals(stored[0]) || !Objects.equals(node.parentId(), stored[1])) {
            throw EventRejection.conflict("CATALOG_IDENTITY_CHANGED");
        }
        return Write.STALE;
    }

    @Override
    public Optional<CatalogNode> find(UUID contentId) {
        return jdbc.query("SELECT * FROM catalog_nodes WHERE content_id = ?", (row, index) -> node(row), contentId)
                .stream().findFirst();
    }

    @Override
    public Optional<Placement> placement(UUID contentId) {
        // Walks up the parent chain; a missing ancestor ends the walk and leaves the placement incomplete.
        List<Object[]> chain = jdbc.query("""
                WITH RECURSIVE chain(content_id, kind, parent_id, visible, depth) AS (
                    SELECT content_id, kind, parent_id, published IS NOT NULL, 0 FROM catalog_nodes WHERE content_id = ?
                    UNION ALL
                    SELECT parent.content_id, parent.kind, parent.parent_id, parent.published IS NOT NULL, chain.depth + 1
                    FROM chain JOIN catalog_nodes parent ON parent.content_id = chain.parent_id
                    WHERE chain.depth < ?)
                SELECT content_id, kind, parent_id, visible FROM chain ORDER BY depth
                """, (row, index) -> new Object[] {row.getObject("content_id", UUID.class), row.getString("kind"),
                        row.getObject("parent_id", UUID.class), row.getBoolean("visible")}, contentId, MAX_DEPTH);
        if (chain.isEmpty()) { return Optional.empty(); }
        Object[] top = chain.getLast();
        boolean complete = top[2] == null;
        boolean visible = complete && chain.stream().allMatch(link -> (Boolean) link[3]);
        return Optional.of(new Placement(contentId, CatalogChange.Kind.valueOf((String) chain.getFirst()[1]),
                complete ? (UUID) top[0] : null, complete, visible));
    }

    private CatalogNode node(ResultSet row) throws SQLException {
        String published = row.getString("published");
        String active = row.getString("active");
        Timestamp publishedAt = row.getTimestamp("published_at");
        return new CatalogNode(row.getObject("content_id", UUID.class), row.getLong("version"),
                CatalogChange.Kind.valueOf(row.getString("kind")), row.getObject("parent_id", UUID.class),
                (Integer) row.getObject("ordinal"),
                published == null ? null : mapper.readValue(published, CatalogChange.Revision.class),
                active == null ? null : mapper.readValue(active, CatalogChange.Binding.class),
                publishedAt == null ? null : publishedAt.toInstant(), false, row.getTimestamp("changed_at").toInstant());
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }

    /** Genres are lowercase slugs ({@code [a-z0-9-]}), so a PostgreSQL array literal needs no quoting. */
    private static String array(List<String> values) { return "{" + String.join(",", values) + "}"; }
}
