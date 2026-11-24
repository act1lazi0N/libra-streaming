package com.libra.streaming.recommendation.internalapi.api;

import com.libra.streaming.recommendation.recommendations.domain.ListKind;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Strict reader of the V2 {@code RecommendationListQuery} body (RA05.3): a closed object with exactly
 * {@code accountId}, {@code profileId} (canonical UUID text), {@code list} (one of the four list names, exact case),
 * {@code seedContentId} (UUID text for SIMILAR, JSON null otherwise) and {@code limit} (a JSON integer 1..100).
 * Failures carry no request text.
 */
final class RecommendationListRequests {
    private static final Set<String> FIELDS = Set.of("accountId", "profileId", "list", "seedContentId", "limit");

    record Query(UUID accountId, UUID profileId, ListKind list, UUID seed, int limit) {}

    private RecommendationListRequests() {}

    /** @throws QueryBodies.Rejected for a body that is not a valid list query */
    static Query read(String contentType, long contentLength, InputStream body) throws IOException {
        JsonNode root = QueryBodies.readObject(contentType, contentLength, body,
                RecommendationRequests.MAX_BODY_BYTES, FIELDS);
        ListKind list = list(root.get("list"));
        JsonNode seedNode = root.get("seedContentId");
        UUID seed;
        if (list == ListKind.SIMILAR) {
            seed = RecommendationRequests.uuid(seedNode);
        } else if (seedNode != null && seedNode.isNull()) {
            seed = null;
        } else {
            throw QueryBodies.invalid();
        }
        return new Query(RecommendationRequests.uuid(root.get("accountId")),
                RecommendationRequests.uuid(root.get("profileId")), list, seed,
                RecommendationRequests.limit(root.get("limit")));
    }

    private static ListKind list(JsonNode node) {
        if (node == null || !node.isString()) { throw QueryBodies.invalid(); }
        for (ListKind kind : ListKind.values()) {
            if (kind.name().equals(node.stringValue())) { return kind; }
        }
        throw QueryBodies.invalid();
    }
}
