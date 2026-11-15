package com.libra.streaming.recommendation.internalapi.api;

import com.libra.streaming.recommendation.freshness.application.ProjectionFreshness;
import com.libra.streaming.recommendation.freshness.domain.Projection;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two analytics-internal.v1 operations. No projection is served yet (statistics: RA04.2, recommendations:
 * RA05.2), so an authorized call gets the truthful 503 of the contract and never a 200 with zeros. The request
 * body is not read.
 */
@RestController
class InternalQueryController {
    private final ProjectionFreshness freshness;
    private final Problems problems;

    InternalQueryController(ProjectionFreshness freshness, Problems problems) {
        this.freshness = freshness;
        this.problems = problems;
    }

    @PostMapping("/internal/v1/statistics/query")
    ResponseEntity<Map<String, Object>> statistics(HttpServletRequest request) {
        return notServed(Projection.STATISTICS, request);
    }

    @PostMapping("/internal/v1/recommendations/query")
    ResponseEntity<Map<String, Object>> recommendations(HttpServletRequest request) {
        return notServed(Projection.RECOMMENDATIONS, request);
    }

    private ResponseEntity<Map<String, Object>> notServed(Projection projection, HttpServletRequest request) {
        return problems.entity(freshness.updatedAt(projection).isEmpty()
                ? Problems.Code.ANALYTICS_NOT_READY : Problems.Code.ANALYTICS_UNAVAILABLE, request);
    }
}
