package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.DeadLetters;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.ingestion.domain.DeadLetter.Captured;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterListener {
    private final DeadLetters letters;
    private final Transactions transactions;
    private final MeterRegistry metrics;

    public DeadLetterListener(DeadLetters letters, Transactions transactions, MeterRegistry metrics) {
        this.letters = letters;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @KafkaListener(id = "analytics-dead-letters", topics = ConsumerConfiguration.DEAD_LETTER_TOPIC,
            groupId = "analytics-dead-letters-v1", containerFactory = "analyticsDeadLetterFactory",
            autoStartup = "${libra.analytics.consumers.enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        try {
            Captured captured = map(record);
            transactions.inTransaction(() -> { letters.capture(captured); return null; });
            metrics.counter("libra.analytics.dead-letters.captured", "reason", captured.reason()).increment();
        } catch (Exception failure) { throw new IllegalStateException("Dead-letter capture failed"); }
    }

    static Captured map(ConsumerRecord<String, String> record) {
        String key = record.key();
        String body = record.value();
        boolean payloadValid = key != null && key.length() <= 128 && key.indexOf(0) < 0
                && body != null && body.indexOf(0) < 0 && body.length() <= 262144
                && body.getBytes(StandardCharsets.UTF_8).length <= 262144;
        String topic = null;
        Integer partition = null;
        Long offset = null;
        try {
            topic = InputTopic.fromWireName(new String(header(record, ConsumerConfiguration.ORIGINAL_TOPIC),
                    StandardCharsets.UTF_8)).wireName();
            byte[] partitionBytes = header(record, ConsumerConfiguration.ORIGINAL_PARTITION);
            byte[] offsetBytes = header(record, ConsumerConfiguration.ORIGINAL_OFFSET);
            if (partitionBytes.length != 4 || offsetBytes.length != 8) { throw new IllegalArgumentException(); }
            partition = ByteBuffer.wrap(partitionBytes).getInt();
            offset = ByteBuffer.wrap(offsetBytes).getLong();
            if (partition < 0 || offset < 0) { throw new IllegalArgumentException(); }
        } catch (RuntimeException invalid) { topic = null; partition = null; offset = null; }
        byte[] reasonBytes = header(record, ConsumerConfiguration.REASON);
        String reason = reasonBytes == null ? "RETRY_EXHAUSTED" : new String(reasonBytes, StandardCharsets.US_ASCII);
        if (!Set.of("SCHEMA_INVALID", "SEMANTIC_INVALID", "EVENT_CONFLICT", "RETRY_EXHAUSTED").contains(reason)) {
            reason = "RETRY_EXHAUSTED";
        }
        // Payload loss takes precedence over origin loss; invalid origin is cleared independently.
        if (!payloadValid) { key = null; body = null; reason = "PAYLOAD_UNAVAILABLE"; }
        else if (topic == null) { reason = "ORIGIN_UNAVAILABLE"; }
        return new Captured(record.partition(), record.offset(), topic, partition, offset, key, body, reason,
                payloadValid && topic != null ? "HELD" : "UNREDRIVABLE");
    }

    private static byte[] header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : header.value();
    }
}
