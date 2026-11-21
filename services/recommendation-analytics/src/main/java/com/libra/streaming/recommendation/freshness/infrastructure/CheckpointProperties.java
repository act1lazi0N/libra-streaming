package com.libra.streaming.recommendation.freshness.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background confirmation of input checkpoints (RA04.2).
 *
 * @param enabled off only where nothing consumes the topics (tests, or an instance with its consumers disabled)
 * @param interval pause between two ticks; Core shows an answer as stale after 15 minutes
 * @param adminTimeout budget of each Kafka admin request; a slower broker fails the tick, it is never waited for
 */
@ConfigurationProperties("libra.analytics.freshness.checkpoints")
public record CheckpointProperties(boolean enabled, @DefaultValue("5s") Duration interval,
        @DefaultValue("5s") Duration adminTimeout) {
    public CheckpointProperties {
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Checkpoint interval must be between 1 second and 1 minute");
        }
        if (adminTimeout.compareTo(Duration.ofMillis(500)) < 0 || adminTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Checkpoint admin timeout must be between 500 ms and 30 seconds");
        }
    }
}
