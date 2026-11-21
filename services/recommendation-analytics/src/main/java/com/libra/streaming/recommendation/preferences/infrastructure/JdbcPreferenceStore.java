package com.libra.streaming.recommendation.preferences.infrastructure;

import com.libra.streaming.recommendation.preferences.application.PreferenceStore;
import com.libra.streaming.recommendation.preferences.domain.WatchedItem;
import com.libra.streaming.recommendation.profiles.application.ProfileErasure;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPreferenceStore implements PreferenceStore, ProfileErasure {
    private final JdbcTemplate jdbc;

    JdbcPreferenceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void add(UUID profileId, UUID accountId, UUID contentId, LocalDate day, long acceptedWatchedMs) {
        // The upsert locks the row, so concurrent increments of one row add up instead of overwriting each other.
        jdbc.update("""
                INSERT INTO profile_watch_days AS stored (profile_id, content_id, day, account_id, accepted_watched_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (profile_id, content_id, day) DO UPDATE
                    SET accepted_watched_ms = stored.accepted_watched_ms + EXCLUDED.accepted_watched_ms
                    WHERE stored.account_id = EXCLUDED.account_id
                """, profileId, contentId, Date.valueOf(day), accountId, acceptedWatchedMs);
    }

    @Override
    public List<WatchedItem> watched(UUID accountId, UUID profileId, LocalDate fromDay) {
        // A movie or series is its own title; an episode belongs to the series above its season. The title's genres
        // are those of its published revision (empty while unpublished, see V2). Nothing else is resolved here.
        return jdbc.query("""
                SELECT watched.content_id, watched.watched_ms, item.duration_seconds,
                    CASE WHEN item.kind IN ('MOVIE', 'SERIES') THEN item.content_id ELSE series.content_id END AS title_id,
                    CASE WHEN item.kind IN ('MOVIE', 'SERIES') THEN item.genres ELSE series.genres END AS title_genres
                FROM (
                    SELECT content_id, sum(accepted_watched_ms) AS watched_ms
                    FROM profile_watch_days
                    WHERE profile_id = ? AND account_id = ? AND day >= ?
                    GROUP BY content_id
                ) watched
                LEFT JOIN catalog_nodes item ON item.content_id = watched.content_id
                LEFT JOIN catalog_nodes season ON item.kind = 'EPISODE' AND season.content_id = item.parent_id
                    AND season.kind = 'SEASON'
                LEFT JOIN catalog_nodes series ON series.content_id = season.parent_id AND series.kind = 'SERIES'
                ORDER BY watched.content_id
                """, (row, index) -> {
            Integer durationSeconds = (Integer) row.getObject("duration_seconds");
            return new WatchedItem(row.getObject("content_id", UUID.class), row.getObject("title_id", UUID.class),
                    durationSeconds == null ? null : durationSeconds * 1000L, genres(row.getArray("title_genres")),
                    row.getLong("watched_ms"));
        }, profileId, accountId, Date.valueOf(fromDay));
    }

    /** Personal data: the whole per-profile rollup goes with the tombstone (RA01.1, "Profiles — tombstone"). */
    @Override
    public void erase(UUID profileId) {
        jdbc.update("DELETE FROM profile_watch_days WHERE profile_id = ?", profileId);
    }

    private static List<String> genres(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
