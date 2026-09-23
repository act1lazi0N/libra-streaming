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
    @GetMapping("/admin/statistics")
    Statistics statistics(@AuthenticationPrincipal IdentityPrincipal actor,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) UUID correlationId) {
        return service.statistics(actor, from, to, correlationId);
    }
}
