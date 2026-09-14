package com.libra.streaming.core.integration.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxWriter {
    private static final int MAX_PAYLOAD_BYTES = 262_144;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // The caller owns the business transaction; never create a separate outbox transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(CoreEventTopic topic, EventEnvelope event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        String payload = mapper.writeValueAsString(event.payload());
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Event payload exceeds 256 KiB");
        }
        jdbc.update("""
                INSERT INTO outbox_events (event_id, topic, event_type, schema_version,
                    aggregate_id, aggregate_version, occurred_at, correlation_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, event.eventId(), topic.value(), event.eventType(), event.schemaVersion(),
                event.aggregateId(), event.aggregateVersion(), Timestamp.from(event.occurredAt()),
                event.correlationId(), payload);
    }
}
