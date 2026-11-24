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
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlatformStatisticsTest {
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final StatisticsWindow WINDOW = new StatisticsWindow(
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8));
    private static final StatisticsTotals TOTALS = new StatisticsTotals(3, 2, 90000);

    @Test
    void recordPassesAccountAndComputedContributionsToStore() {
        var calls = new ArrayList<String>();
        var store = new RecordingStatisticsStore(calls);
        var statistics = statistics(store, calls, Map.of());
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant before = Instant.parse("2026-09-08T00:01:00Z");
        Instant after = Instant.parse("2026-09-07T23:59:00Z");

        statistics.record(account, NOW, 1500, before, after);

        assertThat(store.writes).containsExactly(new Write(account, DayContribution.of(NOW, 1500, before, after)));
        assertThat(calls).containsExactly("add");
    }

    @Test
    void emptyContributionsCallNeitherStoreNorFreshness() {
        var calls = new ArrayList<String>();
        var store = new RecordingStatisticsStore(calls);
        var statistics = statistics(store, calls, Map.of());
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");

        statistics.record(account, NOW, 0, null, null);
        statistics.record(account, NOW, 0, NOW, NOW);

        assertThat(store.writes).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void missingPlaybackCheckpointReturnsEmptyWithoutReadingTotals() {
        var calls = new ArrayList<String>();
        var store = new RecordingStatisticsStore(calls);
        var statistics = statistics(store, calls,
                Map.of(InputTopic.CATALOG, NOW.minusSeconds(30), InputTopic.PROFILES, NOW.minusSeconds(30)));

        assertThat(statistics.snapshot(WINDOW)).isEmpty();
        assertThat(store.readWindows).isEmpty();
        assertThat(calls).containsExactly("freshness");
    }

    @Test
    void snapshotUsesPlaybackCheckpointAndReadsFreshnessBeforeTotals() {
        var calls = new ArrayList<String>();
        var store = new RecordingStatisticsStore(calls);
        Instant checkpoint = NOW.minusSeconds(30);
        var statistics = statistics(store, calls,
                Map.of(InputTopic.PLAYBACK, checkpoint, InputTopic.CATALOG, NOW.minusSeconds(600)));

        assertThat(statistics.snapshot(WINDOW))
                .contains(new PlatformStatistics.Snapshot(WINDOW, checkpoint, TOTALS));
        assertThat(store.readWindows).containsExactly(WINDOW);
        assertThat(calls).containsExactly("freshness", "totals");
    }

    private static PlatformStatistics statistics(RecordingStatisticsStore store, List<String> calls,
            Map<InputTopic, Instant> checkpoints) {
        var freshness = new ProjectionFreshness(new RecordingCheckpoints(calls, checkpoints),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new PlatformStatistics(store, freshness);
    }

    private record Write(UUID account, List<DayContribution> contributions) {}

    private record RecordingCheckpoints(List<String> calls, Map<InputTopic, Instant> checkpoints)
            implements CheckpointStore {
        @Override
        public Map<InputTopic, Instant> confirmed() {
            calls.add("freshness");
            return checkpoints;
        }

        @Override
        public boolean advance(InputTopic topic, Instant at) {
            throw new AssertionError("Statistics must not advance checkpoints");
        }
    }

    private static final class RecordingStatisticsStore implements StatisticsStore {
        private final List<String> calls;
        private final List<Write> writes = new ArrayList<>();
        private final List<StatisticsWindow> readWindows = new ArrayList<>();

        private RecordingStatisticsStore(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void add(UUID accountId, List<DayContribution> contributions) {
            calls.add("add");
            writes.add(new Write(accountId, List.copyOf(contributions)));
        }

        @Override
        public StatisticsTotals totals(StatisticsWindow window) {
            calls.add("totals");
            readWindows.add(window);
            return TOTALS;
        }

        @Override
        public List<DayTotals> days(StatisticsWindow window) {
            throw new AssertionError("These operations must not read daily totals");
        }

        @Override
        public List<ContentTotals> topContent(StatisticsWindow window, int limit) {
            throw new AssertionError("These operations must not read top content");
        }
    }
}
