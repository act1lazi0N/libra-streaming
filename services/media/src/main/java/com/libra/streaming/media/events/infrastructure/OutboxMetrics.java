package com.libra.streaming.media.events.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable delivery state as gauges, read from PostgreSQL when the registry is sampled, so they hold across restarts
 * and whichever process (or none) runs the relay. A failed read is NaN, never a reassuring zero. Labels are fixed
 * values only: no event, asset or error text.
 */
@Component
public class OutboxMetrics {
    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (var state : new String[] {"PENDING", "LEASED"}) {
            Gauge.builder("libra.media.outbox.backlog", this, metrics -> metrics.count(
                    "SELECT count(*) FROM media_outbox_events WHERE delivery_state = '" + state + "'"))
                    .tag("state", state).description("Unsent Media events by delivery state").register(registry);
        }
        for (var failure : new String[] {"BROKER_UNAVAILABLE", "BROKER_REJECTED"}) {
            Gauge.builder("libra.media.outbox.failing", this, metrics -> metrics.count(
                    "SELECT count(*) FROM media_outbox_events WHERE last_error_code = '" + failure + "'"))
                    .tag("failure", failure).description("Unsent Media events whose last delivery failed")
                    .register(registry);
        }
        Gauge.builder("libra.media.outbox.oldest.seconds", this, metrics -> metrics.count("""
                SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(created_at)), 0)
                FROM media_outbox_events WHERE delivery_state <> 'SENT'
                """)).description("Age of the oldest unsent Media event").baseUnit("seconds").register(registry);
    }

    private double count(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (RuntimeException exception) {
            return Double.NaN;
        }
    }
}
