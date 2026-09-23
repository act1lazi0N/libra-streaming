package com.libra.streaming.core.integration.delivery;

import com.libra.streaming.core.integration.outbox.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DeliveryStore {
    public enum Kind {
        OUTBOX("outbox_events", "event_id", "published_at"), MEDIA_REDRIVE("media_dead_letters", "id", "redriven_at");
        final String table; final String id; final String sentAt;
        Kind(String table, String id, String sentAt) { this.table = table; this.id = id; this.sentAt = sentAt; }
    }
    public record Job(Kind kind, UUID id, UUID leaseId, String topic, String key, String body) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public DeliveryStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; }

    @Transactional
    public Optional<Job> claim(Kind kind) {
        var now = clock.instant(); UUID lease = UUID.randomUUID();
        // Identifiers are closed enum values. A parked predecessor blocks later events of that aggregate.
        String predecessor = kind == Kind.OUTBOX ? """
                AND NOT EXISTS (SELECT 1 FROM outbox_events older WHERE older.topic = candidate.topic
                    AND older.aggregate_id = candidate.aggregate_id AND older.published_at IS NULL
                    AND (older.aggregate_version, older.created_at, older.event_id)
                      < (candidate.aggregate_version, candidate.created_at, candidate.event_id))
                """ : "";
        String sql = "WITH selected AS (SELECT candidate." + kind.id + " FROM " + kind.table + " candidate "
                + "WHERE delivery_state = 'PENDING' AND next_attempt_at <= ? AND (lease_until IS NULL OR lease_until <= ?) "
                + predecessor + " ORDER BY created_at, " + kind.id + " FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE " + kind.table
                + " t SET lease_id = ?, lease_until = ?, attempts = attempts + 1, delivery_version = delivery_version + 1 "
                + "FROM selected s WHERE t." + kind.id + " = s." + kind.id + " RETURNING t.*";
        return jdbc.query(sql, (rs, row) -> {
            UUID id = rs.getObject(kind.id, UUID.class);
            if (kind == Kind.MEDIA_REDRIVE) {
                return new Job(kind, id, lease, "media.assets.v1", rs.getString("event_key"), rs.getString("event_body"));
            }
            var event = new EventEnvelope(id, rs.getString("event_type"), rs.getInt("schema_version"),
                    rs.getObject("aggregate_id", UUID.class), rs.getLong("aggregate_version"), rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("correlation_id", UUID.class), mapper.readTree(rs.getString("payload")));
            return new Job(kind, id, lease, rs.getString("topic"), event.aggregateId().toString(), mapper.writeValueAsString(event));
        }, Timestamp.from(now), Timestamp.from(now), lease, Timestamp.from(now.plusSeconds(60))).stream().findFirst();
    }

    @Transactional
    public boolean acknowledged(Job job) {
        Kind kind = job.kind();
        return jdbc.update("UPDATE " + kind.table + " SET delivery_state = 'SENT', " + kind.sentAt
                + " = ?, lease_id = NULL, lease_until = NULL, last_error_code = NULL, delivery_version = delivery_version + 1 WHERE "
                + kind.id + " = ? AND lease_id = ? AND delivery_state = 'PENDING'", Timestamp.from(clock.instant()), job.id(), job.leaseId()) == 1;
    }

    @Transactional
    public boolean failed(Job job) {
        Kind kind = job.kind();
        return jdbc.update("UPDATE " + kind.table + " SET delivery_state = CASE WHEN attempts >= 10 THEN 'PARKED' ELSE 'PENDING' END, "
                + "next_attempt_at = CAST(? AS timestamptz) + make_interval(secs => LEAST(300, power(2, LEAST(attempts, 9))::integer)), "
                + "last_error_code = 'BROKER_DELIVERY_FAILED', lease_id = NULL, lease_until = NULL, delivery_version = delivery_version + 1 WHERE "
                + kind.id + " = ? AND lease_id = ? AND delivery_state = 'PENDING'", Timestamp.from(clock.instant()), job.id(), job.leaseId()) == 1;
    }
}
