package com.libra.streaming.media.delivery.infrastructure;

import com.libra.streaming.media.delivery.application.DeliverableAssets;
import com.libra.streaming.media.delivery.application.StreamDelivery;
import com.libra.streaming.media.delivery.application.StreamObjects;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** The {@code libra.media.delivery.*} settings reach the delivery service, and inconsistent ones stop startup. */
class StreamDeliveryConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StreamDeliveryConfiguration.class)
            .withBean(DeliverableAssets.class, () -> (id, version) -> Optional.empty())
            .withBean(StreamObjects.class, () -> (key, max) -> Optional.empty())
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void consistentLimitsStartTheServiceAndInconsistentOnesStopStartup() {
        runner.withPropertyValues("libra.media.delivery.max-streams=64",
                        "libra.media.delivery.max-streams-per-session=4", "libra.media.delivery.transfer-timeout=60s")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(StreamDelivery.class);
                    assertThat(context.getBean(StreamDeliveryProperties.class).limits())
                            .isEqualTo(new StreamDelivery.Limits(64, 4, java.time.Duration.ofSeconds(60)));
                });
        for (var bad : new String[][]{{"4", "5", "60s"}, {"0", "0", "60s"}, {"64", "4", "0s"}, {"64", "4", "11m"}}) {
            runner.withPropertyValues("libra.media.delivery.max-streams=" + bad[0],
                            "libra.media.delivery.max-streams-per-session=" + bad[1],
                            "libra.media.delivery.transfer-timeout=" + bad[2])
                    .run(context -> assertThat(context).as(String.join(",", bad)).hasFailed());
        }
        runner.run(context -> assertThat(context).as("no settings at all").hasFailed());
    }
}
