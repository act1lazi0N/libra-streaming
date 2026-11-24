package com.libra.streaming.recommendation.statistics.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The totals of one title over a window (RA04.3): a movie or series ID, or the watched item's own ID while Analytics
 * does not know which series an episode belongs to. Platform aggregates only: no account or profile identifier.
 */
public record ContentTotals(UUID contentId, StatisticsTotals totals) {
    public ContentTotals {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(totals, "totals");
    }
}
