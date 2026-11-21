package com.libra.streaming.recommendation.preferences.domain;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GenreInterestsTest {
    private static final long HOUR = 3_600_000L;
    private static final UUID TITLE = new UUID(0, 1);

    @Test
    void rewatchCountsAtMostOneDuration() {
        assertThat(interests(item(TITLE, HOUR, 3 * HOUR, "Drama")).weights())
                .containsOnly(entry("Drama", (double) HOUR));
    }

    @Test
    void unknownAndLongDurationsHaveSeparateCaps() {
        assertThat(interests(item(TITLE, null, 10 * HOUR, "Drama")).weights())
                .containsOnly(entry("Drama", 2.0 * HOUR));
        assertThat(interests(item(TITLE, 8 * HOUR, 10 * HOUR, "Drama")).weights())
                .containsOnly(entry("Drama", 4.0 * HOUR));
    }

    @Test
    void episodesAddUpButOneSeriesCountsAtMostSixHours() {
        assertThat(interests(item(TITLE, 4 * HOUR, 4 * HOUR, "Drama"),
                item(TITLE, 4 * HOUR, 4 * HOUR, "Drama")).weights())
                .containsOnly(entry("Drama", 6.0 * HOUR));
        assertThat(interests(item(TITLE, HOUR, HOUR, "Drama"), item(TITLE, HOUR, HOUR, "Drama")).weights())
                .containsOnly(entry("Drama", 2.0 * HOUR));
    }

    @Test
    void weightIsSplitOverDistinctGenresAndSharesSumToOne() {
        var result = interests(item(TITLE, HOUR, HOUR, "Drama", "Comedy", "Drama"),
                item(new UUID(0, 2), HOUR, HOUR, "Action"));
        assertThat(result.weights()).containsOnly(entry("Drama", HOUR / 2.0),
                entry("Comedy", HOUR / 2.0), entry("Action", (double) HOUR));
        assertThat(result.weights().keySet().stream().mapToDouble(result::share).sum())
                .isCloseTo(1.0, within(1e-12));
        assertThat(result.share("Unwatched")).isZero();
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void unattributedAndZeroTimeContributeNothing() {
        assertThat(interests(item(null, HOUR, HOUR, "Drama"), item(TITLE, HOUR, HOUR),
                item(TITLE, HOUR, 0, "Drama")).isEmpty()).isTrue();
        assertThat(GenreInterests.none().isEmpty()).isTrue();
        assertThat(GenreInterests.none().weights()).isEmpty();
        assertThat(GenreInterests.none().share("Drama")).isZero();
        assertThat(interests().weights()).isEmpty();
    }

    @Test
    void shuffledItemsHaveExactlyTheSameWeights() {
        var items = new ArrayList<WatchedItem>();
        for (int i = 1; i <= 40; i++) {
            items.add(item(new UUID(0, i), HOUR, i * 101L, "Drama", "Comedy", "Action"));
        }
        var expected = GenreInterests.of(items, InterestRules.DEFAULT).weights();
        for (int seed = 0; seed < 10; seed++) {
            Collections.shuffle(items, new Random(seed));
            assertThat(GenreInterests.of(items, InterestRules.DEFAULT).weights()).isEqualTo(expected);
        }
    }

    private static WatchedItem item(UUID title, Long duration, long watched, String... genres) {
        return new WatchedItem(UUID.randomUUID(), title, duration, List.of(genres), watched);
    }

    private static GenreInterests interests(WatchedItem... items) {
        return GenreInterests.of(List.of(items), InterestRules.DEFAULT);
    }
}
