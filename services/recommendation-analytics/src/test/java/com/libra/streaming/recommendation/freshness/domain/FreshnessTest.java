package com.libra.streaming.recommendation.freshness.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreshnessTest {
    static final Instant NOW = Instant.parse("2026-11-11T00:00:10Z");

    @Test
    void anyUnconfirmedInputMeansNotReady() {
        assertThat(Freshness.updatedAt(List.of(Optional.empty()), NOW)).isEmpty();
        assertThat(Freshness.updatedAt(List.of(Optional.of(NOW.minusSeconds(60)), Optional.empty(),
                Optional.of(NOW.minusSeconds(30))), NOW)).isEmpty();
    }

    @Test
    void reportsTheOldestCheckpoint() {
        Instant oldest = NOW.minusSeconds(900);
        assertThat(Freshness.updatedAt(List.of(Optional.of(NOW.minusSeconds(60)), Optional.of(oldest),
                Optional.of(NOW.minusSeconds(30))), NOW)).contains(oldest);
    }

    @Test
    void neverReportsLaterThanTheClockMinusTheSkewMargin() {
        Instant cap = NOW.minusSeconds(5);
        assertThat(Freshness.updatedAt(List.of(Optional.of(NOW)), NOW)).contains(cap);
        assertThat(Freshness.updatedAt(List.of(Optional.of(NOW.plusSeconds(3600))), NOW)).contains(cap);
        assertThat(Freshness.updatedAt(List.of(Optional.of(cap.plusNanos(1))), NOW)).contains(cap);
        assertThat(Freshness.updatedAt(List.of(Optional.of(cap)), NOW)).contains(cap);
        assertThat(Freshness.updatedAt(List.of(Optional.of(cap.minusNanos(1))), NOW)).contains(cap.minusNanos(1));
    }

    @Test
    void anAnswerWithoutInputsIsAProgrammingError() {
        assertThatThrownBy(() -> Freshness.updatedAt(List.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectionsDependOnTheTopicsTheContractNames() {
        assertThat(Projection.STATISTICS.inputs()).containsExactly(InputTopic.PLAYBACK);
        assertThat(Projection.RECOMMENDATIONS.inputs())
                .containsExactlyInAnyOrder(InputTopic.CATALOG, InputTopic.PROFILES, InputTopic.PLAYBACK);
        assertThat(InputTopic.values()).extracting(InputTopic::wireName)
                .containsExactly("core.catalog.v1", "core.profiles.v1", "core.playback.v1");
        for (InputTopic topic : InputTopic.values()) {
            assertThat(InputTopic.fromWireName(topic.wireName())).isEqualTo(topic);
        }
        assertThatThrownBy(() -> InputTopic.fromWireName("media.assets.v1")).isInstanceOf(IllegalArgumentException.class);
    }
}
