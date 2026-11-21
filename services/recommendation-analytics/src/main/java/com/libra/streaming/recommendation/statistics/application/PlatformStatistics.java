package com.libra.streaming.recommendation.statistics.application;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.libra.streaming.recommendation.statistics.domain.DayContribution;
import com.libra.streaming.recommendation.statistics.domain.DayTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Qualified views, unique viewers and accepted watched time per UTC day (RA04.1), and the V1 snapshot served from
 * them (RA04.2). Recording happens in the playback intake transaction; reading never writes.
 */
public class PlatformStatistics {
    /** Totals of a window together with the confirmed checkpoint they are at least as fresh as. */
    public record Snapshot(StatisticsWindow window, Instant updatedAt, StatisticsTotals totals) {
        public Snapshot {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(totals, "totals");
        }
    }

    private final StatisticsStore store;
    private final ProjectionFreshness freshness;

    public PlatformStatistics(StatisticsStore store, ProjectionFreshness freshness) {
        this.store = store;
        this.freshness = freshness;
    }

    /**
     * Adds one accepted playback fact. Must run in the transaction that stores the fact, so the rollup and the facts
     * commit together and a redelivered (duplicate) fact, stopped by the receipt, never reaches this method.
     *
     * @param qualifiedBefore the session's qualifying instant before the fact; null when it had none
     * @param qualifiedAfter the session's qualifying instant after the fact; null when it still has none
     */
    public void record(UUID accountId, Instant occurredAt, long acceptedWatchedMs, Instant qualifiedBefore,
            Instant qualifiedAfter) {
        Objects.requireNonNull(accountId, "accountId");
        List<DayContribution> contributions =
                DayContribution.of(occurredAt, acceptedWatchedMs, qualifiedBefore, qualifiedAfter);
        if (!contributions.isEmpty()) { store.add(accountId, contributions); }
    }

    /**
     * Empty before the first confirmed playback checkpoint: the answer is then "not ready", never zeros.
     * The checkpoint is read before the totals. Every fact committed before the checkpoint was confirmed is therefore
     * visible to the totals query that follows, so the totals are never older than {@code updatedAt}.
     */
    public Optional<Snapshot> snapshot(StatisticsWindow window) {
        Optional<Instant> updatedAt = freshness.updatedAt(Projection.STATISTICS);
        return updatedAt.map(at -> new Snapshot(window, at, store.totals(window)));
    }

    /** Per-day totals for the window; not served by V1, which carries totals only (RA04.3 adds a versioned API). */
    public List<DayTotals> days(StatisticsWindow window) { return store.days(window); }
}
