package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.bootstrap.application.BootstrapProgress;
import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Joins the transaction owned by Bootstrap, including the page's projection writes. */
public final class JdbcBootstrapProgress implements BootstrapProgress {
    private final JdbcTemplate jdbc;

    public JdbcBootstrapProgress(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Position start(long generation, BootstrapSource source, Instant at) {
        jdbc.update("""
                INSERT INTO bootstrap_progress (generation, source, started_at, updated_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (generation, source) DO NOTHING
                """, generation, source.name(), Timestamp.from(at), Timestamp.from(at));
        return find(generation, source).orElseThrow();
    }

    @Override
    public void advance(long generation, BootstrapSource source, String cursor, long applied, long rejected,
            boolean completed, Instant at) {
        jdbc.update("""
                UPDATE bootstrap_progress SET cursor = ?, applied = ?, rejected = ?, updated_at = ?, completed_at = ?
                WHERE generation = ? AND source = ? AND completed_at IS NULL
                """, cursor, applied, rejected, Timestamp.from(at), completed ? Timestamp.from(at) : null,
                generation, source.name());
    }

    @Override
    public Optional<Position> find(long generation, BootstrapSource source) {
        return jdbc.query("""
                SELECT cursor, applied, rejected, completed_at FROM bootstrap_progress
                WHERE generation = ? AND source = ?
                """, (row, index) -> new Position(row.getString("cursor"), row.getLong("applied"),
                row.getLong("rejected"), row.getTimestamp("completed_at") != null), generation, source.name())
                .stream().findFirst();
    }
}
