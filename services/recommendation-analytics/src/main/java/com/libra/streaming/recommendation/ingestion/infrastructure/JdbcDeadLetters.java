package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.DeadLetters;
import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Captured;
import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Held;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcDeadLetters implements DeadLetters {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcDeadLetters(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void capture(Captured row) {
        jdbc.update("""
                INSERT INTO dead_letters (id, dlt_partition, dlt_offset, source_topic, source_partition,
                    source_offset, event_key, event_body, reason, state, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), row.dltPartition(), row.dltOffset(), row.sourceTopic(), row.sourcePartition(),
                row.sourceOffset(), row.key(), row.body(), row.reason(), row.state(), Timestamp.from(clock.instant()));
    }

    @Override
    public List<Held> held(Held after, int limit) {
        Timestamp afterAt = after == null ? null : Timestamp.from(after.receivedAt());
        UUID afterId = after == null ? null : after.id();
        return jdbc.query("""
                SELECT id, received_at, source_topic, event_key, event_body FROM dead_letters
                WHERE state = 'HELD' AND (CAST(? AS timestamptz) IS NULL OR (received_at, id) > (?, ?))
                ORDER BY received_at, id LIMIT ?
                """, (rs, index) -> new Held(rs.getObject("id", UUID.class), rs.getTimestamp("received_at").toInstant(),
                InputTopic.fromWireName(rs.getString("source_topic")), rs.getString("event_key"),
                rs.getString("event_body")), afterAt, afterAt, afterId, limit);
    }

    @Override
    public int countHeld() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE state = 'HELD'", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void redriven(UUID id, Instant at) {
        jdbc.update("UPDATE dead_letters SET state = 'REDRIVEN', redriven_at = ? WHERE id = ? AND state = 'HELD'",
                Timestamp.from(at), id);
    }

    @Override
    public void attemptFailed(UUID id, String code) {
        jdbc.update("""
                UPDATE dead_letters SET redrive_attempts = redrive_attempts + 1, last_redrive_code = ?
                WHERE id = ? AND state = 'HELD'
                """, code, id);
    }
}
