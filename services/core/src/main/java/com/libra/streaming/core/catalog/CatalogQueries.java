package com.libra.streaming.core.catalog;

import com.libra.streaming.core.api.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;

@Service
public class CatalogQueries {
    // The hierarchy is immutable and bounded to series -> season -> episode.
    private static final String PUBLIC = """
            SELECT c.*, r.metadata, b.state, b.duration_seconds
            FROM catalog_contents c
            JOIN catalog_revisions r ON r.content_id = c.id AND r.revision = c.published_revision
            LEFT JOIN catalog_contents p ON p.id = c.parent_id
            LEFT JOIN catalog_contents g ON g.id = p.parent_id
            LEFT JOIN catalog_media_bindings b ON b.id = c.active_binding
            WHERE (c.parent_id IS NULL OR p.published_revision IS NOT NULL)
              AND (p.parent_id IS NULL OR g.published_revision IS NOT NULL)
            """;
    private final JdbcTemplate jdbc;
    private final RowMapper<PublicView> view;

    public CatalogQueries(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.view = (rs, row) -> new PublicView(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
                rs.getObject("parent_id", UUID.class), rs.getObject("ordinal", Integer.class), rs.getLong("published_revision"),
                mapper.readValue(rs.getString("metadata"), Metadata.class), "READY".equals(rs.getString("state")),
                "READY".equals(rs.getString("state")) ? rs.getObject("duration_seconds", Integer.class) : null,
                rs.getTimestamp("published_at").toInstant());
    }

    public PublicView get(UUID id) {
        return jdbc.query(PUBLIC + " AND c.id = ?", view, id).stream().findFirst().orElseThrow(DomainException::missing);
    }

    public List<PublicView> search(String query, Kind kind, String genre, Integer year, String language,
            Tier tier, int limit, int offset) {
        CatalogService.page(limit, offset);
        if (query == null || query.length() > 200 || genre == null || genre.length() > 40
                || language == null || language.length() > 10 || (year != null && (year < 1888 || year > 2200))) {
            throw DomainException.invalid();
        }
        StringBuilder sql = new StringBuilder(PUBLIC);
        List<Object> args = new ArrayList<>();
        if (!query.isBlank()) { sql.append(" AND r.search_vector @@ plainto_tsquery('simple', ?)"); args.add(query); }
        if (kind != null) { sql.append(" AND c.kind = ?"); args.add(kind.name()); }
        if (!genre.isBlank()) { sql.append(" AND jsonb_exists(r.metadata->'genres', ?)"); args.add(genre); }
        if (year != null) { sql.append(" AND (r.metadata->>'releaseYear')::integer = ?"); args.add(year); }
        if (!language.isBlank()) { sql.append(" AND r.metadata->>'language' = ?"); args.add(language); }
        if (tier != null) { sql.append(" AND r.metadata->>'accessTier' = ?"); args.add(tier.name()); }
        sql.append(" ORDER BY c.published_at DESC, c.id LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), view, args.toArray());
    }

    public List<PublicView> children(UUID parentId, int limit, int offset) {
        CatalogService.page(limit, offset);
        get(parentId);
        return jdbc.query(PUBLIC + " AND c.parent_id = ? ORDER BY c.ordinal, c.id LIMIT ? OFFSET ?",
                view, parentId, limit, offset);
    }

    // Recommendations group episodes under a series. Publication and READY are still Core checks.
    private static final String RECOMMENDABLE = """
            AND ((c.kind = 'MOVIE' AND b.state = 'READY') OR (c.kind = 'SERIES' AND EXISTS (
                SELECT 1 FROM catalog_contents season JOIN catalog_contents episode ON episode.parent_id = season.id
                JOIN catalog_media_bindings asset ON asset.id = episode.active_binding AND asset.state = 'READY'
                WHERE season.parent_id = c.id AND season.published_revision IS NOT NULL
                    AND episode.published_revision IS NOT NULL)))
            """;

    public List<PublicView> recommendedCandidates(List<UUID> ids, int limit) {
        CatalogService.page(limit, 0);
        if (ids == null || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull)) { throw DomainException.invalid(); }
        if (ids.isEmpty()) { return List.of(); }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var visible = jdbc.query(PUBLIC + RECOMMENDABLE + " AND c.id IN (" + placeholders + ")", view, ids.toArray());
        var byId = visible.stream().collect(java.util.stream.Collectors.toMap(PublicView::id, item -> item));
        return ids.stream().distinct().filter(byId::containsKey).limit(limit).map(byId::get).toList();
    }

    public List<PublicView> newestRecommendable(int limit) {
        CatalogService.page(limit, 0);
        return jdbc.query(PUBLIC + RECOMMENDABLE + " ORDER BY c.published_at DESC, c.id LIMIT ?", view, limit);
    }
}
