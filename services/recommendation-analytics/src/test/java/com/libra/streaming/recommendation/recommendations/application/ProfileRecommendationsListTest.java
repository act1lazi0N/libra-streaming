package com.libra.streaming.recommendation.recommendations.application;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.preferences.domain.GenreInterests;
import com.libra.streaming.recommendation.recommendations.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mocked read ports: readiness and read ordering, not PostgreSQL isolation proof. */
class ProfileRecommendationsListTest {
    private final Instant now = Instant.parse("2026-09-07T12:00:00Z");
    private final UUID account = new UUID(0, 1);
    private final UUID profile = new UUID(0, 2);
    private final UUID seed = new UUID(0, 3);
    private final ProjectionFreshness freshness = mock(ProjectionFreshness.class);
    private final CandidateStore candidates = mock(CandidateStore.class);
    private final ProfilePreferences preferences = mock(ProfilePreferences.class);
    private final ProfileRecommendations recommendations = new ProfileRecommendations(candidates, preferences,
            freshness, Clock.fixed(now, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(ListKind.class)
    void missingCheckpointReadsNothing(ListKind list) {
        when(freshness.updatedAt(Projection.RECOMMENDATIONS)).thenReturn(Optional.empty());
        assertThat(recommendations.list(account, profile, list, seedFor(list), 20)).isEmpty();
        verifyNoInteractions(candidates, preferences);
    }

    @ParameterizedTest
    @EnumSource(ListKind.class)
    void rejectsSeedMismatchBeforeReading(ListKind list) {
        assertThatIllegalArgumentException().isThrownBy(() -> recommendations.list(account, profile, list,
                list == ListKind.SIMILAR ? null : seed, 20));
        verifyNoInteractions(freshness, candidates, preferences);
    }

    @ParameterizedTest
    @EnumSource(ListKind.class)
    void rejectsOutOfRangeLimitBeforeReading(ListKind list) {
        for (int limit : new int[] {0, 101}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    recommendations.list(account, profile, list, seedFor(list), limit));
        }
        verifyNoInteractions(freshness, candidates, preferences);
    }

    @Test
    void unknownSimilarSeedReturnsEmptyWithoutReadingTitlesOrPreferences() {
        when(freshness.updatedAt(Projection.RECOMMENDATIONS)).thenReturn(Optional.of(now));
        when(candidates.genresOf(seed)).thenReturn(Optional.empty());
        var snapshot = recommendations.list(account, profile, ListKind.SIMILAR, seed, 100).orElseThrow();
        assertThat(snapshot.titles()).isEmpty();
        assertThat(snapshot.profileId()).isEqualTo(profile);
        assertThat(snapshot.list()).isEqualTo(ListKind.SIMILAR);
        assertThat(snapshot.updatedAt()).isEqualTo(now);
        verify(candidates, never()).titles();
        verify(candidates, never()).completed(any(), any());
        verify(candidates, never()).viewersSince(any());
        verifyNoInteractions(preferences);
    }

    @ParameterizedTest
    @EnumSource(ListKind.class)
    void checkpointPrecedesEveryDataReadAndSnapshotEchoesIdentity(ListKind list) {
        var checkpoint = now.minusSeconds(60);
        when(freshness.updatedAt(Projection.RECOMMENDATIONS)).thenReturn(Optional.of(checkpoint));
        when(candidates.genresOf(seed)).thenReturn(Optional.of(List.of("drama")));
        when(preferences.interests(account, profile)).thenReturn(GenreInterests.none());
        var snapshot = recommendations.list(account, profile, list, seedFor(list), 100).orElseThrow();
        assertThat(snapshot.profileId()).isEqualTo(profile);
        assertThat(snapshot.list()).isEqualTo(list);
        assertThat(snapshot.updatedAt()).isEqualTo(checkpoint);
        var order = inOrder(freshness, candidates, preferences);
        order.verify(freshness).updatedAt(Projection.RECOMMENDATIONS);
        // The seed's genres come first, so an unknown seed is answered before the heavier reads.
        if (list == ListKind.SIMILAR) { order.verify(candidates).genresOf(seed); }
        order.verify(candidates).completed(account, profile);
        order.verify(candidates).viewersSince(now.minus(Duration.ofDays(7)));
        order.verify(candidates).titles();
        if (list == ListKind.PERSONAL) { order.verify(preferences).interests(account, profile); }
        else { verifyNoInteractions(preferences); }
        order.verifyNoMoreInteractions();
    }

    @Test
    void listSnapshotRejectsEveryRuleOutsideItsList() {
        for (var list : ListKind.values()) {
            for (var rule : RankedTitle.Rule.values()) {
                if (!list.rules().contains(rule)) {
                    assertThatIllegalStateException().isThrownBy(() -> new ProfileRecommendations.ListSnapshot(
                            profile, list, now, List.of(new RankedTitle(seed, rule))));
                }
            }
        }
    }

    private UUID seedFor(ListKind list) { return list == ListKind.SIMILAR ? seed : null; }
}
