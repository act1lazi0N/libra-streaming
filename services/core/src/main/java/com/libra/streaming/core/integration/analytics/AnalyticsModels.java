package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.catalog.CatalogModels.PublicView;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AnalyticsModels {
    private AnalyticsModels() {}
    public enum Failure { DISABLED, UNAVAILABLE, INVALID_RESPONSE, STALE, NO_VISIBLE_CANDIDATES }
    public enum Source { ANALYTICS, FALLBACK }
    public enum Status { AVAILABLE, STALE, UNAVAILABLE }
    public record RecommendationQuery(UUID accountId, UUID profileId, int limit) {}
    public record StatisticsQuery(LocalDate from, LocalDate to) {}
    public record CandidateResponse(@NotNull UUID profileId, @NotNull Instant updatedAt,
            @NotNull @Size(max = 100) List<@NotNull UUID> contentIds) {}
    public record StatisticsResponse(@NotNull LocalDate from, @NotNull LocalDate to, @NotNull Instant updatedAt,
            @NotNull @Min(0) Long qualifiedViews, @NotNull @Min(0) Long uniqueViewers,
            @NotNull @Min(0) Long acceptedWatchedMs) {}
    public record Recommendations(Source source, Failure reason, Instant updatedAt, List<PublicView> items) {}
    public record Statistics(Status status, Failure reason, LocalDate from, LocalDate to,
            Instant updatedAt, Long qualifiedViews, Long uniqueViewers, Long acceptedWatchedMs) {}
    public record Result<T>(T value, Failure failure) {
        public static <T> Result<T> failed(Failure failure) { return new Result<>(null, failure); }
    }
}
