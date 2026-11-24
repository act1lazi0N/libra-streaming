package com.libra.streaming.recommendation.recommendations.domain;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.libra.streaming.recommendation.recommendations.domain.RankedTitle.Rule.*;

/** Pure domain checks; no database or mocked persistence. */
class RankingListsTest {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    @Test
    void similarUsesCosineOfDistinctSetsExcludesSeedAndCompletedAndNeverFills() {
        var seed = title(1, NOW, "drama", "crime");
        var exact = title(2, NOW, "crime", "drama", "drama");
        var single = title(3, NOW, "drama");
        var diluted = title(4, NOW, "drama", "comedy", "horror");
        var completed = title(5, NOW, "drama", "crime");
        var unrelated = title(6, NOW, "comedy");
        var candidates = List.of(seed, diluted, single, exact, completed, unrelated, exact, title(7, NOW));
        assertThat(Ranking.similar(candidates, seed.titleId(), seed.genres(),
                Map.of(diluted.titleId(), 100L), Set.of(completed.titleId()), 100)).containsExactly(
                new RankedTitle(exact.titleId(), SIMILAR_GENRES), new RankedTitle(single.titleId(), SIMILAR_GENRES),
                new RankedTitle(diluted.titleId(), SIMILAR_GENRES));
        assertThat(Ranking.similar(candidates, seed.titleId(), List.of(), Map.of(), Set.of(), 100)).isEmpty();
    }

    @Test
    void similarAndPopularTiesUseViewersPublicationNullLastAndUuidText() {
        var low = title(1, NOW, "drama");
        var high = new TitleCandidate(UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                TitleCandidate.Kind.SERIES, List.of("drama"), NOW);
        var older = title(3, NOW.minusSeconds(1), "drama");
        var unknown = title(4, null, "drama");
        var mostViewed = title(5, null, "drama");
        var titles = List.of(unknown, older, high, mostViewed, low);
        var viewers = Map.of(low.titleId(), 1L, high.titleId(), 1L, older.titleId(), 1L,
                unknown.titleId(), 1L, mostViewed.titleId(), 2L);
        var expected = List.of(mostViewed.titleId(), low.titleId(), high.titleId(), older.titleId(), unknown.titleId());
        assertThat(Ranking.similar(titles, new UUID(0, 99), List.of("drama"), viewers, Set.of(), 100))
                .extracting(RankedTitle::titleId).containsExactlyElementsOf(expected);
        assertThat(Ranking.popular(titles, viewers, Set.of(), 100))
                .extracting(RankedTitle::titleId).containsExactlyElementsOf(expected);
    }

    @Test
    void popularRequiresPositiveViewersExcludesCompletedAndNeverFills() {
        var titles = List.of(title(1, NOW), title(2, NOW), title(3, NOW), title(4, NOW), title(1, NOW));
        assertThat(Ranking.popular(titles, Map.of(new UUID(0, 1), 2L, new UUID(0, 2), 1L,
                new UUID(0, 3), 0L), Set.of(new UUID(0, 2)), 100))
                .containsExactly(new RankedTitle(new UUID(0, 1), POPULAR));
    }

    @Test
    void newestUsesPublicationBeforeViewersThenUuidTextWithNullDatesLast() {
        var high = new TitleCandidate(UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                TitleCandidate.Kind.MOVIE, List.of(), NOW);
        assertThat(Ranking.newest(List.of(title(4, null), title(3, NOW.minusSeconds(1)), high,
                title(2, NOW), title(1, NOW), title(5, NOW), title(1, NOW)),
                Map.of(new UUID(0, 2), 2L, new UUID(0, 3), 100L, new UUID(0, 4), 1000L),
                Set.of(new UUID(0, 5)), 100)).containsExactly(
                new RankedTitle(new UUID(0, 2), NEWLY_PUBLISHED), new RankedTitle(new UUID(0, 1), NEWLY_PUBLISHED),
                new RankedTitle(high.titleId(), NEWLY_PUBLISHED), new RankedTitle(new UUID(0, 3), NEWLY_PUBLISHED),
                new RankedTitle(new UUID(0, 4), NEWLY_PUBLISHED));
    }

    @Test
    void allNewAlgorithmsTruncateAndRejectNonPositiveLimits() {
        var titles = List.of(title(1, NOW, "drama"), title(1, NOW, "drama"), title(2, NOW, "drama"));
        var viewers = Map.of(new UUID(0, 1), 1L, new UUID(0, 2), 1L);
        assertThat(Ranking.similar(titles, new UUID(0, 99), List.of("drama"), viewers, Set.of(), 1))
                .containsExactly(new RankedTitle(new UUID(0, 1), SIMILAR_GENRES));
        assertThat(Ranking.popular(titles, viewers, Set.of(), 1))
                .containsExactly(new RankedTitle(new UUID(0, 1), POPULAR));
        assertThat(Ranking.newest(titles, viewers, Set.of(), 1))
                .containsExactly(new RankedTitle(new UUID(0, 1), NEWLY_PUBLISHED));
        for (int limit : new int[] {0, -1}) {
            assertThatIllegalArgumentException().isThrownBy(() -> Ranking.similar(titles, new UUID(0, 99),
                    List.of("drama"), viewers, Set.of(), limit));
            assertThatIllegalArgumentException().isThrownBy(() -> Ranking.popular(titles, viewers, Set.of(), limit));
            assertThatIllegalArgumentException().isThrownBy(() -> Ranking.newest(titles, viewers, Set.of(), limit));
        }
    }

    private static TitleCandidate title(long id, Instant published, String... genres) {
        return new TitleCandidate(new UUID(0, id), TitleCandidate.Kind.MOVIE, List.of(genres), published);
    }
}
