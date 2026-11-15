package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCheckpointStore implements CheckpointStore {
    private final JdbcTemplate jdbc;

    JdbcCheckpointStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Map<InputTopic, Instant> confirmed() {
        Map<InputTopic, Instant> confirmed = new EnumMap<>(InputTopic.class);
        jdbc.query("SELECT topic, confirmed_at FROM input_checkpoints WHERE confirmed_at IS NOT NULL", row -> {
            confirmed.put(InputTopic.fromWireName(row.getString("topic")),
                    row.getObject("confirmed_at", OffsetDateTime.class).toInstant());
        });
        return confirmed;
    }

    @Override
    public boolean advance(InputTopic topic, Instant at) {
        // PostgreSQL keeps microseconds; truncating here keeps the stored and the compared value identical.
        OffsetDateTime value = at.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        return jdbc.update("UPDATE input_checkpoints SET confirmed_at = ? "
                + "WHERE topic = ? AND (confirmed_at IS NULL OR confirmed_at < ?)", value, topic.wireName(), value) == 1;
    }
}
