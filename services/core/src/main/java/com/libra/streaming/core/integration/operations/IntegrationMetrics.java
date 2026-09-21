package com.libra.streaming.core.integration.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IntegrationMetrics {
    private final JdbcTemplate jdbc;
    public IntegrationMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String queue : java.util.List.of("OUTBOX", "MEDIA_DLT")) {
            String table = queue.equals("OUTBOX") ? "outbox_events" : "media_dead_letters";
            for (String state : java.util.List.of("PENDING", "PARKED", "HELD")) {
                Gauge.builder("libra.integration.backlog", this, ignored -> number(
                        "SELECT count(*) FROM " + table + " WHERE delivery_state = ?", state))
                        .tag("queue", queue).tag("state", state).register(registry);
            }
            Gauge.builder("libra.integration.oldest.seconds", this, ignored -> number(
                    "SELECT COALESCE(greatest(0, extract(epoch FROM (CURRENT_TIMESTAMP - min(created_at)))), 0) FROM "
                    + table + " WHERE delivery_state <> 'SENT'", null)).tag("queue", queue).register(registry);
        }
    }
    private double number(String sql, String state) {
        try {
            Number value = state == null ? jdbc.queryForObject(sql, Number.class) : jdbc.queryForObject(sql, Number.class, state);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (org.springframework.dao.DataAccessException exception) { return Double.NaN; }
    }
}
