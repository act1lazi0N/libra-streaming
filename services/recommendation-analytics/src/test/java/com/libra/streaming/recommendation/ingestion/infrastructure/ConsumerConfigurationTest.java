package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerConfigurationTest {
    @Test
    void rejectionDoesNotRetryAndOtherFailuresRetryTwice() {
        assertThat(ConsumerConfiguration.backOffFor(EventRejection.schema("EMPTY_RECORD")).start().nextBackOff())
                .isEqualTo(BackOffExecution.STOP);
        var other = ConsumerConfiguration.backOffFor(new RuntimeException()).start();
        assertThat(other.nextBackOff()).isEqualTo(1000);
        assertThat(other.nextBackOff()).isEqualTo(1000);
        assertThat(other.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void transientBackoffIsExponentialCappedAndUnlimited() {
        var policy = (ExponentialBackOff) ConsumerConfiguration.backOffFor(new Failures.TransientIntakeException());
        assertThat(policy.getMaxElapsedTime()).isEqualTo(Long.MAX_VALUE);
        var execution = policy.start();
        for (long expected : new long[] {1000, 2000, 4000, 8000, 16000, 30000}) {
            assertThat(execution.nextBackOff()).isEqualTo(expected);
        }
        for (int i = 0; i < 100; i++) { assertThat(execution.nextBackOff()).isEqualTo(30000); }
    }

    @SuppressWarnings("unchecked")
    @Test
    void publisherUsesOnlyTypedOriginHeadersAndReason() {
        KafkaTemplate<String, String> producer = mock(KafkaTemplate.class);
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var record = new ConsumerRecord<>("core.playback.v1", 2, 17L, "key", "body");
        record.headers().add("untrusted", new byte[] {1});
        ConsumerConfiguration.deadLetterPublisher(producer, new SimpleMeterRegistry())
                .accept(record, new RuntimeException(EventRejection.conflict("EVENT_ID_REUSED")));
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(sent.capture());
        var dead = sent.getValue();
        assertThat(dead.topic()).isEqualTo("analytics.dead-letters.v1");
        assertThat(dead.key()).isEqualTo("key");
        assertThat(dead.value()).isEqualTo("body");
        assertThat(dead.headers().toArray()).hasSize(4);
        assertThat(dead.headers().lastHeader(ConsumerConfiguration.ORIGINAL_TOPIC).value())
                .isEqualTo("core.playback.v1".getBytes(StandardCharsets.UTF_8));
        byte[] partition = dead.headers().lastHeader(ConsumerConfiguration.ORIGINAL_PARTITION).value();
        byte[] offset = dead.headers().lastHeader(ConsumerConfiguration.ORIGINAL_OFFSET).value();
        assertThat(partition).hasSize(4);
        assertThat(offset).hasSize(8);
        assertThat(ByteBuffer.wrap(partition).getInt()).isEqualTo(2);
        assertThat(ByteBuffer.wrap(offset).getLong()).isEqualTo(17L);
        assertThat(dead.headers().lastHeader(ConsumerConfiguration.REASON).value())
                .isEqualTo("EVENT_CONFLICT".getBytes(StandardCharsets.US_ASCII));
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedSendThrows() {
        KafkaTemplate<String, String> producer = mock(KafkaTemplate.class);
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("private-marker")));
        assertThatThrownBy(() -> ConsumerConfiguration.deadLetterPublisher(producer, new SimpleMeterRegistry())
                .accept(new ConsumerRecord<>("core.playback.v1", 0, 0L, "key", "body"), new RuntimeException()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Dead-letter publication failed").hasNoCause();
    }

    @SuppressWarnings("unchecked")
    @Test
    void timedOutSendThrowsAndUnknownReasonIsRetryExhausted() throws Exception {
        KafkaTemplate<String, String> producer = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        when(future.get(ConsumerConfiguration.DEAD_LETTER_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("private-marker"));
        assertThatThrownBy(() -> ConsumerConfiguration.deadLetterPublisher(producer, new SimpleMeterRegistry())
                .accept(new ConsumerRecord<>("core.playback.v1", 0, 0L, "key", "body"), new RuntimeException()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Dead-letter publication failed").hasNoCause();
        verify(future).get(ConsumerConfiguration.DEAD_LETTER_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(sent.capture());
        assertThat(sent.getValue().headers().lastHeader(ConsumerConfiguration.REASON).value())
                .isEqualTo("RETRY_EXHAUSTED".getBytes(StandardCharsets.US_ASCII));
    }
}

