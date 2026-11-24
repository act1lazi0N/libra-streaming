package com.libra.streaming.recommendation.internalapi.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Strict reader of the V1 {@code StatisticsQuery} body: a closed object with exactly {@code from} and {@code to}, each
 * an RFC 3339 full-date string. No coercion, no duplicate keys, no trailing content. Failures carry no request text.
 */
final class StatisticsRequests {
    /** Core's body is about 40 bytes; anything far larger is not a statistics query. */
    static final int MAX_BODY_BYTES = 1024;
    private static final Set<String> FIELDS = Set.of("from", "to");
    private static final Pattern DATE_TEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    record Query(LocalDate from, LocalDate to) {}

    private StatisticsRequests() {}

    /** @throws QueryBodies.Rejected for a body that is not a valid statistics query */
    static Query read(String contentType, long contentLength, InputStream body) throws IOException {
        JsonNode root = QueryBodies.readObject(contentType, contentLength, body, MAX_BODY_BYTES, FIELDS);
        return new Query(date(root.get("from")), date(root.get("to")));
    }

    /** An RFC 3339 full-date string; shared with the V2 report query. */
    static LocalDate date(JsonNode node) {
        if (node == null || !node.isString() || !DATE_TEXT.matcher(node.stringValue()).matches()) {
            throw QueryBodies.invalid();
        }
        try { return LocalDate.parse(node.stringValue()); }
        catch (DateTimeException impossible) { throw QueryBodies.invalid(); }
    }
}
