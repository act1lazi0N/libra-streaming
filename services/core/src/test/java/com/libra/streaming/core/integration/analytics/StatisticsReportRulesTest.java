package com.libra.streaming.core.integration.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.libra.streaming.core.integration.analytics.AnalyticsModels.*;
import static org.assertj.core.api.Assertions.*;

class StatisticsReportRulesTest {
    private static final Instant NOW = Instant.parse("2026-09-07T10:15:30Z");
    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final UUID A = UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff");
    private static final UUID B = UUID.fromString("80000000-0000-4000-8000-000000000000");

    private static final class Report {
        LocalDate from = FROM;
        LocalDate to = FROM.plusDays(6);
        Instant updatedAt = NOW.minusSeconds(60);
        Counts totals = new Counts(5L, 3L, 240000L);
        List<DayCounts> days = new ArrayList<>();
        List<ContentCounts> top = new ArrayList<>(List.of(entry(A, 3, 2, 150000), entry(B, 2, 2, 90000)));
        Report() {
            for (int i = 0; i < 7; i++) {
                days.add(new DayCounts(FROM.plusDays(i), i == 1 ? 2L : i == 4 ? 3L : 0L,
                        i == 1 || i == 4 ? 2L : 0L, i == 1 ? 90000L : i == 4 ? 150000L : 0L));
            }
        }
        boolean valid() {
            return StatisticsReportRules.valid(new StatisticsReportQuery(FROM, FROM.plusDays(6), 10),
                    new StatisticsReportResponse(from, to, updatedAt, totals, days, top), NOW);
        }
    }
    private static ContentCounts entry(UUID id, long views, long viewers, long watched) {
        return new ContentCounts(id, views, viewers, watched);
    }

    @Test void acceptsBase() {
        assertThat(new Report().valid()).isTrue();
    }

    @Test void sharedResponseCorpusMatchesConsumerRules() {
        var mapper = JsonMapper.builder().addModule(StrictContractJson.module()).build();
        var values = new java.util.HashMap<>(Map.of("from", FROM.toString(), "to", FROM.plusDays(6).toString(),
                "updatedAt", NOW.minusSeconds(60).toString(), "staleUpdatedAt", NOW.minusSeconds(901).toString(),
                "futureUpdatedAt", NOW.plusSeconds(60).toString(), "dayBefore", FROM.minusDays(1).toString(),
                "movieId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "seriesId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "otherId", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
        for (int i = 1; i <= 7; i++) { values.put("day" + i, FROM.plusDays(i - 1).toString()); }
        var fixtures = AnalyticsContractFixtures.load(mapper, "analytics-v2").stream()
                .filter(f -> "#/components/schemas/StatisticsReport".equals(f.text("pointer")))
                .filter(f -> !"schema-invalid".equals(f.text("expect"))).toList();
        assertThat(fixtures).hasSize(21);
        for (var fixture : fixtures) {
            var report = mapper.readValue(fixture.instantiate(values), StatisticsReportResponse.class);
            assertThat(StatisticsReportRules.valid(new StatisticsReportQuery(FROM, FROM.plusDays(6), 10), report, NOW))
                    .as(fixture.id()).isEqualTo("valid".equals(fixture.text("expect")));
        }
    }

    @Test void rejectsFromMismatch() {
        var r = new Report(); r.from = FROM.minusDays(1); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsToMismatch() {
        var r = new Report(); r.to = FROM.plusDays(5); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsFutureCheckpoint() {
        var r = new Report(); r.updatedAt = NOW.plusSeconds(1); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTotalsViewersOverViews() {
        var r = new Report(); r.totals = new Counts(5L, 6L, 240000L); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsMissingDay() {
        var r = new Report(); r.days.removeLast(); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsWrongDay() {
        var r = new Report(); r.days.set(0, new DayCounts(FROM.minusDays(1), 0L, 0L, 0L)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsDayOrder() {
        var r = new Report(); Collections.swap(r.days, 0, 1); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsDayViewersOverViews() {
        var r = new Report(); r.days.set(1, new DayCounts(FROM.plusDays(1), 2L, 3L, 90000L)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsDayViewsSum() {
        var r = new Report(); r.totals = new Counts(6L, 3L, 240000L); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsDayWatchedSum() {
        var r = new Report(); r.totals = new Counts(5L, 3L, 240001L); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsViewersBelowBusiestDay() {
        var r = new Report(); r.totals = new Counts(5L, 1L, 240000L); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsViewersAboveDaySum() {
        var r = new Report(); r.totals = new Counts(5L, 5L, 240000L); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopOverLimit() {
        var r = new Report();
        for (int i = 1; i <= 9; i++) { r.top.add(entry(new UUID(0, i), 0, 0, 1)); }
        r.days.set(6, new DayCounts(FROM.plusDays(6), 0L, 0L, 9L));
        r.totals = new Counts(5L, 3L, 240009L); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsDuplicateContent() {
        var r = new Report(); r.top.set(1, entry(A, 2, 2, 90000)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsZeroEntry() {
        var r = new Report(); r.top.add(entry(new UUID(0, 1), 0, 0, 0)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopViewsOrder() {
        var r = new Report(); Collections.swap(r.top, 0, 1); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopViewersOrder() {
        var r = new Report(); r.top = List.of(entry(A, 2, 1, 90000), entry(B, 2, 2, 90000)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopWatchedOrder() {
        var r = new Report(); r.top = List.of(entry(A, 2, 2, 80000), entry(B, 2, 2, 90000)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsIdTextOrder() {
        var r = new Report(); r.top = List.of(entry(B, 2, 2, 90000), entry(A, 2, 2, 90000)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopViewersOverViews() {
        var r = new Report(); r.top.set(0, entry(A, 3, 4, 150000)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopViewsSum() {
        var r = new Report(); r.top.set(1, entry(B, 3, 1, 90000)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsTopWatchedSum() {
        var r = new Report(); r.top.set(1, entry(B, 2, 2, 90001)); assertThat(r.valid()).isFalse();
    }

    @Test void rejectsOverflowWithoutThrowing() {
        var r = new Report();
        r.days.set(1, new DayCounts(FROM.plusDays(1), Long.MAX_VALUE, 2L, 90000L));
        r.days.set(4, new DayCounts(FROM.plusDays(4), Long.MAX_VALUE, 2L, 150000L));
        assertThat(r.valid()).isFalse();
    }

    @Test void acceptsZeros() {
        var r = new Report(); r.totals = new Counts(0L, 0L, 0L); r.top.clear();
        r.days.replaceAll(d -> new DayCounts(d.day(), 0L, 0L, 0L)); assertThat(r.valid()).isTrue();
    }

    @Test void acceptsWatchOnlyTitle() {
        var r = new Report(); r.top.add(entry(new UUID(0, 1), 0, 0, 1000));
        r.days.set(6, new DayCounts(FROM.plusDays(6), 0L, 0L, 1000L));
        r.totals = new Counts(5L, 3L, 241000L); assertThat(r.valid()).isTrue();
    }

    @Test void acceptsIdTextTieBreak() {
        var r = new Report(); r.top = List.of(entry(A, 2, 2, 90000), entry(B, 2, 2, 90000));
        assertThat(r.valid()).isTrue();
        assertThat(StatisticsReportRules.ranksBefore(r.top.get(0), r.top.get(1))).isTrue();
        assertThat(StatisticsReportRules.ranksBefore(r.top.get(1), r.top.get(0))).isFalse();
    }
}
