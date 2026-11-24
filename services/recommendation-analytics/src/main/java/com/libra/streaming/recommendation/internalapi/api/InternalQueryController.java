package com.libra.streaming.recommendation.internalapi.api;

import com.libra.streaming.recommendation.recommendations.application.ProfileRecommendations;
import com.libra.streaming.recommendation.recommendations.domain.ListKind;
import com.libra.streaming.recommendation.recommendations.domain.RankedTitle;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analytics-internal operations: V1 statistics (RA04.2) and recommendations (RA05.2), V2 statistics report
 * (RA04.3). Before the first
 * confirmed checkpoint either answers the truthful 503 {@code ANALYTICS_NOT_READY}, never a 200 with zeros or an empty
 * list. No handler writes: {@code updatedAt} is the confirmed checkpoint, which only the background scheduler moves.
 */
@RestController
class InternalQueryController {
    private final PlatformStatistics statistics;
    private final ProfileRecommendations recommendations;
    private final Problems problems;
    private final Clock clock;

    InternalQueryController(PlatformStatistics statistics, ProfileRecommendations recommendations, Problems problems,
            Clock clock) {
        this.statistics = statistics;
        this.recommendations = recommendations;
        this.problems = problems;
        this.clock = clock;
    }

    @PostMapping("/internal/v1/statistics/query")
    ResponseEntity<Map<String, Object>> statistics(HttpServletRequest request) {
        StatisticsWindow window;
        try {
            var query = StatisticsRequests.read(request.getContentType(), request.getContentLengthLong(),
                    request.getInputStream());
            window = StatisticsWindow.requested(query.from(), query.to(), clock.instant());
        } catch (QueryBodies.Rejected rejected) {
            return problems.entity(rejected.code, request);
        } catch (StatisticsWindow.InvalidWindowException | IOException unreadable) {
            // An aborted or truncated upload is the caller's failure, not a server error to alert on.
            return problems.entity(Problems.Code.INVALID_REQUEST, request);
        }
        Optional<PlatformStatistics.Snapshot> snapshot = statistics.snapshot(window);
        if (snapshot.isEmpty()) { return problems.entity(Problems.Code.ANALYTICS_NOT_READY, request); }
        var value = snapshot.get();
        Map<String, Object> body = new LinkedHashMap<>();
        // The request's dates are echoed exactly: Core rejects any other window.
        body.put("from", value.window().from().toString());
        body.put("to", value.window().to().toString());
        body.put("updatedAt", wholeSeconds(value.updatedAt()));
        body.put("qualifiedViews", value.totals().qualifiedViews());
        body.put("uniqueViewers", value.totals().uniqueViewers());
        body.put("acceptedWatchedMs", value.totals().acceptedWatchedMs());
        return ok(body);
    }

    /** analytics-internal.v2 (RA04.3): V1's totals plus one entry per UTC day and the top titles. */
    @PostMapping("/internal/v2/statistics/query")
    ResponseEntity<Map<String, Object>> statisticsReport(HttpServletRequest request) {
        StatisticsWindow window;
        int topLimit;
        try {
            var query = StatisticsReportRequests.read(request.getContentType(), request.getContentLengthLong(),
                    request.getInputStream());
            window = StatisticsWindow.requested(query.from(), query.to(), clock.instant());
            topLimit = query.topLimit();
        } catch (QueryBodies.Rejected rejected) {
            return problems.entity(rejected.code, request);
        } catch (StatisticsWindow.InvalidWindowException | IOException unreadable) {
            return problems.entity(Problems.Code.INVALID_REQUEST, request);
        }
        Optional<PlatformStatistics.Report> report = statistics.report(window, topLimit);
        if (report.isEmpty()) { return problems.entity(Problems.Code.ANALYTICS_NOT_READY, request); }
        var value = report.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", value.window().from().toString());
        body.put("to", value.window().to().toString());
        body.put("updatedAt", wholeSeconds(value.updatedAt()));
        body.put("totals", counts(new LinkedHashMap<>(), value.totals()));
        body.put("days", value.days().stream().map(day -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", day.day().toString());
            return counts(entry, day.totals());
        }).toList());
        body.put("topContent", value.topContent().stream().map(title -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("contentId", title.contentId().toString());
            return counts(entry, title.totals());
        }).toList());
        return ok(body);
    }

    private static Map<String, Object> counts(Map<String, Object> entry, StatisticsTotals totals) {
        entry.put("qualifiedViews", totals.qualifiedViews());
        entry.put("uniqueViewers", totals.uniqueViewers());
        entry.put("acceptedWatchedMs", totals.acceptedWatchedMs());
        return entry;
    }

    @PostMapping("/internal/v1/recommendations/query")
    ResponseEntity<Map<String, Object>> recommendations(HttpServletRequest request) {
        RecommendationRequests.Query query;
        try {
            query = RecommendationRequests.read(request.getContentType(), request.getContentLengthLong(),
                    request.getInputStream());
        } catch (QueryBodies.Rejected rejected) {
            return problems.entity(rejected.code, request);
        } catch (IOException unreadable) {
            return problems.entity(Problems.Code.INVALID_REQUEST, request);
        }
        Optional<ProfileRecommendations.Snapshot> snapshot =
                recommendations.forProfile(query.accountId(), query.profileId(), query.limit());
        if (snapshot.isEmpty()) { return problems.entity(Problems.Code.ANALYTICS_NOT_READY, request); }
        var value = snapshot.get();
        Map<String, Object> body = new LinkedHashMap<>();
        // Core rejects a response whose profileId differs from the request's.
        body.put("profileId", value.profileId().toString());
        body.put("updatedAt", wholeSeconds(value.updatedAt()));
        // Movie and series IDs in rank order, distinct, at most `limit`; V1 carries no reason (RA05.3, /internal/v2).
        List<String> contentIds = value.titles().stream().map(RankedTitle::titleId).map(Object::toString).toList();
        body.put("contentIds", contentIds);
        return ok(body);
    }

    /**
     * analytics-internal.v2 (RA05.3): one of four lists, each title with the rule that placed it as its reason, and the
     * algorithm version. Same readiness and freshness rules as V1.
     */
    @PostMapping("/internal/v2/recommendations/query")
    ResponseEntity<Map<String, Object>> recommendationList(HttpServletRequest request) {
        RecommendationListRequests.Query query;
        try {
            query = RecommendationListRequests.read(request.getContentType(), request.getContentLengthLong(),
                    request.getInputStream());
        } catch (QueryBodies.Rejected rejected) {
            return problems.entity(rejected.code, request);
        } catch (IOException unreadable) {
            return problems.entity(Problems.Code.INVALID_REQUEST, request);
        }
        Optional<ProfileRecommendations.ListSnapshot> snapshot = recommendations.list(query.accountId(),
                query.profileId(), query.list(), query.seed(), query.limit());
        if (snapshot.isEmpty()) { return problems.entity(Problems.Code.ANALYTICS_NOT_READY, request); }
        var value = snapshot.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profileId", value.profileId().toString());
        body.put("list", value.list().name());
        body.put("algorithmVersion", ListKind.ALGORITHM_VERSION);
        body.put("updatedAt", wholeSeconds(value.updatedAt()));
        body.put("items", value.titles().stream().map(title -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contentId", title.titleId().toString());
            item.put("reason", title.rule().name());
            return item;
        }).toList());
        return ok(body);
    }

    /** Whole seconds, rounded down: never later than the confirmed checkpoint. */
    private static String wholeSeconds(Instant updatedAt) {
        return updatedAt.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
