package com.libra.streaming.recommendation.statistics.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticsTotalsTest {
    @Test
    void rejectsNegativeQualifiedViews() {
        assertThatThrownBy(() -> new StatisticsTotals(-1, 0, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeUniqueViewers() {
        assertThatThrownBy(() -> new StatisticsTotals(1, -1, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeWatchedTime() {
        assertThatThrownBy(() -> new StatisticsTotals(1, 1, -1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMoreViewersThanViews() {
        assertThatThrownBy(() -> new StatisticsTotals(1, 2, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsEqualViewersAndViewsIncludingZero() {
        var totals = new StatisticsTotals(3, 3, 60000);
        assertThat(totals.qualifiedViews()).isEqualTo(3);
        assertThat(totals.uniqueViewers()).isEqualTo(3);
        assertThat(totals.acceptedWatchedMs()).isEqualTo(60000);
        assertThat(new StatisticsTotals(0, 0, 0)).isEqualTo(StatisticsTotals.ZERO);
    }
}
