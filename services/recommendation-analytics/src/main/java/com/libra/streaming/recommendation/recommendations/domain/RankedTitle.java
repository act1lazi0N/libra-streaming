package com.libra.streaming.recommendation.recommendations.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One title of a ranked list with the rule that selected it. V1 serves the IDs only; the rule is kept so the reason
 * planned for RA05.3 ({@code /internal/v2}) is the rule that actually placed the title.
 */
public record RankedTitle(UUID titleId, Rule rule) {
    public enum Rule {
        /** Shares genres with the profile's interests (RA05.1). */
        GENRE_INTEREST,
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
