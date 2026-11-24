package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;

@Service
public class AnalyticsReadService {
    private static final Duration FRESHNESS = Duration.ofMinutes(15);
    private final AnalyticsReadAccess access;
    private final AnalyticsClient client;
    private final Clock clock;
    public AnalyticsReadService(AnalyticsReadAccess access, AnalyticsClient client, Clock clock) {
        this.access = access; this.client = client; this.clock = clock;
    }
    public Recommendations recommendations(IdentityPrincipal actor, UUID profileId, int limit, UUID correlationId) {
        if (limit < 1 || limit > 100) { throw DomainException.invalid(); }
        access.profile(actor, profileId);
        var result = client.recommendations(new RecommendationQuery(actor.accountId(), profileId, 100), correlationId);
        if (result.failure() == null) {
            if (!profileId.equals(result.value().profileId()) || result.value().updatedAt().isAfter(clock.instant())) {
                result = Result.failed(Failure.INVALID_RESPONSE);
            } else if (stale(result.value().updatedAt())) { result = Result.failed(Failure.STALE); }
        }
        return access.project(actor, profileId, limit, result);
    }
    /**
     * A V2 list (RA05.3). Core asks Analytics for 100 titles, then keeps the ones it can still show. Recommendations
     * never grant playback: the admission decides as always.
     */
    public RecommendationList recommendationList(IdentityPrincipal actor, UUID profileId, ListKind list, UUID seed,
            int limit, UUID correlationId) {
        if (list == null || limit < 1 || limit > 20 || (list == ListKind.SIMILAR) != (seed != null)) {
            throw DomainException.invalid();
        }
        access.profileAndSeed(actor, profileId, seed);
        var query = new RecommendationListQuery(actor.accountId(), profileId, list, seed, 100);
        var result = client.recommendationList(query, correlationId);
        if (result.failure() == null) {
            var value = result.value();
            if (!RecommendationListRules.valid(query, value, clock.instant())) {
                result = Result.failed(Failure.INVALID_RESPONSE);
            } else if (stale(value.updatedAt())) {
                result = Result.failed(Failure.STALE);
            }
        }
        return access.projectList(actor, profileId, list, limit, result);
    }
    public Statistics statistics(IdentityPrincipal actor, LocalDate from, LocalDate to, UUID correlationId) {
        access.administrator(actor);
        if (!validWindow(from, to)) { throw DomainException.invalid(); }
        var result = client.statistics(new StatisticsQuery(from, to), correlationId);
        access.administrator(actor); // Revocation/demotion during the HTTP call must still deny disclosure.
        if (result.failure() != null) { return unavailable(from, to, result.failure()); }
        var value = result.value();
        if (!from.equals(value.from()) || !to.equals(value.to()) || value.updatedAt().isAfter(clock.instant())
                || value.uniqueViewers() > value.qualifiedViews()) { return unavailable(from, to, Failure.INVALID_RESPONSE); }
        boolean stale = stale(value.updatedAt());
        return new Statistics(stale ? Status.STALE : Status.AVAILABLE, stale ? Failure.STALE : null,
                from, to, value.updatedAt(), value.qualifiedViews(), value.uniqueViewers(), value.acceptedWatchedMs());
    }
    /**
     * The V2 report (RA04.3): the same window and freshness rules as {@link #statistics}, plus one entry per UTC day
     * and the top titles named from Core's catalog. Any broken invariant hides every number (UNAVAILABLE).
     */
    public StatisticsReport statisticsReport(IdentityPrincipal actor, LocalDate from, LocalDate to, int top,
            UUID correlationId) {
        access.administrator(actor);
        if (!validWindow(from, to) || top < 1 || top > 50) { throw DomainException.invalid(); }
        var query = new StatisticsReportQuery(from, to, top);
        var result = client.statisticsReport(query, correlationId);
        if (result.failure() == null && !StatisticsReportRules.valid(query, result.value(), clock.instant())) {
            result = Result.failed(Failure.INVALID_RESPONSE);
        }
        var value = result.value();
        // Rechecked after the HTTP call even when nothing is shown, so a demoted administrator learns nothing at all.
        var labels = access.administratorLabels(actor,
                value == null ? List.of() : value.topContent().stream().map(ContentCounts::contentId).toList());
        if (value == null) {
            return new StatisticsReport(Status.UNAVAILABLE, result.failure(), from, to, null, null, null, null);
        }
        var titles = value.topContent().stream().map(entry -> {
            var label = labels.get(entry.contentId());
            return new ReportedTitle(entry.contentId(), label == null ? null : label.kind(),
                    label == null ? null : label.title(), label != null && label.published(),
                    entry.qualifiedViews(), entry.uniqueViewers(), entry.acceptedWatchedMs());
        }).toList();
        boolean stale = stale(value.updatedAt());
        return new StatisticsReport(stale ? Status.STALE : Status.AVAILABLE, stale ? Failure.STALE : null, from, to,
                value.updatedAt(), value.totals(), value.days(), titles);
    }
    private boolean validWindow(LocalDate from, LocalDate to) {
        return from != null && to != null && !from.isAfter(to) && ChronoUnit.DAYS.between(from, to) < 31
                && !to.isAfter(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
    private boolean stale(Instant updatedAt) { return updatedAt.isBefore(clock.instant().minus(FRESHNESS)); }
    private static Statistics unavailable(LocalDate from, LocalDate to, Failure reason) {
        return new Statistics(Status.UNAVAILABLE, reason, from, to, null, null, null, null);
    }
}
