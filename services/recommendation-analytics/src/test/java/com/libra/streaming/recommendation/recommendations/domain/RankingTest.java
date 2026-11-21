package com.libra.streaming.recommendation.recommendations.domain;

import com.libra.streaming.recommendation.preferences.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.libra.streaming.recommendation.recommendations.domain.RankedTitle.Rule.*;

class RankingTest {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    @Test
    void tiersRecordTheirRulesAndZeroScoresFallBack() {
        var genre = title(1, NOW.minusSeconds(100), "Drama");
        var popular = title(2, NOW, "Comedy");
        var newest = title(3, NOW, "Action");
        assertThat(Ranking.rank(List.of(newest, popular, genre), interests(),
                Map.of(popular.titleId(), 100L), Set.of(), 10)).containsExactly(
                new RankedTitle(genre.titleId(), GENRE_INTEREST),
                new RankedTitle(popular.titleId(), POPULAR),
                new RankedTitle(newest.titleId(), NEWLY_PUBLISHED));
    }

    @Test
    void coldStartUsesViewersThenNewest() {
        var popular = title(1, NOW.minusSeconds(100));
        var morePopular = title(2, NOW.minusSeconds(200));
        var older = title(3, NOW.minusSeconds(10));
        var newest = title(4, NOW);
        assertThat(Ranking.rank(List.of(older, popular, newest, morePopular), GenreInterests.none(),
                Map.of(popular.titleId(), 1L, morePopular.titleId(), 2L), Set.of(), 10)).containsExactly(
                new RankedTitle(morePopular.titleId(), POPULAR), new RankedTitle(popular.titleId(), POPULAR),
                new RankedTitle(newest.titleId(), NEWLY_PUBLISHED), new RankedTitle(older.titleId(), NEWLY_PUBLISHED));
    }

    @Test
    void completedTitlesAreExcludedFromEveryTier() {
        var genre = title(1, NOW, "Drama");
        var popular = title(2, NOW);
        var newest = title(3, NOW);
        assertThat(Ranking.rank(List.of(genre, popular, newest), interests(), Map.of(popular.titleId(), 5L),
                Set.of(genre.titleId(), popular.titleId(), newest.titleId()), 10)).isEmpty();
    }

    @Test
    void tiesUseScoreThenViewersThenPublicationWithNullLastThenUuidText() {
        var exact = title(9, null, "Drama");
        var diluted = title(8, NOW, "Drama", "Comedy");
        var popular = title(7, NOW.minusSeconds(100), "Drama");
        var newest = title(6, NOW, "Drama");
        var older = title(5, NOW.minusSeconds(1), "Drama");
        var zero = title(0, NOW, "Drama");
        var negative = new TitleCandidate(UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                TitleCandidate.Kind.MOVIE, List.of("Drama"), NOW);
        assertThat(negative.titleId().compareTo(zero.titleId())).isNegative();
        assertThat(negative.titleId().toString().compareTo(zero.titleId().toString())).isPositive();
        var ranked = Ranking.rank(List.of(diluted, exact, older, negative, newest, popular, zero), interests(),
                Map.of(popular.titleId(), 10L, diluted.titleId(), 1000L), Set.of(), 20);
        assertThat(ranked).extracting(RankedTitle::titleId).containsExactly(popular.titleId(), zero.titleId(),
                newest.titleId(), negative.titleId(), older.titleId(), exact.titleId(), diluted.titleId());
    }

    @Test
    void exactGenreHasGreaterAffinityThanDilutedGenres() {
        assertThat(Ranking.affinity(title(1, NOW, "Drama"), interests()))
                .isGreaterThan(Ranking.affinity(title(2, NOW, "Drama", "Comedy", "Action", "Horror"), interests()));
    }

    @Test
    void limitTruncatesAndNonPositiveLimitIsRejected() {
        assertThat(Ranking.rank(List.of(title(2, NOW), title(1, NOW)), GenreInterests.none(),
                Map.of(), Set.of(), 1)).containsExactly(new RankedTitle(new UUID(0, 1), NEWLY_PUBLISHED));
        for (int limit : new int[] {0, -1}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    Ranking.rank(List.of(), GenreInterests.none(), Map.of(), Set.of(), limit));
        }
    }

    @Test
    void duplicatesAppearOnceAndShufflingKeepsTheSameList() {
        var candidates = new ArrayList<>(List.of(title(1, NOW, "Drama"), title(2, NOW),
                title(3, NOW.minusSeconds(1)), title(1, NOW, "Drama")));
        var expected = Ranking.rank(candidates, interests(), Map.of(new UUID(0, 2), 3L), Set.of(), 10);
        assertThat(expected).extracting(RankedTitle::titleId).doesNotHaveDuplicates().hasSize(3);
        for (int seed = 0; seed < 10; seed++) {
            Collections.shuffle(candidates, new Random(seed));
            assertThat(Ranking.rank(candidates, interests(), Map.of(new UUID(0, 2), 3L), Set.of(), 10))
                    .isEqualTo(expected);
        }
    }

    @Test
    void popularityWindowIsSevenDays() {
        assertThat(Ranking.popularitySince(NOW)).isEqualTo(Instant.parse("2026-08-31T12:00:00Z"));
    }

    private static TitleCandidate title(long id, Instant published, String... genres) {
        return new TitleCandidate(new UUID(0, id), TitleCandidate.Kind.MOVIE, List.of(genres), published);
    }

    private static GenreInterests interests() {
        return GenreInterests.of(List.of(new WatchedItem(new UUID(0, 99), new UUID(0, 99),
                1000L, List.of("Drama"), 1000)), InterestRules.DEFAULT);
    }
}
