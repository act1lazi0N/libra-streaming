package com.libra.streaming.recommendation.recommendations.infrastructure;

import com.libra.streaming.recommendation.recommendations.application.CandidateStore;
import com.libra.streaming.recommendation.recommendations.domain.TitleCandidate;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCandidateStore implements CandidateStore {
    /**
     * A published episode with a READY asset under a published season of the series {@code title}. Mirrors Core's
     * RECOMMENDABLE rule for a series; Core rechecks it on its own tables.
     */
    private static final String PLAYABLE_EPISODE = """
            SELECT 1 FROM catalog_nodes season JOIN catalog_nodes episode ON episode.parent_id = season.content_id
            WHERE season.parent_id = title.content_id AND season.kind = 'SEASON' AND season.published IS NOT NULL
                AND episode.kind = 'EPISODE' AND episode.published IS NOT NULL AND episode.active_state = 'READY'
            """;

    /** The movie or series a watched node belongs to, given the node {@code node} (a movie, series or episode). */
    private static final String TITLE_OF_NODE = """
            CASE WHEN node.kind IN ('MOVIE', 'SERIES') THEN node.content_id ELSE series.content_id END
            """;
    private static final String TITLE_JOINS = """
            LEFT JOIN catalog_nodes season ON node.kind = 'EPISODE' AND season.content_id = node.parent_id
                AND season.kind = 'SEASON'
            LEFT JOIN catalog_nodes series ON series.content_id = season.parent_id AND series.kind = 'SERIES'
            """;

    private final JdbcTemplate jdbc;

    JdbcCandidateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<TitleCandidate> titles() {
        return jdbc.query("""
                SELECT title.content_id, title.kind, title.genres, title.published_at
                FROM catalog_nodes title
                WHERE title.kind IN ('MOVIE', 'SERIES') AND title.published IS NOT NULL
                    AND ((title.kind = 'MOVIE' AND title.active_state = 'READY')
                        OR (title.kind = 'SERIES' AND EXISTS (%s)))
                ORDER BY title.content_id
                """.formatted(PLAYABLE_EPISODE), (row, index) -> {
            Timestamp publishedAt = row.getTimestamp("published_at");
            return new TitleCandidate(row.getObject("content_id", UUID.class),
                    TitleCandidate.Kind.valueOf(row.getString("kind")), genres(row.getArray("genres")),
                    publishedAt == null ? null : publishedAt.toInstant());
        });
    }

    @Override
    public Map<UUID, Long> viewersSince(Instant since) {
        Map<UUID, Long> viewers = new HashMap<>();
        jdbc.query("""
                SELECT title_id, count(DISTINCT account_id) AS viewers
                FROM (
                    SELECT watched.account_id, %s AS title_id
                    FROM playback_sessions watched
                    JOIN catalog_nodes node ON node.content_id = watched.content_id
                    %s
                    WHERE watched.qualified_at >= ?
                ) sessions
                WHERE title_id IS NOT NULL
                GROUP BY title_id
                """.formatted(TITLE_OF_NODE, TITLE_JOINS), row -> {
            viewers.put(row.getObject("title_id", UUID.class), row.getLong("viewers"));
        }, Timestamp.from(since));
        return Map.copyOf(viewers);
    }

    @Override
    public Set<UUID> completed(UUID accountId, UUID profileId) {
        // Core's rule (ResumeState.completes: position * 100 >= duration * 95) applied as Core's history applies it:
        // to the latest accepted position of each item, so seeking back below 95% (or rewatching) reopens the item.
        // The duration is the item's current one.
        Set<UUID> completed = new HashSet<>();
        jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (fact.content_id) fact.content_id, fact.position_ms
                    FROM playback_facts fact
                    WHERE fact.profile_id = ? AND fact.account_id = ?
                    ORDER BY fact.content_id, fact.occurred_at DESC, fact.session_id, fact.sequence DESC
                ), done AS (
                    SELECT latest.content_id
                    FROM latest JOIN catalog_nodes item ON item.content_id = latest.content_id
                    WHERE item.duration_seconds IS NOT NULL
                        AND latest.position_ms * 100 >= item.duration_seconds::bigint * 95000
                )
                SELECT node.content_id AS title_id
                FROM done JOIN catalog_nodes node ON node.content_id = done.content_id AND node.kind = 'MOVIE'
                UNION
                SELECT series.content_id
                FROM catalog_nodes series
                JOIN catalog_nodes season ON season.parent_id = series.content_id AND season.kind = 'SEASON'
                    AND season.published IS NOT NULL
                JOIN catalog_nodes episode ON episode.parent_id = season.content_id AND episode.kind = 'EPISODE'
                    AND episode.published IS NOT NULL AND episode.active_state = 'READY'
                LEFT JOIN done finished ON finished.content_id = episode.content_id
                WHERE series.kind = 'SERIES' AND series.content_id IN (
                    SELECT watched_season.parent_id
                    FROM done
                    JOIN catalog_nodes watched ON watched.content_id = done.content_id AND watched.kind = 'EPISODE'
                    JOIN catalog_nodes watched_season ON watched_season.content_id = watched.parent_id)
                GROUP BY series.content_id
                HAVING bool_and(finished.content_id IS NOT NULL)
                """, row -> { completed.add(row.getObject("title_id", UUID.class)); }, profileId, accountId);
        return Set.copyOf(completed);
    }

    private static List<String> genres(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
