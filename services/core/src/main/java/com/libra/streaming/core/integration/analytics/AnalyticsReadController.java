package com.libra.streaming.core.integration.analytics;

import com.libra.streaming.core.api.CorrelationIdFilter;
import com.libra.streaming.core.identity.IdentityPrincipal;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;

@RestController
@RequestMapping("/v1")
public class AnalyticsReadController {
    private final AnalyticsReadService service;
    public AnalyticsReadController(AnalyticsReadService service) { this.service = service; }
    @GetMapping("/profiles/{profileId}/recommendations")
    Recommendations recommendations(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return service.recommendations(actor, profileId, limit, correlationId);
    }
    @GetMapping("/profiles/{profileId}/recommendations/lists/{list}")
    RecommendationList recommendationList(@AuthenticationPrincipal IdentityPrincipal actor, @PathVariable UUID profileId,
            @PathVariable String list, @RequestParam(required = false) UUID contentId,
            @RequestParam(defaultValue = "12") @Min(1) @Max(20) int limit,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return service.recommendationList(actor, profileId, listKind(list), contentId, limit, correlationId);
    }
    /** Lower-case path names only: {@code personal}, {@code similar}, {@code popular}, {@code new}. */
    private static ListKind listKind(String name) {
        for (ListKind kind : ListKind.values()) {
            if (kind.name().toLowerCase(java.util.Locale.ROOT).equals(name)) { return kind; }
        }
        throw com.libra.streaming.core.api.DomainException.invalid();
    }
    @GetMapping("/admin/statistics")
    Statistics statistics(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return service.statistics(actor, from, to, correlationId);
    }
    @GetMapping("/admin/statistics/report")
    StatisticsReport statisticsReport(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int top,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return service.statisticsReport(actor, from, to, top, correlationId);
    }
}
