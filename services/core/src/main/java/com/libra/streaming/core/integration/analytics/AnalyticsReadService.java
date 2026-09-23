package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    public Statistics statistics(IdentityPrincipal actor, LocalDate from, LocalDate to, UUID correlationId) {
        access.administrator(actor);
        if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 31
                || to.isAfter(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) { throw DomainException.invalid(); }
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
    private boolean stale(Instant updatedAt) { return updatedAt.isBefore(clock.instant().minus(FRESHNESS)); }
    private static Statistics unavailable(LocalDate from, LocalDate to, Failure reason) {
        return new Statistics(Status.UNAVAILABLE, reason, from, to, null, null, null, null);
    }
}
