package com.libra.streaming.recommendation.recommendations.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One title of a ranked list with the rule that selected it. V1 serves the IDs only; analytics-internal.v2 (RA05.3)
 * serves the rule as the reason, so a reason is always the rule that actually placed the title.
 */
public record RankedTitle(UUID titleId, Rule rule) {
    public enum Rule {
        /** Shares genres with the profile's interests (RA05.1). */
        GENRE_INTEREST,
        /** Shares genres with the seed title of a SIMILAR list (RA05.3). */
        SIMILAR_GENRES,
        /** Watched by the most accounts in the last 7 days. */
        POPULAR,
        /** Newest publication first. */
        NEWLY_PUBLISHED
    }

    public RankedTitle {
        Objects.requireNonNull(titleId, "titleId");
        Objects.requireNonNull(rule, "rule");
    }
}
