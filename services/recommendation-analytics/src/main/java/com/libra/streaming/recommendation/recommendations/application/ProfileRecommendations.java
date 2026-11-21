package com.libra.streaming.recommendation.recommendations.application;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.recommendations.domain.RankedTitle;
import com.libra.streaming.recommendation.recommendations.domain.Ranking;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ranked movie and series candidates for one profile (RA05.2), served by the V1 recommendations query. Core keeps the
 * final say: it rechecks publication and availability, presents access tiers, and never grants playback from here.
 */
public class ProfileRecommendations {
    /** The most candidates V1 returns (CandidateResponse.contentIds maxItems). */
    public static final int MAX_LIMIT = 100;

    /** The ranked titles together with the confirmed checkpoint they are at least as fresh as. */
    public record Snapshot(UUID profileId, Instant updatedAt, List<RankedTitle> titles) {
        public Snapshot {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            titles = List.copyOf(titles);
        }
    }

    private final CandidateStore candidates;
    private final ProfilePreferences preferences;
    private final ProjectionFreshness freshness;
    private final Clock clock;

    public ProfileRecommendations(CandidateStore candidates, ProfilePreferences preferences,
            ProjectionFreshness freshness, Clock clock) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Empty until all three input topics have a confirmed checkpoint: the answer is then "not ready", never an empty
     * list. The checkpoint is read before any data, so the data is never older than {@code updatedAt}.
     * A profile Analytics knows nothing about (new, deleted, or of another account) gets the cold-start ranking.
     */
    public Optional<Snapshot> forProfile(UUID accountId, UUID profileId, int limit) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileId, "profileId");
        if (limit < 1 || limit > MAX_LIMIT) { throw new IllegalArgumentException("limit"); }
        Optional<Instant> updatedAt = freshness.updatedAt(Projection.RECOMMENDATIONS);
        if (updatedAt.isEmpty()) { return Optional.empty(); }
        Instant now = clock.instant();
        var interests = preferences.interests(accountId, profileId);
        var completed = candidates.completed(accountId, profileId);
        var viewers = candidates.viewersSince(Ranking.popularitySince(now));
        var ranked = Ranking.rank(candidates.titles(), interests, viewers, completed, limit);
        return Optional.of(new Snapshot(profileId, updatedAt.get(), ranked));
    }
}
