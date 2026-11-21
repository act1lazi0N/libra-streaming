package com.libra.streaming.recommendation.freshness.infrastructure;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointPropertiesTest {
    @ParameterizedTest
    @ValueSource(longs = {1000, 60000})
    void acceptsIntervalBoundaries(long millis) {
        Duration interval = Duration.ofMillis(millis);
        assertThat(new CheckpointProperties(true, interval, Duration.ofSeconds(5)).interval()).isEqualTo(interval);
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 61000})
    void rejectsIntervalsOutsideBoundaries(long millis) {
        assertThatThrownBy(() -> new CheckpointProperties(true, Duration.ofMillis(millis), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {500, 30000})
    void acceptsAdminTimeoutBoundaries(long millis) {
        Duration timeout = Duration.ofMillis(millis);
        assertThat(new CheckpointProperties(true, Duration.ofSeconds(5), timeout).adminTimeout()).isEqualTo(timeout);
    }

    @ParameterizedTest
    @ValueSource(longs = {499, 31000})
    void rejectsAdminTimeoutsOutsideBoundaries(long millis) {
        assertThatThrownBy(() -> new CheckpointProperties(true, Duration.ofSeconds(5), Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
