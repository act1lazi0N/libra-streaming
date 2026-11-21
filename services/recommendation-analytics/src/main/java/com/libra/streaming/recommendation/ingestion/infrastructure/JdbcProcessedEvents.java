package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.application.ProcessedEvents;
import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcProcessedEvents implements ProcessedEvents {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Receipt record(CoreEvent event) {
        var envelope = event.envelope();
        // A concurrent writer of the same event ID blocks here until it commits or rolls back, so exactly one of
        // them applies the event.
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, topic, event_type, aggregate_id, aggregate_version, processed_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
                """, envelope.eventId(), event.topic().wireName(), envelope.eventType(), envelope.aggregateId(),
                envelope.aggregateVersion(), Timestamp.from(clock.instant()));
        if (inserted == 1) { return Receipt.NEW; }
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT topic, event_type, aggregate_id, aggregate_version FROM processed_events WHERE event_id = ?",
                envelope.eventId());
        boolean same = event.topic().wireName().equals(stored.get("topic"))
                && envelope.eventType().equals(stored.get("event_type"))
                && envelope.aggregateId().equals((UUID) stored.get("aggregate_id"))
                && envelope.aggregateVersion() == ((Number) stored.get("aggregate_version")).longValue();
        return same ? Receipt.DUPLICATE : Receipt.CONFLICT;
    }
}
