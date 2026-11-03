package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.OutboxEvents;
import com.libra.streaming.media.events.application.OutboxRelay;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublicationConfigurationTest {
    private final OutboxEvents events = mock(OutboxEvents.class);
    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(OutboxPublicationConfiguration.class)
            .withBean(OutboxEvents.class, () -> events)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                    io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
    // Nothing listens here; the relay never gets an event to send.
    private final ApplicationContextRunner runner = bare.withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1");

    @Test
    void theRelayIsOffUnlessEnabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OutboxRelay.class)
                .doesNotHaveBean(KafkaEventPublisher.class));
        runner.withPropertyValues("libra.media.outbox.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    void anEnabledRelayStartsWithTheDefaultTimingAndPolls() {
        when(events.claim(any())).thenReturn(Optional.empty());
        runner.withPropertyValues("libra.media.outbox.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OutboxRelay.class).hasSingleBean(KafkaEventPublisher.class);
            assertThat(context.getBean(OutboxRelay.Settings.class)).isEqualTo(new OutboxRelay.Settings(
                    Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofMinutes(5),
                    Duration.ofSeconds(10)));
            verify(events, timeout(5000).atLeastOnce()).claim(Duration.ofSeconds(30));
        });
    }

    @Test
    void invalidTimingOrAMissingBrokerFailsAtStartup() {
        runner.withPropertyValues("libra.media.outbox.enabled=true", "libra.media.outbox.lease-duration=15s")
                .run(context -> assertThat(context).getFailure().hasRootCauseMessage("Invalid outbox timing"));
        bare.withPropertyValues("libra.media.outbox.enabled=true").run(context -> assertThat(context).getFailure()
                .hasRootCauseMessage("Kafka bootstrap servers are required"));
    }
}
