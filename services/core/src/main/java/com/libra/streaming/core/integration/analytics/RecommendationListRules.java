package com.libra.streaming.core.integration.analytics;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;

/**
 * The analytics-internal.v2 consumer checks of a recommendation list (RA05.3). Bean Validation has checked the
 * shape; these are the cross-field rules. A list that breaks any of them is an INVALID_RESPONSE: Core then falls back
 * rather than show a reason that may not be the rule that chose the title.
 */
final class RecommendationListRules {
    private static final Map<ListKind, Set<Reason>> ALLOWED = Map.of(
            ListKind.PERSONAL, EnumSet.of(Reason.GENRE_INTEREST, Reason.POPULAR, Reason.NEWLY_PUBLISHED),
            ListKind.SIMILAR, EnumSet.of(Reason.SIMILAR_GENRES),
            ListKind.POPULAR, EnumSet.of(Reason.POPULAR),
            ListKind.NEW, EnumSet.of(Reason.NEWLY_PUBLISHED));

    private RecommendationListRules() {}

    static boolean valid(RecommendationListQuery query, RecommendationListResponse list, Instant now) {
        if (!query.profileId().equals(list.profileId()) || query.list() != list.list()
                || list.updatedAt().isAfter(now) || list.items().size() > query.limit()) {
            return false;
        }
        Set<Reason> allowed = ALLOWED.get(query.list());
        Set<UUID> seen = new HashSet<>();
        for (RecommendedTitle item : list.items()) {
            if (!seen.add(item.contentId()) || !allowed.contains(item.reason())
                    || item.contentId().equals(query.seedContentId())) {
                return false;
            }
        }
        // PERSONAL fills tier by tier: once a later tier appears, an earlier one may not come back.
        if (query.list() == ListKind.PERSONAL) {
            int tier = 0;
            for (RecommendedTitle item : list.items()) {
                int current = switch (item.reason()) {
                    case GENRE_INTEREST -> 0;
                    case POPULAR -> 1;
                    default -> 2;
                };
                if (current < tier) { return false; }
                tier = current;
            }
        }
        return true;
    }
}
