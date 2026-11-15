package com.libra.streaming.recommendation.freshness.application;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionFreshnessTest {
    static final Instant NOW = Instant.parse("2026-11-11T00:10:00Z");
    final MemoryStore store = new MemoryStore();
    final ProjectionFreshness freshness = new ProjectionFreshness(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void statisticsNeedOnlyPlaybackAndRecommendationsNeedEveryTopic() {
        assertThat(freshness.updatedAt(Projection.STATISTICS)).isEmpty();
        freshness.confirm(InputTopic.PLAYBACK, NOW.minusSeconds(60));
        assertThat(freshness.updatedAt(Projection.STATISTICS)).contains(NOW.minusSeconds(60));
        assertThat(freshness.updatedAt(Projection.RECOMMENDATIONS)).isEmpty();
        freshness.confirm(InputTopic.CATALOG, NOW.minusSeconds(600));
        assertThat(freshness.updatedAt(Projection.RECOMMENDATIONS)).isEmpty();
        freshness.confirm(InputTopic.PROFILES, NOW.minusSeconds(30));
        assertThat(freshness.updatedAt(Projection.RECOMMENDATIONS)).contains(NOW.minusSeconds(600));
    }

    @Test
    void readingNeverMovesACheckpoint() {
        freshness.confirm(InputTopic.PLAYBACK, NOW.minusSeconds(900));
        for (int i = 0; i < 3; i++) {
            assertThat(freshness.updatedAt(Projection.STATISTICS)).contains(NOW.minusSeconds(900));
        }
        assertThat(store.advances).isEqualTo(1);
        assertThat(store.values).containsExactly(Map.entry(InputTopic.PLAYBACK, NOW.minusSeconds(900)));
    }

    @Test
    void aFutureCheckpointIsRefusedBeforeTheStore() {
        assertThatThrownBy(() -> freshness.confirm(InputTopic.PLAYBACK, NOW.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.advances).isZero();
        assertThat(freshness.confirm(InputTopic.PLAYBACK, NOW)).isTrue();
        assertThat(freshness.confirm(InputTopic.PLAYBACK, NOW.minusSeconds(1))).isFalse();
        assertThat(freshness.updatedAt(Projection.STATISTICS)).contains(NOW.minusSeconds(5));
    }

    static class MemoryStore implements CheckpointStore {
        final Map<InputTopic, Instant> values = new EnumMap<>(InputTopic.class);
        int advances;

        @Override public Map<InputTopic, Instant> confirmed() { return Map.copyOf(values); }

        @Override public boolean advance(InputTopic topic, Instant at) {
            advances++;
            Instant current = values.get(topic);
            if (current != null && !current.isBefore(at)) { return false; }
            values.put(topic, at);
            return true;
        }
    }
}
