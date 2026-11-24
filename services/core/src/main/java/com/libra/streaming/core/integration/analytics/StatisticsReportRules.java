package com.libra.streaming.core.integration.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;

/**
 * The analytics-internal.v2 consumer checks (docs/analytics-statistics-report-ra04-3.md). Bean Validation has already
 * checked the shape and the nonnegative counts; these are the cross-field rules. A report that breaks any of them is
 * an INVALID_RESPONSE: Core shows no number rather than a number it cannot trust.
 */
final class StatisticsReportRules {
    private StatisticsReportRules() {}

    static boolean valid(StatisticsReportQuery query, StatisticsReportResponse report, Instant now) {
        try {
            return window(query, report, now) && consistent(report.totals()) && days(query, report) && top(query, report);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean window(StatisticsReportQuery query, StatisticsReportResponse report, Instant now) {
        return query.from().equals(report.from()) && query.to().equals(report.to()) && !report.updatedAt().isAfter(now);
    }

    private static boolean consistent(Counts counts) { return counts.uniqueViewers() <= counts.qualifiedViews(); }

    private static boolean days(StatisticsReportQuery query, StatisticsReportResponse report) {
        List<DayCounts> days = report.days();
        if (days.size() != ChronoUnit.DAYS.between(query.from(), query.to()) + 1) { return false; }
        long views = 0;
        long watched = 0;
        long viewersSum = 0;
        long viewersMax = 0;
        LocalDate day = query.from();
        for (DayCounts entry : days) {
            if (!day.equals(entry.day()) || !consistent(entry.counts())) { return false; }
            views = Math.addExact(views, entry.qualifiedViews());
            watched = Math.addExact(watched, entry.acceptedWatchedMs());
            viewersSum = Math.addExact(viewersSum, entry.uniqueViewers());
            viewersMax = Math.max(viewersMax, entry.uniqueViewers());
            day = day.plusDays(1);
        }
        Counts totals = report.totals();
        // The days partition the window's views and watched time; the window's distinct viewers are at least those
        // of its busiest day and at most the sum over its days.
        return views == totals.qualifiedViews() && watched == totals.acceptedWatchedMs()
                && viewersMax <= totals.uniqueViewers() && totals.uniqueViewers() <= viewersSum;
    }

    private static boolean top(StatisticsReportQuery query, StatisticsReportResponse report) {
        List<ContentCounts> top = report.topContent();
        if (top.size() > query.topLimit()) { return false; }
        var seen = new HashSet<UUID>();
        long views = 0;
        long watched = 0;
        ContentCounts previous = null;
        for (ContentCounts entry : top) {
            if (!seen.add(entry.contentId()) || !consistent(entry.counts())
                    || (entry.qualifiedViews() == 0 && entry.acceptedWatchedMs() == 0)
                    || (previous != null && !ranksBefore(previous, entry))) { return false; }
            views = Math.addExact(views, entry.qualifiedViews());
            watched = Math.addExact(watched, entry.acceptedWatchedMs());
            previous = entry;
        }
        // Each session and each fact belongs to one title, so the listed titles never hold more than the window.
        Counts totals = report.totals();
        return views <= totals.qualifiedViews() && watched <= totals.acceptedWatchedMs();
    }

    /** The strict order of analytics-internal.v2: views, viewers, watched time descending, then ID text ascending. */
    static boolean ranksBefore(ContentCounts first, ContentCounts second) {
        int order = Long.compare(second.qualifiedViews(), first.qualifiedViews());
        if (order == 0) { order = Long.compare(second.uniqueViewers(), first.uniqueViewers()); }
        if (order == 0) { order = Long.compare(second.acceptedWatchedMs(), first.acceptedWatchedMs()); }
        if (order == 0) { order = first.contentId().toString().compareTo(second.contentId().toString()); }
        return order < 0;
    }
}
