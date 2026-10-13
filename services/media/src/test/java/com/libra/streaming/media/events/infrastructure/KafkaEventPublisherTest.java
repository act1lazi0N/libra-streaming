package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.EventPublisher;
import com.libra.streaming.media.events.domain.DeliveryFailure;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KafkaEventPublisherTest {
    private final OutboxDelivery delivery = new OutboxDelivery(UUID.randomUUID(), UUID.randomUUID(), 1,
            "media.assets.v1", UUID.randomUUID().toString(), "{\"eventId\":\"x\"}");
    private final List<MockProducer<String, String>> created = new ArrayList<>();

    @Test
    void aProducerThatRefusesEverySendIsReplacedAndTheNextPublishGoesThrough() throws Exception {
        // A closed producer refuses every send, as one in a fatal state does.
        var dead = producer(true);
        dead.close();
        try (var publisher = new KafkaEventPublisher(sequence(dead, producer(true)))) {
            assertFailure(publisher, DeliveryFailure.BROKER_REJECTED);
            publisher.publish(delivery, Duration.ofSeconds(1));
            assertThat(publisher.generation()).isEqualTo(2);
            assertThat(created.get(1).history()).singleElement().satisfies(record -> {
                assertThat(record.topic()).isEqualTo(delivery.topic());
                assertThat(record.key()).isEqualTo(delivery.key());
                assertThat(record.value()).isEqualTo(delivery.value());
                assertThat(record.headers().toArray()).isEmpty();
            });
        }
    }

    @Test
    void anAsynchronousNonRetriableErrorClosesTheProducer() throws Exception {
        var first = producer(false);
        try (var publisher = new KafkaEventPublisher(sequence(first, producer(true)));
                var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> { publisher.publish(delivery, Duration.ofSeconds(10)); return null; });
            awaitSends(first, 1);
            assertThat(first.errorNext(new ClusterAuthorizationException("denied for broker.internal:9092"))).isTrue();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).cause()
                    .isInstanceOf(EventPublisher.Failure.class).hasMessage("BROKER_REJECTED");
            assertThat(first.closed()).isTrue();
            publisher.publish(delivery, Duration.ofSeconds(1));
            assertThat(publisher.generation()).isEqualTo(2);
        }
    }

    @Test
    void retriableErrorsAndTimeoutsKeepTheProducer() throws Exception {
        var only = producer(false);
        try (var publisher = new KafkaEventPublisher(sequence(only));
                var pool = Executors.newSingleThreadExecutor()) {
            // No acknowledgement within the timeout.
            assertFailure(publisher, DeliveryFailure.BROKER_UNAVAILABLE);
            // The timed-out send is settled first: a mock fails its oldest incomplete send.
            assertThat(only.completeNext()).isTrue();
            // A retriable broker error.
            var pending = pool.submit(() -> { publisher.publish(delivery, Duration.ofSeconds(10)); return null; });
            awaitSends(only, 2);
            assertThat(only.errorNext(new NotEnoughReplicasException("isr"))).isTrue();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).cause().hasMessage("BROKER_UNAVAILABLE");
            // A retriable error thrown by send() itself, such as a metadata timeout.
            only.sendException = new TimeoutException("metadata");
            assertFailure(publisher, DeliveryFailure.BROKER_UNAVAILABLE);
            assertThat(only.closed()).isFalse();
            assertThat(publisher.generation()).isEqualTo(1);
        }
    }

    @Test
    void aFailedReplacementIsRetriedOnTheNextPublish() throws Exception {
        var dead = producer(true);
        dead.close();
        var supplies = new ArrayDeque<Supplier<Producer<String, String>>>(List.of(() -> dead,
                () -> { throw new IllegalStateException("cannot build"); }, () -> producer(true)));
        try (var publisher = new KafkaEventPublisher(() -> supplies.poll().get())) {
            assertFailure(publisher, DeliveryFailure.BROKER_REJECTED);
            assertFailure(publisher, DeliveryFailure.BROKER_REJECTED);
            publisher.publish(delivery, Duration.ofSeconds(1));
            assertThat(publisher.generation()).isEqualTo(2);
        }
    }

    @Test
    void aClosedPublisherClosesItsProducerAndRefuses() {
        var only = producer(true);
        var publisher = new KafkaEventPublisher(sequence(only));
        publisher.close();
        assertThat(only.closed()).isTrue();
        assertFailure(publisher, DeliveryFailure.BROKER_REJECTED);
        assertThat(created).hasSize(1);
    }

    private void assertFailure(KafkaEventPublisher publisher, DeliveryFailure reason) {
        // Only the fixed code leaves the publisher: no broker message, address or record.
        assertThatThrownBy(() -> publisher.publish(delivery, Duration.ofMillis(200)))
                .isInstanceOfSatisfying(EventPublisher.Failure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure).hasMessage(reason.name()).hasNoCause();
                });
    }

    private MockProducer<String, String> producer(boolean autoComplete) {
        var producer = new MockProducer<>(autoComplete, null, new StringSerializer(), new StringSerializer());
        created.add(producer);
        return producer;
    }

    @SafeVarargs
    private static Supplier<Producer<String, String>> sequence(Producer<String, String>... producers) {
        var queue = new ArrayDeque<>(List.of(producers));
        return () -> {
            var next = queue.poll();
            if (next == null) { throw new AssertionError("No further producer expected"); }
            return next;
        };
    }

    private static void awaitSends(MockProducer<String, String> producer, int count) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (producer.history().size() < count) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(10);
        }
    }
}
