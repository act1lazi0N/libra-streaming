package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.EventPublisher;
import com.libra.streaming.media.events.domain.DeliveryFailure;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * A plain idempotent producer: all in-sync replicas acknowledge, broker-side retries cannot reorder or duplicate
 * within one producer session, and every wait is bounded by the send timeout. A plain client also keeps record
 * values out of the logs (Spring's default producer listener logs them on failure).
 *
 * <p>A failure the client calls retriable (a timeout, a broker or network fault) keeps the producer. Any other
 * failure may have left it in a fatal state in which it refuses every later send (an authorization error, an
 * idempotence sequence error, a closed producer), so it is closed and the next publish uses a new one.
 */
public final class KafkaEventPublisher implements EventPublisher, AutoCloseable {
    private final Supplier<Producer<String, String>> producers;
    private final Object lock = new Object();
    private Producer<String, String> producer;
    private int generation;
    private boolean closed;

    public KafkaEventPublisher(String bootstrapServers, Duration sendTimeout) {
        this(factory(bootstrapServers, sendTimeout));
    }

    KafkaEventPublisher(Supplier<Producer<String, String>> producers) {
        this.producers = producers;
        this.producer = producers.get();
        this.generation = 1;
    }

    private static Supplier<Producer<String, String>> factory(String bootstrapServers, Duration sendTimeout) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("Kafka bootstrap servers are required");
        }
        int timeout = Math.toIntExact(sendTimeout.toMillis());
        Map<String, Object> settings = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, "media-outbox",
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.LINGER_MS_CONFIG, 0,
                // delivery.timeout.ms must cover linger.ms + request.timeout.ms.
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout / 2,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout / 2);
        return () -> new KafkaProducer<>(settings, new StringSerializer(), new StringSerializer());
    }

    @Override
    public void publish(OutboxDelivery delivery, Duration timeout) throws InterruptedException {
        var current = current();
        try {
            current.send(new ProducerRecord<>(delivery.topic(), delivery.key(), delivery.value()))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new Failure(DeliveryFailure.BROKER_UNAVAILABLE);
        } catch (ExecutionException exception) {
            // The cause can quote broker addresses; only its class decides the outcome.
            throw classified(current, exception.getCause());
        } catch (InterruptException exception) {
            // Kafka's unchecked form, thrown while send() waits for metadata; it has already restored the flag.
            Thread.interrupted();
            throw new InterruptedException("Publication interrupted");
        } catch (RuntimeException exception) {
            throw classified(current, exception);
        }
    }

    private Failure classified(Producer<String, String> failed, Throwable cause) {
        if (cause instanceof RetriableException) { return new Failure(DeliveryFailure.BROKER_UNAVAILABLE); }
        replace(failed);
        return new Failure(DeliveryFailure.BROKER_REJECTED);
    }

    private Producer<String, String> current() {
        synchronized (lock) {
            if (closed) { throw new Failure(DeliveryFailure.BROKER_REJECTED); }
            if (producer == null) {
                try {
                    producer = producers.get();
                    generation++;
                } catch (RuntimeException exception) {
                    throw new Failure(DeliveryFailure.BROKER_REJECTED);
                }
            }
            return producer;
        }
    }

    /** Drops the producer that failed; a later failure of an already replaced producer changes nothing. */
    private void replace(Producer<String, String> failed) {
        Producer<String, String> stale;
        synchronized (lock) {
            if (producer != failed) { return; }
            stale = producer;
            producer = null;
        }
        closeQuietly(stale, Duration.ZERO);
    }

    /** How many producers this publisher has created; a replacement adds one. */
    int generation() {
        synchronized (lock) { return generation; }
    }

    @Override
    public void close() {
        Producer<String, String> last;
        synchronized (lock) {
            closed = true;
            last = producer;
            producer = null;
        }
        closeQuietly(last, Duration.ofSeconds(5));
    }

    private static void closeQuietly(Producer<String, String> producer, Duration timeout) {
        if (producer == null) { return; }
        try {
            producer.close(timeout);
        } catch (RuntimeException exception) {
            // A producer in a fatal state may fail to close; it is unreachable either way.
        }
    }
}
