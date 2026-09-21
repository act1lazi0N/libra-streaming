package com.libra.streaming.core.integration.media;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaDeadLetterStore {
    public static final String ORIGINAL_TOPIC = "libra-original-topic";
    public static final String ORIGINAL_PARTITION = "libra-original-partition";
    public static final String ORIGINAL_OFFSET = "libra-original-offset";
    private final JdbcTemplate jdbc;
    private final MediaEventDecoder decoder;
    private final Clock clock;
    public MediaDeadLetterStore(JdbcTemplate jdbc, MediaEventDecoder decoder, Clock clock) {
        this.jdbc = jdbc; this.decoder = decoder; this.clock = clock;
    }
    @Transactional
    public void capture(ConsumerRecord<String, String> record) {
        if (!record.topic().equals("media.assets.v1.DLT")) { throw new IllegalArgumentException("Invalid DLT topic"); }
        String key = record.key(); String body = record.value();
        boolean available = key != null && key.length() <= 128 && key.indexOf('\0') < 0
                && body != null && body.indexOf('\0') < 0 && body.getBytes(StandardCharsets.UTF_8).length <= 262144;
        if (!available) { key = null; body = null; }
        UUID eventId = null;
        if (available) {
            try { eventId = decoder.decode(key, body).eventId(); }
            catch (RuntimeException ignored) { /* Keep bounded poison data private; never expose its parser message. */ }
        }
        Integer partition = null; Long offset = null;
        var topicHeader = record.headers().lastHeader(ORIGINAL_TOPIC);
        var partitionHeader = record.headers().lastHeader(ORIGINAL_PARTITION);
        var offsetHeader = record.headers().lastHeader(ORIGINAL_OFFSET);
        // Consume older DLT records produced by Spring's original recoverer as well.
        if (topicHeader == null) { topicHeader = record.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_TOPIC); }
        if (partitionHeader == null) { partitionHeader = record.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_PARTITION); }
        if (offsetHeader == null) { offsetHeader = record.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_OFFSET); }
        if (topicHeader != null && java.util.Arrays.equals(topicHeader.value(), "media.assets.v1".getBytes(StandardCharsets.UTF_8))
                && partitionHeader != null && partitionHeader.value() != null && partitionHeader.value().length == 4
                && offsetHeader != null && offsetHeader.value() != null && offsetHeader.value().length == 8) {
            int p = ByteBuffer.wrap(partitionHeader.value()).getInt(); long o = ByteBuffer.wrap(offsetHeader.value()).getLong();
            if (p >= 0 && o >= 0) { partition = p; offset = o; }
        }
        String code = !available ? "PAYLOAD_UNAVAILABLE" : eventId == null ? "INVALID_EVENT"
                : partition == null ? "ORIGIN_UNAVAILABLE" : "MEDIA_EVENT_REJECTED";
        jdbc.update("""
                INSERT INTO media_dead_letters(id, dlt_partition, dlt_offset, source_partition, source_offset,
                    event_key, event_body, event_id, payload_available, received_at, created_at, last_error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (dlt_partition, dlt_offset) DO NOTHING
                """, UUID.randomUUID(), record.partition(), record.offset(), partition, offset, key, body, eventId, available,
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), code);
    }
}
