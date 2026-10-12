package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.OutboxEvents;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
// Short transactions judged by database time; the broker call never runs inside one.
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class JdbcOutboxEvents implements OutboxEvents {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public JdbcOutboxEvents(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<OutboxDelivery> claim(Duration leaseDuration) {
        UUID token = UUID.randomUUID();
        // An event waits until every earlier version of its asset is SENT: a leased or failing predecessor holds it.
        return jdbc.query("""
                WITH candidate AS (
                    SELECT e.event_id FROM media_outbox_events e
                    WHERE ((e.delivery_state = 'PENDING' AND e.next_attempt_at <= clock_timestamp())
                            OR (e.delivery_state = 'LEASED' AND e.lease_until <= clock_timestamp()))
                        AND NOT EXISTS (SELECT 1 FROM media_outbox_events older
                            WHERE older.asset_id = e.asset_id AND older.aggregate_version < e.aggregate_version
                                AND older.delivery_state <> 'SENT')
                    ORDER BY e.created_at, e.event_id
                    FOR UPDATE OF e SKIP LOCKED
                    LIMIT 1)
                UPDATE media_outbox_events t SET delivery_state = 'LEASED', lease_token = ?,
                    lease_until = clock_timestamp() + ? * INTERVAL '1 millisecond', attempt_count = t.attempt_count + 1
                FROM candidate c WHERE t.event_id = c.event_id
                RETURNING t.event_id, t.asset_id, t.aggregate_version, t.topic, t.event_type, t.schema_version,
                    t.correlation_id, t.payload::text AS payload, t.created_at, t.attempt_count
                """, (rs, row) -> delivery(rs, token), token, leaseDuration.toMillis()).stream().findFirst();
    }

    @Override
    public boolean sent(OutboxDelivery delivery) {
        // No expiry check: the broker acknowledged this record, so SENT is true even after the lease ran out,
        // unless another relay leased the event since (its token replaced ours).
        return jdbc.update("""
                UPDATE media_outbox_events SET delivery_state = 'SENT', sent_at = clock_timestamp(),
                    lease_token = NULL, lease_until = NULL
                WHERE event_id = ? AND lease_token = ? AND delivery_state = 'LEASED'
                """, delivery.eventId(), delivery.leaseToken()) == 1;
    }

    @Override
    public boolean retry(OutboxDelivery delivery, Duration maxDelay) {
        return jdbc.update("""
                UPDATE media_outbox_events SET delivery_state = 'PENDING', lease_token = NULL, lease_until = NULL,
                    next_attempt_at = clock_timestamp() + LEAST(?, 1000 * power(2, LEAST(attempt_count - 1, 30)))
                        * INTERVAL '1 millisecond'
                WHERE event_id = ? AND lease_token = ? AND delivery_state = 'LEASED'
                """, maxDelay.toMillis(), delivery.eventId(), delivery.leaseToken()) == 1;
    }

    @Override
    public boolean release(OutboxDelivery delivery) {
        return jdbc.update("""
                UPDATE media_outbox_events SET delivery_state = 'PENDING', lease_token = NULL, lease_until = NULL,
                    next_attempt_at = clock_timestamp()
                WHERE event_id = ? AND lease_token = ? AND delivery_state = 'LEASED'
                """, delivery.eventId(), delivery.leaseToken()) == 1;
    }

    /** The row holds the payload; the envelope is rebuilt from its columns in the contract's field order. */
    private static OutboxDelivery delivery(ResultSet rs, UUID token) throws SQLException {
        var assetId = rs.getObject("asset_id", UUID.class);
        var envelope = MAPPER.createObjectNode()
                .put("eventId", rs.getObject("event_id", UUID.class).toString())
                .put("eventType", rs.getString("event_type"))
                .put("schemaVersion", rs.getInt("schema_version"))
                .put("aggregateId", assetId.toString())
                .put("aggregateVersion", rs.getLong("aggregate_version"))
                .put("occurredAt", rs.getTimestamp("created_at").toInstant().toString())
                .put("correlationId", rs.getObject("correlation_id", UUID.class).toString());
        envelope.set("payload", MAPPER.readTree(rs.getString("payload")));
        return new OutboxDelivery(rs.getObject("event_id", UUID.class), token, rs.getInt("attempt_count"),
                rs.getString("topic"), assetId.toString(), MAPPER.writeValueAsString(envelope));
    }
}
