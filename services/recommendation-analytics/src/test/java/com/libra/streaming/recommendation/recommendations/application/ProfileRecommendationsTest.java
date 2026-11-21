package com.libra.streaming.recommendation.recommendations.application;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.libra.streaming.recommendation.preferences.application.*;
import com.libra.streaming.recommendation.preferences.domain.InterestRules;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileRecommendationsTest {
    private final Instant now = Instant.parse("2026-09-07T12:00:00Z");
    private final UUID account = new UUID(0, 1);
    private final UUID profile = new UUID(0, 2);
    private final ProjectionFreshness freshness = mock(ProjectionFreshness.class);
    private final CandidateStore candidates = mock(CandidateStore.class);
    private final PreferenceStore store = mock(PreferenceStore.class);
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ProfileRecommendations recommendations = new ProfileRecommendations(candidates,
            new ProfilePreferences(store, InterestRules.DEFAULT, clock), freshness, clock);

    @Test
    void missingCheckpointDoesNotReadAnyData() {
        when(freshness.updatedAt(Projection.RECOMMENDATIONS)).thenReturn(Optional.empty());
        assertThat(recommendations.forProfile(account, profile, 20)).isEmpty();
        verifyNoInteractions(candidates, store);
    }

    @Test
    void checkpointPrecedesDataAndSnapshotCarriesItsIdentityAndTime() {
        var checkpoint = now.minusSeconds(123);
        when(freshness.updatedAt(Projection.RECOMMENDATIONS)).thenReturn(Optional.of(checkpoint));
        when(store.watched(account, profile, LocalDate.parse("2026-08-09"))).thenReturn(List.of());
        var snapshot = recommendations.forProfile(account, profile, 20).orElseThrow();
        assertThat(snapshot.profileId()).isEqualTo(profile);
        assertThat(snapshot.updatedAt()).isEqualTo(checkpoint);
        var order = inOrder(freshness, candidates, store);
        order.verify(freshness).updatedAt(Projection.RECOMMENDATIONS);
        order.verify(store).watched(account, profile, LocalDate.parse("2026-08-09"));
        order.verify(candidates).completed(account, profile);
        order.verify(candidates).viewersSince(Instant.parse("2026-08-31T12:00:00Z"));
        order.verify(candidates).titles();
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsLimitsOutsideTheContract(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> recommendations.forProfile(account, profile, limit));
        verifyNoInteractions(freshness, candidates, store);
    }
}
