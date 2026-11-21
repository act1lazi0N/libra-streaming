package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.CheckpointAdvancer;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static com.libra.streaming.recommendation.freshness.application.CheckpointAdvancer.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CheckpointSchedulerTest {
    private static final String METRIC = "libra.analytics.freshness.ticks";

    @Test
    void eachTickCountsOutcomesWithTopicAndOutcomeTags() {
        var advancer = mock(CheckpointAdvancer.class);
        when(advancer.tick()).thenReturn(Map.of(
                InputTopic.CATALOG, CONFIRMED, InputTopic.PROFILES, BACKFILLING, InputTopic.PLAYBACK, WAITING));
        var registry = new SimpleMeterRegistry();
        try {
            var scheduler = new CheckpointScheduler(advancer, Duration.ofSeconds(5), registry);
            scheduler.tick();
            scheduler.tick();

            assertThat(registry.getMeters()).hasSize(3);
            assertThat(registry.get(METRIC).tags("topic", "core.catalog.v1", "outcome", "confirmed").counter().count())
                    .isEqualTo(2);
            assertThat(registry.get(METRIC).tags("topic", "core.profiles.v1", "outcome", "backfilling").counter().count())
                    .isEqualTo(2);
            assertThat(registry.get(METRIC).tags("topic", "core.playback.v1", "outcome", "waiting").counter().count())
                    .isEqualTo(2);
        } finally {
            registry.close();
        }
    }

    @Test
    void unexpectedFailureDoesNotPropagateOrLeakMessageAndLaterTickStillWorks(CapturedOutput output) {
        String message = "canary-checkpoint-exception-message";
        var advancer = mock(CheckpointAdvancer.class);
        when(advancer.tick()).thenThrow(new IllegalStateException(message))
                .thenReturn(Map.of(InputTopic.PLAYBACK, CONFIRMED));
        var registry = new SimpleMeterRegistry();
        try {
            var scheduler = new CheckpointScheduler(advancer, Duration.ofSeconds(5), registry);
            assertThatCode(scheduler::tick).doesNotThrowAnyException();
            assertThatCode(scheduler::tick).doesNotThrowAnyException();
            assertThat(registry.get(METRIC).tags("topic", "core.playback.v1", "outcome", "confirmed").counter().count())
                    .isEqualTo(1);
        } finally {
            registry.close();
        }
        assertThat(output.getAll()).contains("Checkpoint tick failed unexpectedly (java.lang.IllegalStateException)")
                .doesNotContain(message);
    }

    @Test
    void anErrorDoesNotEscapeEither(CapturedOutput output) {
        // An escaping Throwable would cancel scheduleWithFixedDelay for good, without a log line.
        var advancer = mock(CheckpointAdvancer.class);
        when(advancer.tick()).thenThrow(new NoClassDefFoundError("canary-missing-class"))
                .thenReturn(Map.of(InputTopic.PLAYBACK, CONFIRMED));
        var registry = new SimpleMeterRegistry();
        try {
            var scheduler = new CheckpointScheduler(advancer, Duration.ofSeconds(5), registry);
            assertThatCode(scheduler::tick).doesNotThrowAnyException();
            assertThatCode(scheduler::tick).doesNotThrowAnyException();
            assertThat(registry.get(METRIC).tags("topic", "core.playback.v1", "outcome", "confirmed").counter().count())
                    .isEqualTo(1);
        } finally {
            registry.close();
        }
        assertThat(output.getAll()).contains("Checkpoint tick failed unexpectedly (java.lang.NoClassDefFoundError)")
                .doesNotContain("canary-missing-class");
    }

    @Test
    void repeatedFailuresWarnOnceAndRecoveryLogsInfoOnce(CapturedOutput output) {
        var advancer = mock(CheckpointAdvancer.class);
        when(advancer.tick()).thenReturn(Map.of(InputTopic.PLAYBACK, FAILED), Map.of(InputTopic.PLAYBACK, FAILED),
                Map.of(InputTopic.PLAYBACK, CONFIRMED), Map.of(InputTopic.PLAYBACK, CONFIRMED));
        var registry = new SimpleMeterRegistry();
        try {
            var scheduler = new CheckpointScheduler(advancer, Duration.ofSeconds(5), registry);
            for (int tick = 0; tick < 4; tick++) { scheduler.tick(); }
            assertThat(registry.get(METRIC).tags("topic", "core.playback.v1", "outcome", "failed").counter().count())
                    .isEqualTo(2);
            assertThat(registry.get(METRIC).tags("topic", "core.playback.v1", "outcome", "confirmed").counter().count())
                    .isEqualTo(2);
        } finally {
            registry.close();
        }
        assertThat(output.getAll().lines().filter(line -> line.contains("WARN")
                && line.contains("Checkpoint of core.playback.v1 could not advance"))).hasSize(1);
        assertThat(output.getAll().lines().filter(line -> line.contains("INFO")
                && line.contains("Checkpoint of core.playback.v1 recovered (CONFIRMED)"))).hasSize(1);
    }
}
