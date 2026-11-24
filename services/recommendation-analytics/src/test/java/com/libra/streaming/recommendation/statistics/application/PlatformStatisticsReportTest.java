package com.libra.streaming.recommendation.statistics.application;

import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.statistics.domain.ContentTotals;
import com.libra.streaming.recommendation.statistics.domain.DayContribution;
import com.libra.streaming.recommendation.statistics.domain.DayTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformStatisticsReportTest {
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final StatisticsWindow WINDOW = new StatisticsWindow(
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8));
    private static final List<DayTotals> DAYS = List.of(new DayTotals(WINDOW.from(), StatisticsTotals.ZERO),
            new DayTotals(WINDOW.to(), StatisticsTotals.ZERO));
    private final List<String> calls = new ArrayList<>();
    private boolean inside;
    private int transactions;

    @Test
    void missingCheckpointNeverReadsAggregates() {
        assertThat(statistics(Map.of()).report(WINDOW, 10)).isEmpty();
        assertThat(calls).containsExactly("begin", "checkpoint", "end");
        assertThat(transactions).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void rejectsOutOfRangeLimitBeforeReading(int limit) {
        assertThatThrownBy(() -> statistics(Map.of()).report(WINDOW, limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
        assertThat(transactions).isZero();
    }

    @Test
    void checkpointThenAllAggregatesAreInsideExactlyOneRead() {
        assertThat(statistics(Map.of(InputTopic.PLAYBACK, NOW.minusSeconds(60))).report(WINDOW, 50))
                .contains(new PlatformStatistics.Report(WINDOW, NOW.minusSeconds(60), StatisticsTotals.ZERO, DAYS, List.of()));
        assertThat(calls).containsExactly("begin", "checkpoint", "totals", "days", "topContent", "end");
        assertThat(transactions).isEqualTo(1);
        assertThat(inside).isFalse();
    }

    @Test
    void reportRejectsBothMissingAndExtraDays() {
        for (List<DayTotals> days : List.of(List.<DayTotals>of(), List.of(DAYS.getFirst()),
                List.of(DAYS.getFirst(), DAYS.getLast(), DAYS.getLast()))) {
            assertThatThrownBy(() -> new PlatformStatistics.Report(WINDOW, NOW, StatisticsTotals.ZERO, days, List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private void record(String call) {
        assertThat(inside).as("%s must execute inside read", call).isTrue();
        calls.add(call);
    }

    private PlatformStatistics statistics(Map<InputTopic, Instant> confirmed) {
        var checkpoints = new CheckpointStore() {
            @Override public Map<InputTopic, Instant> confirmed() { record("checkpoint"); return confirmed; }
            @Override public boolean advance(InputTopic topic, Instant at) { throw new AssertionError("write"); }
        };
        var store = new StatisticsStore() {
            @Override public void add(UUID account, List<DayContribution> changes) { throw new AssertionError("write"); }
            @Override public StatisticsTotals totals(StatisticsWindow window) {
                record("totals"); assertThat(window).isEqualTo(WINDOW); return StatisticsTotals.ZERO;
            }
            @Override public List<DayTotals> days(StatisticsWindow window) {
                record("days"); assertThat(window).isEqualTo(WINDOW); return DAYS;
            }
            @Override public List<ContentTotals> topContent(StatisticsWindow window, int limit) {
                record("topContent"); assertThat(window).isEqualTo(WINDOW); assertThat(limit).isEqualTo(50);
                return List.of();
            }
        };
        var reads = new ConsistentReads() {
            @Override public <T> T read(Supplier<T> work) {
                assertThat(inside).isFalse();
                transactions++;
                calls.add("begin");
                inside = true;
                try { return work.get(); }
                finally { inside = false; calls.add("end"); }
            }
        };
        return new PlatformStatistics(store, new ProjectionFreshness(checkpoints, Clock.fixed(NOW, ZoneOffset.UTC)), reads);
    }
}
