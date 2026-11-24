package com.libra.streaming.recommendation.statistics.infrastructure;

import com.libra.streaming.recommendation.statistics.application.StatisticsStore;
import com.libra.streaming.recommendation.statistics.domain.ContentTotals;
import com.libra.streaming.recommendation.statistics.domain.DayContribution;
import com.libra.streaming.recommendation.statistics.domain.DayTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcStatisticsStore implements StatisticsStore {
    private static final String TOTALS = "COALESCE(sum(qualified_views), 0) AS views, "
            + "count(DISTINCT account_id) FILTER (WHERE qualified_views > 0) AS viewers, "
            + "COALESCE(sum(accepted_watched_ms), 0) AS watched_ms";

    private final JdbcTemplate jdbc;

    JdbcStatisticsStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void add(UUID accountId, List<DayContribution> contributions) {
        for (DayContribution change : contributions) {
            if (change.qualifiedViews() < 0) { moveViewAway(accountId, change); }
            else {
                // A proposed row is checked against the CHECK constraints before the conflict is detected, so only
                // nonnegative changes go through the upsert.
                jdbc.update("""
                        INSERT INTO statistics_account_days AS stored (day, account_id, qualified_views, accepted_watched_ms)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (day, account_id) DO UPDATE SET
                            qualified_views = stored.qualified_views + EXCLUDED.qualified_views,
                            accepted_watched_ms = stored.accepted_watched_ms + EXCLUDED.accepted_watched_ms
                        """, change.day(), accountId, change.qualifiedViews(), change.acceptedWatchedMs());
            }
        }
    }

    /** The day losing a view counted it earlier in the same rollup, so its row must exist with that view. */
    private void moveViewAway(UUID accountId, DayContribution change) {
        int updated = jdbc.update("""
                UPDATE statistics_account_days SET qualified_views = qualified_views + ?,
                    accepted_watched_ms = accepted_watched_ms + ?
                WHERE day = ? AND account_id = ? AND qualified_views + ? >= 0
                """, change.qualifiedViews(), change.acceptedWatchedMs(), change.day(), accountId, change.qualifiedViews());
        // Rolls the intake transaction back: the record is retried, then parked, instead of corrupting the totals.
        if (updated != 1) { throw new IllegalStateException("Statistics rollup has no view to move"); }
        jdbc.update("DELETE FROM statistics_account_days WHERE day = ? AND account_id = ? "
                + "AND qualified_views = 0 AND accepted_watched_ms = 0", change.day(), accountId);
    }

    @Override
    public StatisticsTotals totals(StatisticsWindow window) {
        return jdbc.queryForObject("SELECT " + TOTALS + " FROM statistics_account_days WHERE day BETWEEN ? AND ?",
                (row, index) -> totals(row), window.from(), window.to());
    }

    @Override
    public List<DayTotals> days(StatisticsWindow window) {
        return jdbc.query("""
                SELECT calendar.day::date AS day,
                    COALESCE(sum(stored.qualified_views), 0) AS views,
                    count(DISTINCT stored.account_id) FILTER (WHERE stored.qualified_views > 0) AS viewers,
                    COALESCE(sum(stored.accepted_watched_ms), 0) AS watched_ms
                FROM generate_series(?::date, ?::date, interval '1 day') AS calendar (day)
                LEFT JOIN statistics_account_days stored ON stored.day = calendar.day::date
                GROUP BY calendar.day ORDER BY calendar.day
                """, (row, index) -> new DayTotals(row.getObject("day", java.time.LocalDate.class), totals(row)),
                window.from(), window.to());
    }

    /**
     * Read from the facts and sessions themselves, which the rollup is derived from in the same transaction: a
     * session counts on the UTC date of its qualifying instant, a fact on the UTC date of its {@code occurred_at},
     * exactly the day rule of {@link DayContribution#dayOf}. Unknown placements fall back to the watched item's ID.
     */
    @Override
    public List<ContentTotals> topContent(StatisticsWindow window, int limit) {
        OffsetDateTime start = startOf(window.from());
        OffsetDateTime end = startOf(window.to().plusDays(1));
        return jdbc.query("""
                WITH watched AS (
                    SELECT session.content_id AS item_id, session.account_id, 1 AS views, 0::bigint AS watched_ms
                    FROM playback_sessions session
                    WHERE session.qualified_at >= ? AND session.qualified_at < ?
                    UNION ALL
                    SELECT fact.content_id, NULL::uuid, 0, sum(fact.accepted_watched_ms)
                    FROM playback_facts fact
                    WHERE fact.occurred_at >= ? AND fact.occurred_at < ?
                    GROUP BY fact.content_id
                ), titled AS (
                    SELECT COALESCE(CASE WHEN node.kind IN ('MOVIE', 'SERIES') THEN node.content_id
                            ELSE series.content_id END, watched.item_id) AS title_id,
                        watched.account_id, watched.views, watched.watched_ms
                    FROM watched
                    LEFT JOIN catalog_nodes node ON node.content_id = watched.item_id
                    LEFT JOIN catalog_nodes season ON node.kind = 'EPISODE' AND season.content_id = node.parent_id
                        AND season.kind = 'SEASON'
                    LEFT JOIN catalog_nodes series ON series.content_id = season.parent_id AND series.kind = 'SERIES'
                )
                SELECT title_id, sum(views) AS views, count(DISTINCT account_id) AS viewers,
                    sum(watched_ms) AS watched_ms
                FROM titled
                GROUP BY title_id
                HAVING sum(views) > 0 OR sum(watched_ms) > 0
                ORDER BY views DESC, viewers DESC, watched_ms DESC, title_id::text
                LIMIT ?
                """, (row, index) -> new ContentTotals(row.getObject("title_id", UUID.class), totals(row)),
                start, end, start, end, limit);
    }

    private static OffsetDateTime startOf(LocalDate day) { return day.atStartOfDay().atOffset(ZoneOffset.UTC); }

    private static StatisticsTotals totals(ResultSet row) throws SQLException {
        return new StatisticsTotals(row.getLong("views"), row.getLong("viewers"), row.getLong("watched_ms"));
    }
}
