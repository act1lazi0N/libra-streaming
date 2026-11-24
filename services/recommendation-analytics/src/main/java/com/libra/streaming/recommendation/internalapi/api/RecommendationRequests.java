package com.libra.streaming.recommendation.internalapi.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Strict reader of the V1 {@code RecommendationQuery} body: a closed object with exactly {@code accountId} and
 * {@code profileId} (canonical hyphenated UUID strings) and {@code limit} (a JSON integer from 1 to 100). No coercion:
 * {@code "20"}, {@code 20.0} and {@code 2e1} are rejected, as are braced or unhyphenated UUID spellings.
 */
final class RecommendationRequests {
    /** Core's body is about 110 bytes; anything far larger is not a recommendation query. */
    static final int MAX_BODY_BYTES = 1024;
    private static final Set<String> FIELDS = Set.of("accountId", "profileId", "limit");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    record Query(UUID accountId, UUID profileId, int limit) {}

    private RecommendationRequests() {}

    /** @throws QueryBodies.Rejected for a body that is not a valid recommendation query */
    static Query read(String contentType, long contentLength, InputStream body) throws IOException {
        JsonNode root = QueryBodies.readObject(contentType, contentLength, body, MAX_BODY_BYTES, FIELDS);
        return new Query(uuid(root.get("accountId")), uuid(root.get("profileId")), limit(root.get("limit")));
    }

    static UUID uuid(JsonNode node) {
        if (node == null || !node.isString() || !UUID_TEXT.matcher(node.stringValue()).matches()) {
            throw QueryBodies.invalid();
        }
        return UUID.fromString(node.stringValue());
    }

    static int limit(JsonNode node) {
        // isInt: an integral JSON number token within int range; floats and exponent forms are not integral tokens.
        if (node == null || !node.isInt() || node.intValue() < 1 || node.intValue() > 100) {
            throw QueryBodies.invalid();
        }
        return node.intValue();
    }
}
