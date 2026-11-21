package com.libra.streaming.recommendation.freshness.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WatermarkTest {
    private static final Instant AT = Instant.parse("2026-09-07T12:00:00Z");

    @Test
    void emptyEndOffsetsAreReachedByAnyPositions() {
        var watermark = new Watermark(AT, Map.of());
        assertThat(watermark.reachedBy(Map.of())).isTrue();
        assertThat(watermark.reachedBy(Map.of(2, 42L))).isTrue();
    }

    @Test
    void zeroEndOffsetNeedsNoPosition() {
        assertThat(new Watermark(AT, Map.of(0, 0L)).reachedBy(Map.of())).isTrue();
    }

    @Test
    void positiveEndOffsetNeedsAPositionForThatPartition() {
        var watermark = new Watermark(AT, Map.of(0, 5L));
        assertThat(watermark.reachedBy(Map.of())).isFalse();
        assertThat(watermark.reachedBy(Map.of(1, 100L))).isFalse();
    }

    @Test
    void positionAtOrBeyondEndReachesIt() {
        var watermark = new Watermark(AT, Map.of(0, 5L));
        assertThat(watermark.reachedBy(Map.of(0, 5L))).isTrue();
        assertThat(watermark.reachedBy(Map.of(0, 6L))).isTrue();
    }

    @Test
    void everyPartitionMustReachItsEnd() {
        var watermark = new Watermark(AT, Map.of(0, 5L, 1, 10L));
        assertThat(watermark.reachedBy(Map.of(0, 5L, 1, 9L))).isFalse();
        assertThat(watermark.reachedBy(Map.of(0, 5L, 1, 10L))).isTrue();
    }

    @Test
    void rejectsNegativeEndOffset() {
        assertThatThrownBy(() -> new Watermark(AT, Map.of(0, -1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesSourceMap() {
        var source = new HashMap<>(Map.of(0, 5L));
        var watermark = new Watermark(AT, source);
        source.put(0, 100L);
        source.put(1, 200L);
        assertThat(watermark.endOffsets()).containsExactlyEntriesOf(Map.of(0, 5L));
        assertThat(watermark.reachedBy(Map.of(0, 5L))).isTrue();
    }
}
