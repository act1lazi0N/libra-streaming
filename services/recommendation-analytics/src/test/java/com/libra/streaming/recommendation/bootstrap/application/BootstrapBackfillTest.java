package com.libra.streaming.recommendation.bootstrap.application;

import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BootstrapBackfillTest {
    private static final long GENERATION = 42;
    private static final List<Query> EXPECTED_QUERIES = List.of(
            new Query(GENERATION, BootstrapSource.CATALOG),
            new Query(GENERATION, BootstrapSource.PROFILES),
            new Query(GENERATION, BootstrapSource.PLAYBACK));

    @Test
    void disabledBackfillCompletesAllTopicsWithoutTouchingStore() {
        var store = new RecordingProgress(Map.of());
        var backfill = new BootstrapBackfill(store, false, GENERATION);

        assertCompletionForAllTopics(backfill, true);

        assertThat(store.queries).isEmpty();
    }

    @Test
    void enabledBackfillWithoutRowIsIncompleteForEachConfiguredSource() {
        var store = new RecordingProgress(Map.of());

        assertCompletionForAllTopics(new BootstrapBackfill(store, true, GENERATION), false);

        assertThat(store.queries).containsExactlyElementsOf(EXPECTED_QUERIES);
    }

    @Test
    void enabledBackfillWithUnfinishedRowsIsIncomplete() {
        var store = progressWithCompletion(false);

        assertCompletionForAllTopics(new BootstrapBackfill(store, true, GENERATION), false);

        assertThat(store.queries).containsExactlyElementsOf(EXPECTED_QUERIES);
    }

    @Test
    void enabledBackfillUsesCompletedRowsFromConfiguredGenerationAndMatchingSources() {
        var store = progressWithCompletion(true);

        assertCompletionForAllTopics(new BootstrapBackfill(store, true, GENERATION), true);

        assertThat(store.queries).containsExactlyElementsOf(EXPECTED_QUERIES);
    }

    private static void assertCompletionForAllTopics(BootstrapBackfill backfill, boolean expected) {
        assertThat(backfill.complete(InputTopic.CATALOG)).as("catalog").isEqualTo(expected);
        assertThat(backfill.complete(InputTopic.PROFILES)).as("profiles").isEqualTo(expected);
        assertThat(backfill.complete(InputTopic.PLAYBACK)).as("playback").isEqualTo(expected);
    }

    private static RecordingProgress progressWithCompletion(boolean completed) {
        var position = new BootstrapProgress.Position(null, 0, 0, completed);
        return new RecordingProgress(Map.of(
                new Query(GENERATION, BootstrapSource.CATALOG), position,
                new Query(GENERATION, BootstrapSource.PROFILES), position,
                new Query(GENERATION, BootstrapSource.PLAYBACK), position));
    }

    private record Query(long generation, BootstrapSource source) {}

    private static final class RecordingProgress implements BootstrapProgress {
        private final Map<Query, Position> rows;
        private final List<Query> queries = new ArrayList<>();

        private RecordingProgress(Map<Query, Position> rows) {
            this.rows = rows;
        }

        @Override
        public Position start(long generation, BootstrapSource source, Instant at) {
            throw new AssertionError("Backfill readiness must not start a bootstrap");
        }

        @Override
        public void advance(long generation, BootstrapSource source, String cursor, long applied, long rejected,
                boolean completed, Instant at) {
            throw new AssertionError("Backfill readiness must not advance a bootstrap");
        }

        @Override
        public Optional<Position> find(long generation, BootstrapSource source) {
            var query = new Query(generation, source);
            queries.add(query);
            return Optional.ofNullable(rows.get(query));
        }
    }
}
