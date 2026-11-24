package com.libra.streaming.recommendation.internalapi.api;

import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Strict reader of the V2 {@code StatisticsReportQuery} body: a closed object with exactly {@code from}, {@code to}
 * (RFC 3339 full-date strings) and {@code topLimit} (a JSON integer token in 1..50; no fraction, exponent, string or
 * boolean). Failures carry no request text.
 */
final class StatisticsReportRequests {
    private static final Set<String> FIELDS = Set.of("from", "to", "topLimit");

    record Query(LocalDate from, LocalDate to, int topLimit) {}

    private StatisticsReportRequests() {}

    /** @throws QueryBodies.Rejected for a body that is not a valid report query */
    static Query read(String contentType, long contentLength, InputStream body) throws IOException {
        JsonNode root = QueryBodies.readObject(contentType, contentLength, body, StatisticsRequests.MAX_BODY_BYTES,
                FIELDS);
        LocalDate from = StatisticsRequests.date(root.get("from"));
        LocalDate to = StatisticsRequests.date(root.get("to"));
        JsonNode limit = root.get("topLimit");
        // isIntegralNumber is false for 10.0 and 1e1 (parsed as floating point); canConvertToInt rejects huge values.
        if (limit == null || !limit.isIntegralNumber() || !limit.canConvertToInt()
                || limit.intValue() < PlatformStatistics.MIN_TOP || limit.intValue() > PlatformStatistics.MAX_TOP) {
            throw QueryBodies.invalid();
        }
        return new Query(from, to, limit.intValue());
    }
}
