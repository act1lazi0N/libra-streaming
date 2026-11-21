package com.libra.streaming.recommendation.statistics.domain;

/**
 * Aggregates over a set of UTC days. Platform totals only: no account or profile identifier.
 *
 * @param qualifiedViews sessions that qualified on one of the days, each counted once
 * @param uniqueViewers distinct accounts among those sessions over all the days together, not a sum per day
 * @param acceptedWatchedMs accepted watched time whose event occurred on one of the days
 */
public record StatisticsTotals(long qualifiedViews, long uniqueViewers, long acceptedWatchedMs) {
    public static final StatisticsTotals ZERO = new StatisticsTotals(0, 0, 0);

    public StatisticsTotals {
        if (qualifiedViews < 0 || uniqueViewers < 0 || acceptedWatchedMs < 0) {
            throw new IllegalStateException("Statistics are never negative");
        }
        // Each unique viewer has at least one counted view; a violation means the rollup is corrupt.
        if (uniqueViewers > qualifiedViews) { throw new IllegalStateException("More viewers than views"); }
    }
}
