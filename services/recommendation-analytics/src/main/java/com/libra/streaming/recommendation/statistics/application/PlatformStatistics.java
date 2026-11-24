package com.libra.streaming.recommendation.statistics.application;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import com.libra.streaming.recommendation.statistics.domain.ContentTotals;
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
 * them (RA04.2), and the V2 report with days and top titles (RA04.3). Recording happens in the playback intake transaction; reading never writes.
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

    /**
     * The V2 report (RA04.3): totals, one entry per day and the top titles, all from the snapshot that read
     * {@code updatedAt}. {@code days} holds every date of the window in ascending order.
     */
    public record Report(StatisticsWindow window, Instant updatedAt, StatisticsTotals totals, List<DayTotals> days,
            List<ContentTotals> topContent) {
        public Report {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(totals, "totals");
            days = List.copyOf(days);
            topContent = List.copyOf(topContent);
            if (days.size() != window.days()) { throw new IllegalStateException("One entry per day of the window"); }
        }
    }

    /** Bounds of the requested number of top titles (analytics-internal.v2 {@code topLimit}). */
    public static final int MIN_TOP = 1;
    public static final int MAX_TOP = 50;

    private static final ConsistentReads UNISOLATED = new ConsistentReads() {
        @Override public <T> T read(java.util.function.Supplier<T> work) { return work.get(); }
    };

    private final StatisticsStore store;
    private final ProjectionFreshness freshness;
    private final ConsistentReads reads;

    /** For callers that only record or read V1 totals, which are single statements. */
    public PlatformStatistics(StatisticsStore store, ProjectionFreshness freshness) {
        this(store, freshness, UNISOLATED);
    }

    public PlatformStatistics(StatisticsStore store, ProjectionFreshness freshness, ConsistentReads reads) {
        this.store = store;
        this.freshness = freshness;
        this.reads = Objects.requireNonNull(reads, "reads");
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

    /** Per-day totals for the window, zeros included, in ascending order; served by V2 through {@link #report}. */
    public List<DayTotals> days(StatisticsWindow window) { return store.days(window); }

    /**
     * Empty before the first confirmed playback checkpoint, like {@link #snapshot}. The checkpoint and the three
     * aggregates are read in one snapshot, checkpoint first: they agree with each other (the days add up to the
     * totals) and none is older than {@code updatedAt}.
     */
    public Optional<Report> report(StatisticsWindow window, int topLimit) {
        Objects.requireNonNull(window, "window");
        if (topLimit < MIN_TOP || topLimit > MAX_TOP) { throw new IllegalArgumentException("topLimit out of range"); }
        return reads.read(() -> freshness.updatedAt(Projection.STATISTICS).map(at -> new Report(window, at,
                store.totals(window), store.days(window), store.topContent(window, topLimit))));
    }
}
