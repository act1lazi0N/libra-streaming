package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.OutboxEvents;
import com.libra.streaming.media.events.application.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "libra.media.outbox.enabled", havingValue = "true")
public class OutboxPublicationConfiguration {
    @Bean
    OutboxRelay.Settings outboxRelaySettings(
            @Value("${libra.media.outbox.poll-interval:1s}") Duration poll,
            @Value("${libra.media.outbox.lease-duration:30s}") Duration lease,
            @Value("${libra.media.outbox.send-timeout:10s}") Duration send,
            @Value("${libra.media.outbox.max-retry-delay:5m}") Duration maxRetry,
            @Value("${libra.media.outbox.shutdown-timeout:10s}") Duration shutdown) {
        return new OutboxRelay.Settings(poll, lease, send, maxRetry, shutdown);
    }

    @Bean(destroyMethod = "close")
    KafkaEventPublisher outboxPublisher(@Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers,
            OutboxRelay.Settings settings) {
        return new KafkaEventPublisher(bootstrapServers, settings.sendTimeout());
    }

    // The relay stops (destroyed first, as it depends on the publisher) before the producer closes.
    @Bean(initMethod = "start", destroyMethod = "close")
    OutboxRelay outboxRelay(OutboxEvents events, KafkaEventPublisher publisher, OutboxRelay.Settings settings,
            MeterRegistry metrics) {
        var log = LoggerFactory.getLogger(OutboxPublicationConfiguration.class);
        return new OutboxRelay(events, publisher, settings, code -> {
            metrics.counter("libra.media.outbox.relay", "outcome", code).increment();
            if (!OutboxRelay.ACKNOWLEDGED.equals(code)) { log.warn("Media outbox: {}", code); }
        });
    }
}
