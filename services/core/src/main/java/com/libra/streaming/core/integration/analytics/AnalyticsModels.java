package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.catalog.CatalogModels.Kind;
import com.libra.streaming.core.catalog.CatalogModels.PublicView;
import jakarta.validation.Valid;
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
    // analytics-internal.v2 (RA04.3). Shape is checked here; cross-field invariants in StatisticsReportRules.
    public record StatisticsReportQuery(LocalDate from, LocalDate to, int topLimit) {}
    public record Counts(@NotNull @Min(0) Long qualifiedViews, @NotNull @Min(0) Long uniqueViewers,
            @NotNull @Min(0) Long acceptedWatchedMs) {}
    public record DayCounts(@NotNull LocalDate day, @NotNull @Min(0) Long qualifiedViews,
            @NotNull @Min(0) Long uniqueViewers, @NotNull @Min(0) Long acceptedWatchedMs) {
        Counts counts() { return new Counts(qualifiedViews, uniqueViewers, acceptedWatchedMs); }
    }
    public record ContentCounts(@NotNull UUID contentId, @NotNull @Min(0) Long qualifiedViews,
            @NotNull @Min(0) Long uniqueViewers, @NotNull @Min(0) Long acceptedWatchedMs) {
        Counts counts() { return new Counts(qualifiedViews, uniqueViewers, acceptedWatchedMs); }
    }
    public record StatisticsReportResponse(@NotNull LocalDate from, @NotNull LocalDate to, @NotNull Instant updatedAt,
            @NotNull @Valid Counts totals, @NotNull @Size(min = 1, max = 31) List<@NotNull @Valid DayCounts> days,
            @NotNull @Size(max = 50) List<@NotNull @Valid ContentCounts> topContent) {}

    // analytics-internal.v2 recommendation lists (RA05.3).
    public enum ListKind { PERSONAL, SIMILAR, POPULAR, NEW }
    public enum Reason { GENRE_INTEREST, SIMILAR_GENRES, POPULAR, NEWLY_PUBLISHED }
    public record RecommendationListQuery(UUID accountId, UUID profileId, ListKind list, UUID seedContentId, int limit) {}
    public record RecommendedTitle(@NotNull UUID contentId, @NotNull Reason reason) {}
    public record RecommendationListResponse(@NotNull UUID profileId, @NotNull ListKind list,
            @NotNull @Pattern(regexp = "[a-z0-9][a-z0-9.-]{0,31}") String algorithmVersion, @NotNull Instant updatedAt,
            @NotNull @Size(max = 100) List<@NotNull @Valid RecommendedTitle> items) {}

    public record Recommendations(Source source, Failure reason, Instant updatedAt, List<PublicView> items) {}
    public record Statistics(Status status, Failure reason, LocalDate from, LocalDate to,
            Instant updatedAt, Long qualifiedViews, Long uniqueViewers, Long acceptedWatchedMs) {}
    /** Core's admin report (core-analytics.v1 1.1.0). UNAVAILABLE carries no numbers at all, never zeros. */
    public record StatisticsReport(Status status, Failure reason, LocalDate from, LocalDate to, Instant updatedAt,
            Counts totals, List<DayCounts> days, List<ReportedTitle> topContent) {}
    /** kind/title come from Core's catalog (published revision, else draft); null when Core does not know the ID. */
    public record ReportedTitle(UUID contentId, Kind kind, String title, boolean published, long qualifiedViews,
            long uniqueViewers, long acceptedWatchedMs) {}
    public record TitleLabel(Kind kind, String title, boolean published) {}
    /** Core's list answer (core-analytics.v1 1.2.0). A fallback never claims a reason Core did not apply. */
    public record RecommendationList(Source source, Failure reason, String list, String algorithmVersion,
            Instant updatedAt, List<ListItem> items) {}
    public record ListItem(PublicView content, Reason reason) {}
    public record Result<T>(T value, Failure failure) {
        public static <T> Result<T> failed(Failure failure) { return new Result<>(null, failure); }
    }
}
