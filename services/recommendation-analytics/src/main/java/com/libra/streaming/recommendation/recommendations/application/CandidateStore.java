package com.libra.streaming.recommendation.recommendations.application;

import com.libra.streaming.recommendation.recommendations.domain.TitleCandidate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read side of candidate generation, over the catalog and playback projections. Each read is one statement. */
public interface CandidateStore {
    /** Every movie and series that is published and playable in the catalog projection. */
    List<TitleCandidate> titles();

    /**
     * Viewers per movie or series: distinct accounts with a session on the title (the movie or any of the series'
     * episodes) whose qualifying instant is at or after {@code since}. Titles without viewers are absent.
     */
    Map<UUID, Long> viewersSince(Instant since);

    /**
     * Titles the profile has completed, by Core's completion rule (a position at or past 95% of the item's duration)
     * applied to the item's latest accepted position, as Core's history does. A movie is completed when the movie is;
     * a series when every playable episode is. Empty for another account.
     */
    Set<UUID> completed(UUID accountId, UUID profileId);

    /**
     * The genres of the published revision of a movie or series, whether or not it is playable now; empty when the
     * catalog projection does not know it as a published movie or series (RA05.3, seed of a SIMILAR list).
     */
    Optional<List<String>> genresOf(UUID titleId);
}
