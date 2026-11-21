package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.application.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeadLetterListenerTest {
    static ConsumerRecord<String, String> record(String key, String body) {
        var record = new ConsumerRecord<>("analytics.dead-letters.v1", 2, 8L, key, body);
        record.headers().add(ConsumerConfiguration.ORIGINAL_TOPIC, "core.playback.v1".getBytes(StandardCharsets.UTF_8));
        record.headers().add(ConsumerConfiguration.ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(3).array());
        record.headers().add(ConsumerConfiguration.ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(99).array());
        record.headers().add(ConsumerConfiguration.REASON, "SCHEMA_INVALID".getBytes(StandardCharsets.US_ASCII));
        return record;
    }

    @Test
    void validRecordPreservesOriginAndPayload() {
        var row = DeadLetterListener.map(record("key", "body"));
        assertThat(row.dltPartition()).isEqualTo(2);
        assertThat(row.dltOffset()).isEqualTo(8);
        assertThat(row.sourceTopic()).isEqualTo("core.playback.v1");
        assertThat(row.sourcePartition()).isEqualTo(3);
        assertThat(row.sourceOffset()).isEqualTo(99);
        assertThat(row.key()).isEqualTo("key");
        assertThat(row.body()).isEqualTo("body");
        assertThat(row.reason()).isEqualTo("SCHEMA_INVALID");
        assertThat(row.state()).isEqualTo("HELD");
    }

    @Test
    void payloadValidationClearsBothValues() {
        String nul = String.valueOf((char) 0);
        for (String key : new String[] {null, "x".repeat(129), nul}) {
            assertUnavailable(record(key, "body"));
        }
        for (String body : new String[] {null, nul, "😀".repeat(65537)}) {
            assertUnavailable(record("key", body));
        }
        assertThat(DeadLetterListener.map(record("x".repeat(128), "😀".repeat(65536))).state()).isEqualTo("HELD");
    }

    static void assertUnavailable(ConsumerRecord<String, String> record) {
        var row = DeadLetterListener.map(record);
        assertThat(row.key()).isNull();
        assertThat(row.body()).isNull();
        assertThat(row.reason()).isEqualTo("PAYLOAD_UNAVAILABLE");
        assertThat(row.state()).isEqualTo("UNREDRIVABLE");
    }

    @Test
    void malformedOriginsClearAllSourceFieldsButKeepValidPayload() {
        for (String name : new String[] {ConsumerConfiguration.ORIGINAL_TOPIC, ConsumerConfiguration.ORIGINAL_PARTITION,
                ConsumerConfiguration.ORIGINAL_OFFSET}) {
            for (byte[] invalid : new byte[][] {null, new byte[0], new byte[] {-1}, new byte[9]}) {
                var record = record("key", "body");
                record.headers().remove(name);
                if (invalid != null) { record.headers().add(name, invalid); }
                var row = DeadLetterListener.map(record);
                assertThat(row.sourceTopic()).isNull();
                assertThat(row.sourcePartition()).isNull();
                assertThat(row.sourceOffset()).isNull();
                assertThat(row.key()).isEqualTo("key");
                assertThat(row.body()).isEqualTo("body");
                assertThat(row.reason()).isEqualTo("ORIGIN_UNAVAILABLE");
                assertThat(row.state()).isEqualTo("UNREDRIVABLE");
            }
        }
        var negative = record("key", "body");
        negative.headers().add(ConsumerConfiguration.ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(-1).array());
        assertThat(DeadLetterListener.map(negative).reason()).isEqualTo("ORIGIN_UNAVAILABLE");
        negative = record("key", "body");
        negative.headers().add(ConsumerConfiguration.ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(-1).array());
        assertThat(DeadLetterListener.map(negative).reason()).isEqualTo("ORIGIN_UNAVAILABLE");
    }

    @Test
    void payloadLossTakesPrecedenceOverOriginLoss() {
        var record = record(null, null);
        record.headers().remove(ConsumerConfiguration.ORIGINAL_TOPIC);
        assertUnavailable(record);
        assertThat(DeadLetterListener.map(record).sourceTopic()).isNull();
    }

    @Test
    void reasonsAreAllowlisted() {
        for (String reason : new String[] {"SCHEMA_INVALID", "SEMANTIC_INVALID", "EVENT_CONFLICT", "RETRY_EXHAUSTED",
                "unknown", ""}) {
            var record = record("key", "body");
            record.headers().remove(ConsumerConfiguration.REASON);
            record.headers().add(ConsumerConfiguration.REASON, reason.getBytes(StandardCharsets.US_ASCII));
            assertThat(DeadLetterListener.map(record).reason())
                    .isEqualTo(reason.equals("unknown") || reason.isEmpty() ? "RETRY_EXHAUSTED" : reason);
        }
        var missing = record("key", "body");
        missing.headers().remove(ConsumerConfiguration.REASON);
        assertThat(DeadLetterListener.map(missing).reason()).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    void captureUsesTransactionAndCountsOnlyAfterSuccessAndSanitizesFailure() {
        DeadLetters letters = mock(DeadLetters.class);
        Transactions tx = new Transactions() {
            public <T> T inTransaction(Supplier<T> work) { return work.get(); }
        };
        var metrics = new SimpleMeterRegistry();
        var listener = new DeadLetterListener(letters, tx, metrics);
        var record = record("key", "body");
        listener.receive(record);
        verify(letters).capture(DeadLetterListener.map(record));
        assertThat(metrics.get("libra.analytics.dead-letters.captured").tag("reason", "SCHEMA_INVALID").counter().count())
                .isEqualTo(1);
        doThrow(new RuntimeException("private-marker")).when(letters).capture(any());
        assertThatThrownBy(() -> listener.receive(record)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Dead-letter capture failed").hasNoCause();
        assertThat(metrics.get("libra.analytics.dead-letters.captured").tag("reason", "SCHEMA_INVALID").counter().count())
                .isEqualTo(1);
    }
}

