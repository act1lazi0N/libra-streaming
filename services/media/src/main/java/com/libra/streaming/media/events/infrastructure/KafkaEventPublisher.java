package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.EventPublisher;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * A plain idempotent producer: all in-sync replicas acknowledge, broker-side retries cannot reorder or duplicate
 * within one producer session, and every wait is bounded by the send timeout. A plain client also keeps record
 * values out of the logs (Spring's default producer listener logs them on failure).
 */
public final class KafkaEventPublisher implements EventPublisher, AutoCloseable {
    private final KafkaProducer<String, String> producer;

    public KafkaEventPublisher(String bootstrapServers, Duration sendTimeout) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("Kafka bootstrap servers are required");
        }
        int timeout = Math.toIntExact(sendTimeout.toMillis());
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, "media-outbox",
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.LINGER_MS_CONFIG, 0,
                // delivery.timeout.ms must cover linger.ms + request.timeout.ms.
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout / 2,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout / 2),
                new StringSerializer(), new StringSerializer());
    }

    @Override
    public void publish(OutboxDelivery delivery, Duration timeout) throws InterruptedException {
        try {
            producer.send(new ProducerRecord<>(delivery.topic(), delivery.key(), delivery.value()))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            // The cause can quote broker addresses; the relay only needs to know the record was not acknowledged.
            throw new IllegalStateException("Broker did not acknowledge the event");
        } catch (org.apache.kafka.common.errors.InterruptException exception) {
            // Kafka's unchecked form, thrown while send() waits for metadata; it has already restored the flag.
            Thread.interrupted();
            throw new InterruptedException("Publication interrupted");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Broker did not accept the event");
        }
    }

    @Override
    public void close() { producer.close(Duration.ofSeconds(5)); }
}
