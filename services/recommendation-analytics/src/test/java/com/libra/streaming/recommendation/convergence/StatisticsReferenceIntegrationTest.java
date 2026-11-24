package com.libra.streaming.recommendation.convergence;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures.Event;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.statistics.domain.ContentTotals;
import com.libra.streaming.recommendation.statistics.domain.DayTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RA04.4: the V1 snapshot and the V2 report match an independent reference computed from the raw events, for a
 * dataset holding every edge the statistics rules name (30-second threshold, a session across UTC midnight, a late
 * lower-sequence event that moves a view to an earlier day, several profiles of one account, viewers on several days,
 * a deleted profile, episodes with known and unknown placement). The events go through the real intake in shuffled
 * orders with duplicates. The reference below folds the JSON only and never calls projection code.
 */
@Testcontainers
@SpringBootTest(properties = {"libra.analytics.consumers.enabled=false", "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"})
class StatisticsReferenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_reference").withUsername("analytics_reference")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final long SEED = 20261123L;
    private static final LocalDate D0 = LocalDate.parse("2026-09-07");
    private static final Instant CHECKPOINT = Instant.parse("2026-09-12T00:00:00Z");
    private static final int THRESHOLD_MS = 30_000;

    @Autowired EventIntake intake;
    @Autowired PlatformStatistics statistics;
    @Autowired JdbcTemplate jdbc;

    private Dataset dataset;

    @BeforeEach void build() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, catalog_nodes, profiles, playback_facts, "
                + "playback_sessions, processed_events");
        jdbc.update("UPDATE input_checkpoints SET confirmed_at = ?", Timestamp.from(CHECKPOINT));
        dataset = Dataset.create();
    }

    @Test void everyWindowMatchesTheReferenceForSeveralDeliveryOrders() {
        for (int run = 0; run < 6; run++) {
            clear();
            var random = new Random(SEED + run);
            var stream = new ArrayList<Event>();
            dataset.events.forEach(event -> { for (int n = 1 + random.nextInt(2); n > 0; n--) { stream.add(event); } });
            Collections.shuffle(stream, random);
            stream.forEach(this::apply);
            assertMatchesReference("delivery order " + run);
        }
    }

    @Test void lateLowerSequenceEventMovesTheViewToItsEarlierDay() {
        Event early = dataset.named.get("late-sequence-1");
        dataset.events.stream().filter(event -> event != early).forEach(this::apply);
        var lateDay = D0.plusDays(2);
        var earlyDay = D0.plusDays(1);
        // Without sequence 1, sequence 2 (00:00:20 on day 2, total 40 s) is the qualifying event.
        long day2Before = day(lateDay).totals().qualifiedViews();
        long day1Before = day(earlyDay).totals().qualifiedViews();
        apply(early);
        assertThat(day(lateDay).totals().qualifiedViews()).isEqualTo(day2Before - 1);
        assertThat(day(earlyDay).totals().qualifiedViews()).isEqualTo(day1Before + 1);
        assertMatchesReference("late lower sequence delivered last");
    }

    @Test void thresholdAndUniqueViewerEdgesHoldExactly() {
        dataset.events.forEach(this::apply);
        var d0 = new StatisticsWindow(D0, D0);
        // 29,999 ms never qualifies; 30,000 ms does. Two profiles of one account are one viewer.
        assertThat(dataset.reference(d0).totals().qualifiedViews())
                .isEqualTo(statistics.snapshot(d0).orElseThrow().totals().qualifiedViews());
        var whole = new StatisticsWindow(D0, D0.plusDays(3));
        var report = statistics.report(whole, 50).orElseThrow();
        long perDayViewers = report.days().stream().mapToLong(day -> day.totals().uniqueViewers()).sum();
        assertThat(report.totals().uniqueViewers()).isLessThan(perDayViewers);
        assertThat(dataset.sessionQualified("threshold-29999")).isFalse();
        assertThat(dataset.sessionQualified("threshold-30000")).isTrue();
    }

    private DayTotals day(LocalDate day) {
        return statistics.report(new StatisticsWindow(day, day), 1).orElseThrow().days().getFirst();
    }

    private void assertMatchesReference(String label) {
        List<StatisticsWindow> windows = new ArrayList<>();
        for (int d = -1; d <= 4; d++) { windows.add(new StatisticsWindow(D0.plusDays(d), D0.plusDays(d))); }
        windows.add(new StatisticsWindow(D0, D0.plusDays(3)));
        windows.add(new StatisticsWindow(D0.plusDays(1), D0.plusDays(2)));
        windows.add(new StatisticsWindow(D0.minusDays(2), D0.plusDays(5)));
        for (var window : windows) {
            var expected = dataset.reference(window);
            for (int top : new int[] {1, 3, 50}) {
                var report = statistics.report(window, top).orElseThrow();
                String where = label + ", window " + window + ", top " + top;
                assertThat(report.updatedAt()).as(where).isEqualTo(CHECKPOINT);
                assertThat(report.totals()).as(where).isEqualTo(expected.totals());
                assertThat(report.days()).as(where).isEqualTo(expected.days());
                assertThat(report.topContent()).as(where)
                        .isEqualTo(expected.top().subList(0, Math.min(top, expected.top().size())));
            }
            assertThat(statistics.snapshot(window).orElseThrow().totals()).as(label + " V1 " + window)
                    .isEqualTo(expected.totals());
        }
    }

    private void clear() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, catalog_nodes, profiles, playback_facts, "
                + "playback_sessions, processed_events");
    }

    private void apply(Event event) { intake.accept(event.topic(), event.key(), event.json()); }

    static UUID id(String name) { return UUID.nameUUIDFromBytes((SEED + ":" + name).getBytes(StandardCharsets.UTF_8)); }

    record Expected(StatisticsTotals totals, List<DayTotals> days, List<ContentTotals> top) {}

    /** One accepted heartbeat as the reference sees it. */
    record Beat(UUID session, UUID account, UUID item, long sequence, long increment, long total, Instant at) {}

    static final class Dataset {
        final List<Event> events = new ArrayList<>();
        final Map<String, Event> named = new HashMap<>();
        final List<Beat> beats = new ArrayList<>();
        final Map<UUID, UUID> titleOf = new HashMap<>();
        final Map<String, UUID> sessions = new HashMap<>();
        private final IngestionFixtures fixtures = new IngestionFixtures();
        private int counter;

        static Dataset create() {
            var data = new Dataset();
            data.catalog();
            data.profiles();
            data.edges();
            data.random();
            for (Event event : data.events) {
                event.root().put("eventId", id("event-" + data.counter).toString())
                        .put("correlationId", id("correlation-" + data.counter++).toString());
            }
            return data;
        }

        private void catalog() {
            UUID m1 = id("M1"), m2 = id("M2"), series = id("S"), season = id("SS"), e1 = id("E1"), e2 = id("E2");
            UUID orphan = id("E3"), missingSeason = id("SS-missing");
            Instant at = D0.minusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC);
            events.add(fixtures.catalog(m1, "MOVIE", null, 1, "CatalogPublished", at));
            events.add(fixtures.catalog(m2, "MOVIE", null, 1, "CatalogPublished", at.plusSeconds(1)));
            events.add(fixtures.catalog(series, "SERIES", null, 1, "CatalogPublished", at.plusSeconds(2)));
            events.add(fixtures.catalog(season, "SEASON", series, 1, "CatalogPublished", at.plusSeconds(3)));
            events.add(fixtures.catalog(e1, "EPISODE", season, 1, "CatalogPublished", at.plusSeconds(4)));
            events.add(fixtures.catalog(e2, "EPISODE", season, 1, "CatalogPublished", at.plusSeconds(5)));
            // An episode whose season Analytics never hears of counts under its own ID.
            events.add(fixtures.catalog(orphan, "EPISODE", missingSeason, 1, "CatalogPublished", at.plusSeconds(6)));
            titleOf.put(m1, m1); titleOf.put(m2, m2); titleOf.put(e1, series); titleOf.put(e2, series);
            titleOf.put(orphan, orphan);
            // A season is known but its series is not: its episode also counts under its own ID.
            UUID lostSeason = id("SS-lost"), stranded = id("E4");
            events.add(fixtures.catalog(lostSeason, "SEASON", id("S-missing"), 1, "CatalogPublished", at.plusSeconds(7)));
            events.add(fixtures.catalog(stranded, "EPISODE", lostSeason, 1, "CatalogPublished", at.plusSeconds(8)));
            titleOf.put(stranded, stranded);
            // "U" has no catalog event at all.
            titleOf.put(id("U"), id("U"));
        }

        private void profiles() {
            for (int p = 0; p <= 5; p++) {
                var event = fixtures.template(InputTopic.PROFILES, "profile-created");
                event.root().put("aggregateId", profile(p).toString()).put("aggregateVersion", 1)
                        .put("occurredAt", D0.minusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(p).toString());
                event.payload().put("profileId", profile(p).toString()).put("accountId", account(p).toString());
                events.add(event);
            }
            // Profile 4 is deleted in the middle of the data: aggregates keep its views.
            var deleted = fixtures.template(InputTopic.PROFILES, "profile-deleted");
            deleted.root().put("aggregateId", profile(4).toString()).put("aggregateVersion", 2)
                    .put("occurredAt", D0.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC).toString());
            deleted.payload().put("profileId", profile(4).toString()).put("accountId", account(4).toString());
            events.add(deleted);
        }

        static UUID profile(int p) { return id("P" + p); }

        /** Profiles 0-1 belong to account A0, 2 to A1, 3-4 to A2, 5 to A3. */
        static UUID account(int p) { return id("A" + List.of(0, 0, 1, 2, 2, 3).get(p)); }

        private void edges() {
            Instant d0 = D0.atTime(10, 0).toInstant(ZoneOffset.UTC);
            session("threshold-29999", 2, id("M1"), d0, new long[] {10_000, 10_000, 9_999}, 10);
            session("threshold-30000", 2, id("M1"), d0.plusSeconds(600), new long[] {10_000, 10_000, 10_000}, 10);
            // Two profiles of account A0 qualify on day 0: two views, one viewer.
            session("profile-0", 0, id("M2"), d0.plusSeconds(1200), new long[] {30_000}, 10);
            session("profile-1", 1, id("M2"), d0.plusSeconds(1300), new long[] {15_000, 15_000}, 10);
            // A session across midnight: 20 s on day 1, 10 s and the view on day 2.
            Instant beforeMidnight = D0.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(15);
            session("midnight", 3, id("E1"), beforeMidnight, new long[] {10_000, 10_000, 10_000}, 10);
            // Qualifies just before midnight; its last heartbeat falls on the next day.
            session("midnight-early", 5, id("E2"), beforeMidnight.minusSeconds(20), new long[] {15_000, 15_000, 10_000}, 15);
            // Sequence 1 alone reaches 30 s at 23:59:50 on day 1; sequence 2 is at 00:00:20 on day 2.
            Instant late = D0.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(10);
            List<Event> lateSession = session("late-sequence", 2, id("U"), late, new long[] {30_000, 10_000}, 30);
            named.put("late-sequence-1", lateSession.getFirst());
            // Account A1 (profile 2) is a viewer on days 0 and 2: one viewer over the range, two by day.
            session("unique-day2", 2, id("E3"), D0.plusDays(2).atTime(9, 0).toInstant(ZoneOffset.UTC),
                    new long[] {30_000}, 10);
            // Exactly 00:00:00Z belongs to the new day only: both its watched time and its view land on day 3, and a
            // window ending on day 2 must not count them.
            session("exact-midnight", 1, id("M2"), D0.plusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC),
                    new long[] {30_000}, 10);
            session("stranded-episode", 5, id("E4"), D0.plusDays(1).atTime(8, 0).toInstant(ZoneOffset.UTC),
                    new long[] {20_000, 15_000}, 10);
            // A deleted profile's session after its deletion still counts.
            session("deleted-profile", 4, id("M1"), D0.plusDays(1).atTime(13, 0).toInstant(ZoneOffset.UTC),
                    new long[] {20_000, 0, 20_000}, 10);
        }

        private void random() {
            var random = new Random(SEED);
            List<UUID> items = List.of(id("M1"), id("M2"), id("E1"), id("E2"), id("E3"), id("E4"), id("U"));
            long[] steps = {0, 5_000, 10_000, 15_000, 20_000, 30_000};
            for (int s = 0; s < 40; s++) {
                int beats = 1 + random.nextInt(6);
                long[] increments = new long[beats];
                for (int b = 0; b < beats; b++) { increments[b] = steps[random.nextInt(steps.length)]; }
                Instant start = D0.atStartOfDay().toInstant(ZoneOffset.UTC)
                        .plusSeconds(random.nextLong(4L * 86_400 - 400));
                session("random-" + s, random.nextInt(6), items.get(random.nextInt(items.size())), start, increments,
                        5 + random.nextInt(40));
            }
        }

        private List<Event> session(String name, int profile, UUID item, Instant start, long[] increments, int gapSeconds) {
            UUID session = id("session-" + name);
            sessions.put(name, session);
            List<Event> created = new ArrayList<>();
            long total = 0;
            for (int q = 1; q <= increments.length; q++) {
                total += increments[q - 1];
                Instant at = start.plusSeconds((long) (q - 1) * gapSeconds);
                var event = fixtures.playback(q, increments[q - 1], total);
                event.root().put("aggregateId", session.toString()).put("occurredAt", at.toString());
                event.payload().put("sessionId", session.toString()).put("profileId", profile(profile).toString())
                        .put("accountId", account(profile).toString()).put("contentId", item.toString())
                        .put("state", increments[q - 1] == 0 ? "SEEKING" : "PLAYING");
                events.add(event);
                created.add(event);
                beats.add(new Beat(session, account(profile), item, q, increments[q - 1], total, at));
            }
            return created;
        }

        boolean sessionQualified(String name) {
            return beats.stream().anyMatch(beat -> beat.session().equals(sessions.get(name)) && beat.total() >= THRESHOLD_MS);
        }

        /** The statistics rules applied to the raw heartbeats (RA01.1, "Statistics semantics"). */
        Expected reference(StatisticsWindow window) {
            // A session's view is on the UTC date of its lowest-sequence heartbeat whose Core total reaches 30 s.
            Map<UUID, Beat> qualifying = new HashMap<>();
            beats.stream().filter(beat -> beat.total() >= THRESHOLD_MS)
                    .forEach(beat -> qualifying.merge(beat.session(), beat,
                            (a, b) -> a.sequence() <= b.sequence() ? a : b));
            TreeMap<LocalDate, long[]> perDay = new TreeMap<>();
            Map<LocalDate, Set<UUID>> dayViewers = new HashMap<>();
            Set<UUID> viewers = new HashSet<>();
            Map<UUID, long[]> perTitle = new HashMap<>();
            Map<UUID, Set<UUID>> titleViewers = new HashMap<>();
            for (var day = window.from(); !day.isAfter(window.to()); day = day.plusDays(1)) {
                perDay.put(day, new long[2]);
                dayViewers.put(day, new HashSet<>());
            }
            for (Beat view : qualifying.values()) {
                LocalDate day = LocalDate.ofInstant(view.at(), ZoneOffset.UTC);
                if (!perDay.containsKey(day)) { continue; }
                perDay.get(day)[0]++;
                dayViewers.get(day).add(view.account());
                viewers.add(view.account());
                UUID title = titleOf.get(view.item());
                perTitle.computeIfAbsent(title, ignored -> new long[2])[0]++;
                titleViewers.computeIfAbsent(title, ignored -> new HashSet<>()).add(view.account());
            }
            for (Beat beat : beats) {
                LocalDate day = LocalDate.ofInstant(beat.at(), ZoneOffset.UTC);
                if (!perDay.containsKey(day)) { continue; }
                perDay.get(day)[1] += beat.increment();
                perTitle.computeIfAbsent(titleOf.get(beat.item()), ignored -> new long[2])[1] += beat.increment();
            }
            List<DayTotals> days = new ArrayList<>();
            long views = 0;
            long watched = 0;
            for (var entry : perDay.entrySet()) {
                long[] counts = entry.getValue();
                views += counts[0];
                watched += counts[1];
                days.add(new DayTotals(entry.getKey(),
                        new StatisticsTotals(counts[0], dayViewers.get(entry.getKey()).size(), counts[1])));
            }
            List<ContentTotals> top = new ArrayList<>();
            perTitle.forEach((title, counts) -> {
                if (counts[0] > 0 || counts[1] > 0) {
                    top.add(new ContentTotals(title, new StatisticsTotals(counts[0],
                            titleViewers.getOrDefault(title, Set.of()).size(), counts[1])));
                }
            });
            top.sort(Comparator.comparingLong((ContentTotals c) -> -c.totals().qualifiedViews())
                    .thenComparingLong(c -> -c.totals().uniqueViewers())
                    .thenComparingLong(c -> -c.totals().acceptedWatchedMs())
                    .thenComparing(c -> c.contentId().toString()));
            return new Expected(new StatisticsTotals(views, viewers.size(), watched), days, top);
        }
    }
}
