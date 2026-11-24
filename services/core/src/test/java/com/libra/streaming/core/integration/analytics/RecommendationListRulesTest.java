package com.libra.streaming.core.integration.analytics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationListRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PROFILE = UUID.fromString("4a4a4a4a-4444-4444-8444-444444444444");
    private static final UUID SEED = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID A = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID B = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID C = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    private static RecommendationListQuery query(ListKind list) {
        return new RecommendationListQuery(ACCOUNT, PROFILE, list, list == ListKind.SIMILAR ? SEED : null, 100);
    }

    private static RecommendationListResponse response(ListKind list, RecommendedTitle... items) {
        return new RecommendationListResponse(PROFILE, list, "rules-v1", NOW.minusSeconds(60), List.of(items));
    }

    private static RecommendedTitle item(UUID id, Reason reason) { return new RecommendedTitle(id, reason); }

    private static boolean valid(ListKind list, RecommendationListResponse response) {
        return RecommendationListRules.valid(query(list), response, NOW);
    }

    @Test void personalTiersInOrderAreValid() {
        assertThat(valid(ListKind.PERSONAL, response(ListKind.PERSONAL, item(A, Reason.GENRE_INTEREST),
                item(B, Reason.POPULAR), item(C, Reason.NEWLY_PUBLISHED)))).isTrue();
        assertThat(valid(ListKind.PERSONAL, response(ListKind.PERSONAL, item(A, Reason.POPULAR),
                item(B, Reason.NEWLY_PUBLISHED)))).isTrue();
    }

    @Test void personalTierMayNotComeBack() {
        assertThat(valid(ListKind.PERSONAL, response(ListKind.PERSONAL, item(A, Reason.POPULAR),
                item(B, Reason.GENRE_INTEREST)))).isFalse();
        assertThat(valid(ListKind.PERSONAL, response(ListKind.PERSONAL, item(A, Reason.NEWLY_PUBLISHED),
                item(B, Reason.POPULAR)))).isFalse();
    }

    @ParameterizedTest @EnumSource(ListKind.class)
    void emptyListIsValid(ListKind list) {
        assertThat(valid(list, response(list))).isTrue();
    }

    @Test void eachListAcceptsOnlyItsOwnReasons() {
        assertThat(valid(ListKind.SIMILAR, response(ListKind.SIMILAR, item(A, Reason.SIMILAR_GENRES)))).isTrue();
        assertThat(valid(ListKind.POPULAR, response(ListKind.POPULAR, item(A, Reason.POPULAR)))).isTrue();
        assertThat(valid(ListKind.NEW, response(ListKind.NEW, item(A, Reason.NEWLY_PUBLISHED)))).isTrue();
        for (Reason other : List.of(Reason.GENRE_INTEREST, Reason.POPULAR, Reason.NEWLY_PUBLISHED)) {
            assertThat(valid(ListKind.SIMILAR, response(ListKind.SIMILAR, item(A, other)))).as(other.name()).isFalse();
        }
        assertThat(valid(ListKind.POPULAR, response(ListKind.POPULAR, item(A, Reason.NEWLY_PUBLISHED)))).isFalse();
        assertThat(valid(ListKind.NEW, response(ListKind.NEW, item(A, Reason.POPULAR)))).isFalse();
        assertThat(valid(ListKind.PERSONAL, response(ListKind.PERSONAL, item(A, Reason.SIMILAR_GENRES)))).isFalse();
    }

    @Test void similarNeverContainsItsSeed() {
        assertThat(valid(ListKind.SIMILAR, response(ListKind.SIMILAR, item(A, Reason.SIMILAR_GENRES),
                item(SEED, Reason.SIMILAR_GENRES)))).isFalse();
    }

    @Test void duplicatesAreInvalid() {
        assertThat(valid(ListKind.NEW, response(ListKind.NEW, item(A, Reason.NEWLY_PUBLISHED),
                item(A, Reason.NEWLY_PUBLISHED)))).isFalse();
    }

    @Test void echoesAndClockAreChecked() {
        var other = new RecommendationListResponse(UUID.randomUUID(), ListKind.NEW, "rules-v1", NOW.minusSeconds(60), List.of());
        assertThat(valid(ListKind.NEW, other)).isFalse();
        assertThat(valid(ListKind.NEW, response(ListKind.POPULAR))).isFalse();
        var future = new RecommendationListResponse(PROFILE, ListKind.NEW, "rules-v1", NOW.plusSeconds(1), List.of());
        assertThat(valid(ListKind.NEW, future)).isFalse();
        var exact = new RecommendationListResponse(PROFILE, ListKind.NEW, "rules-v1", NOW, List.of());
        assertThat(valid(ListKind.NEW, exact)).isTrue();
    }

    @Test void moreItemsThanAskedAreInvalid() {
        var items = new ArrayList<RecommendedTitle>();
        for (int i = 0; i < 3; i++) { items.add(item(UUID.randomUUID(), Reason.NEWLY_PUBLISHED)); }
        var small = new RecommendationListQuery(ACCOUNT, PROFILE, ListKind.NEW, null, 2);
        var answer = new RecommendationListResponse(PROFILE, ListKind.NEW, "rules-v1", NOW, items);
        assertThat(RecommendationListRules.valid(small, answer, NOW)).isFalse();
        assertThat(RecommendationListRules.valid(new RecommendationListQuery(ACCOUNT, PROFILE, ListKind.NEW, null, 3),
                answer, NOW)).isTrue();
    }
}
