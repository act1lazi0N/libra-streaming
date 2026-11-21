package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer error policy (docs/analytics-dead-letters-ra02-3.md). The recoverer publishes a parked record to the
 * Analytics dead-letter topic and waits for the broker's acknowledgement. If that publication fails, the recoverer
 * throws: the error handler then seeks back to the record, the offset is not committed, and the record is tried
 * again. A record is therefore never skipped without being durably parked.
 */
@Configuration(proxyBeanMethods = false)
public class ConsumerConfiguration {
    public static final String DEAD_LETTER_TOPIC = "analytics.dead-letters.v1";
    public static final String ORIGINAL_TOPIC = "libra-original-topic";
    public static final String ORIGINAL_PARTITION = "libra-original-partition";
    public static final String ORIGINAL_OFFSET = "libra-original-offset";
    public static final String REASON = "libra-dead-letter-reason";
    /** Attempts of an unclassified failure before the record is parked as RETRY_EXHAUSTED: 1 + 2 retries. */
    static final long UNCLASSIFIED_RETRIES = 2;
    static final long TRANSIENT_MAX_INTERVAL_MS = 30_000;
    /**
     * Longer than the producer's {@code delivery.timeout.ms} (30 s): giving up earlier would retry a send that may
     * still succeed and park the record twice. The capture also deduplicates by source position.
     */
    static final long DEAD_LETTER_SEND_TIMEOUT_SECONDS = 35;

    @Bean
    ProducerListener<Object, Object> analyticsProducerListener(MeterRegistry metrics) {
        return new ProducerListener<>() {
            @Override
            public void onError(ProducerRecord<Object, Object> record, RecordMetadata metadata, Exception exception) {
                // The default listener logs the record value, which may be a parked payload.
                metrics.counter("libra.analytics.kafka.producer.errors").increment();
            }
        };
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> analyticsListenerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> producer, MeterRegistry metrics) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var handler = new DefaultErrorHandler(deadLetterPublisher(producer, metrics),
                new FixedBackOff(1000L, UNCLASSIFIED_RETRIES));
        handler.setBackOffFunction((record, failure) -> backOffFor(failure));
        handler.setRetryListeners((record, failure, attempt) -> metrics.counter("libra.analytics.retries",
                "topic", record.topic(), "kind", kind(failure)).increment());
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> analyticsDeadLetterFactory(
            ConsumerFactory<String, String> consumers) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Capture must never be skipped: it is the only durable copy once the source offset moved on.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    static BackOff backOffFor(Exception failure) {
        if (Failures.rejection(failure).isPresent()) { return new FixedBackOff(0L, 0L); }
        if (Failures.isTransient(failure)) {
            var backOff = new ExponentialBackOff(1000L, 2.0);
            backOff.setMaxInterval(TRANSIENT_MAX_INTERVAL_MS);
            return backOff;
        }
        return new FixedBackOff(1000L, UNCLASSIFIED_RETRIES);
    }

    static String kind(Exception failure) {
        return Failures.rejection(failure).isPresent() ? "rejected" : Failures.isTransient(failure) ? "transient" : "other";
    }

    static ConsumerRecordRecoverer deadLetterPublisher(KafkaTemplate<String, String> producer, MeterRegistry metrics) {
        return (record, failure) -> {
            String reason = Failures.rejection(failure).map(EventRejection::reason).map(Enum::name)
                    .orElse("RETRY_EXHAUSTED");
            // A new record: no untrusted source headers and no exception text or stack trace travel with it.
            var dead = new ProducerRecord<String, String>(DEAD_LETTER_TOPIC, null, (String) record.key(),
                    (String) record.value());
            dead.headers().add(ORIGINAL_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8));
            dead.headers().add(ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(record.partition()).array());
            dead.headers().add(ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(record.offset()).array());
            dead.headers().add(REASON, reason.getBytes(StandardCharsets.US_ASCII));
            try { producer.send(dead).get(DEAD_LETTER_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Dead-letter publication interrupted");
            } catch (Exception notAcknowledged) {
                metrics.counter("libra.analytics.dead-letters.publish-failures", "topic", record.topic()).increment();
                throw new IllegalStateException("Dead-letter publication failed");
            }
            metrics.counter("libra.analytics.dead-letters.published", "topic", record.topic(), "reason", reason)
                    .increment();
        };
    }

}
