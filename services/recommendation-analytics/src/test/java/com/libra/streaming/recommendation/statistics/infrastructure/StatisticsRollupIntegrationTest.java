package com.libra.streaming.recommendation.statistics.infrastructure;

import com.libra.streaming.recommendation.bootstrap.application.Bootstrap;
import com.libra.streaming.recommendation.bootstrap.application.BootstrapProgress;
import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots;
import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import com.libra.streaming.recommendation.bootstrap.domain.SnapshotPages;
import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.CoreEventDecoder;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures.Event;
import com.libra.streaming.recommendation.playback.application.PlaybackStore;
import com.libra.streaming.recommendation.playback.domain.Qualification;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import com.libra.streaming.recommendation.statistics.application.PlatformStatistics;
import com.libra.streaming.recommendation.statistics.application.StatisticsStore;
import com.libra.streaming.recommendation.statistics.domain.DayTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.libra.streaming.recommendation.ingestion.application.EventIntake.Outcome.APPLIED;
import static com.libra.streaming.recommendation.ingestion.application.EventIntake.Outcome.DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL 18, decoder, intake, transactions and rollups; no Kafka or Core HTTP server. */
@Testcontainers
@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class StatisticsRollupIntegrationTest {
    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 7);
    private static final LocalDate DAY_2 = DAY_1.plusDays(1);
    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-09-07T23:59:50Z");
    private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-09-08T00:00:10Z");
    private static final String STORED_ROWS = """
            SELECT day, account_id, qualified_views, accepted_watched_ms
            FROM statistics_account_days ORDER BY day, account_id
            """;
    // V6's backfill SELECT, kept independent of DayContribution and the incremental writer.
    private static final String RECOMPUTED_ROWS = """
            SELECT day, account_id, sum(views) AS qualified_views, sum(watched_ms) AS accepted_watched_ms
            FROM (
                SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, account_id,
                    0 AS views, accepted_watched_ms AS watched_ms
                FROM playback_facts
                UNION ALL
                SELECT (qualified_at AT TIME ZONE 'UTC')::date, account_id, 1, 0
                FROM playback_sessions WHERE qualified_at IS NOT NULL
            ) contributions
            GROUP BY day, account_id
            HAVING sum(views) > 0 OR sum(watched_ms) > 0
            ORDER BY day, account_id
            """;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_statistics").withUsername("analytics_statistics")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired EventIntake intake;
    @Autowired CoreEventDecoder decoder;
    @Autowired PlaybackStore playback;
    @Autowired Transactions transactions;
    @Autowired PlatformStatistics statistics;
    @Autowired StatisticsStore statisticsStore;
    @Autowired BootstrapProgress bootstrapProgress;
    @Autowired ProfileProjection profiles;
    @Autowired CatalogProjection catalog;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    private IngestionFixtures fixtures;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, playback_facts, playback_sessions, processed_events, "
                + "profiles, catalog_nodes, bootstrap_progress, dead_letters");
        jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL");
        fixtures = new IngestionFixtures();
    }

    @Test
    void severalQualifyingHeartbeatsCountOneSessionView() {
        var session = session("one", "account-1", "profile-1");
        for (int sequence = 1; sequence <= 6; sequence++) {
            long watched = sequence == 1 ? 0 : 10000;
            accept(event(session, sequence, watched, (sequence - 1) * 10000L,
                    BEFORE_MIDNIGHT.minusSeconds(60 - sequence)));
            assertThat(totals(DAY_1, DAY_1)).as("after heartbeat sequence=%s", sequence)
                    .isEqualTo(new StatisticsTotals(sequence >= 4 ? 1 : 0, sequence >= 4 ? 1 : 0,
                            (sequence - 1) * 10000L));
        }
        assertThat(totals(DAY_1, DAY_1)).isEqualTo(new StatisticsTotals(1, 1, 50000));
        assertThat(playback.session(session.id()).orElseThrow().qualifiedSequence()).isEqualTo(4);
    }

    @Test
    void midnightCrossingSplitsWatchTimeAndUsesLowestQualifyingSequenceDay() {
        var session = session("crossing", "account-1", "profile-1");
        accept(event(session, 1, 10000, 10000, BEFORE_MIDNIGHT.minusSeconds(10)));
        accept(event(session, 2, 20000, 30000, BEFORE_MIDNIGHT));
        accept(event(session, 3, 5000, 35000, AFTER_MIDNIGHT));

        assertThat(statistics.days(new StatisticsWindow(DAY_1, DAY_2))).containsExactly(
                new DayTotals(DAY_1, new StatisticsTotals(1, 1, 30000)),
                new DayTotals(DAY_2, new StatisticsTotals(0, 0, 5000)));
        assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(1, 1, 35000));
    }

    @Test
    void lateEarlierQualificationMovesViewAndDeletesEmptyOldDayRow() {
        var session = session("late", "account-1", "profile-1");
        accept(event(session, 3, 0, 30000, AFTER_MIDNIGHT));
        assertThat(rows(jdbc, STORED_ROWS)).containsExactly(new AccountDay(DAY_2, session.account(), 1, 0));

        accept(event(session, 2, 30000, 30000, BEFORE_MIDNIGHT));

        assertThat(rows(jdbc, STORED_ROWS)).containsExactly(new AccountDay(DAY_1, session.account(), 1, 30000));
        assertThat(totals(DAY_2, DAY_2)).isEqualTo(StatisticsTotals.ZERO);
        assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(1, 1, 30000));
        assertThat(playback.session(session.id()).orElseThrow().qualifiedAt()).isEqualTo(BEFORE_MIDNIGHT);
    }

    @Test
    void uniqueViewersAreAccountsAcrossProfilesAndTheWholeWindow() {
        accept(event(session("first", "account-1", "profile-1"), 1, 30000, 30000, BEFORE_MIDNIGHT));
        accept(event(session("second", "account-1", "profile-2"), 1, 30000, 30000, AFTER_MIDNIGHT));

        assertThat(statistics.days(new StatisticsWindow(DAY_1, DAY_2))).containsExactly(
                new DayTotals(DAY_1, new StatisticsTotals(1, 1, 30000)),
                new DayTotals(DAY_2, new StatisticsTotals(1, 1, 30000)));
        assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(2, 1, 60000));

        accept(event(session("third", "account-2", "profile-3"), 1, 30000, 30000, AFTER_MIDNIGHT));
        assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(3, 2, 90000));
    }

    @Test
    void redeliveryAndRealBootstrapReplayLeaveRollupsUnchanged() {
        var session = session("replay", "account-1", "profile-1");
        var events = List.of(event(session, 1, 10000, 10000, BEFORE_MIDNIGHT),
                event(session, 2, 20000, 30000, AFTER_MIDNIGHT));
        events.forEach(this::accept);
        var before = rows(jdbc, STORED_ROWS);
        assertThat(intake.accept(events.getFirst().topic(), events.getFirst().key(), events.getFirst().json()))
                .isEqualTo(DUPLICATE);
        var source = new ReplaySource(events);
        var bootstrap = new Bootstrap(source, bootstrapProgress, transactions, profiles, catalog, intake, clock);

        for (long generation : List.of(1L, 2L)) {
            var report = bootstrap.run(generation, 100);
            assertThat(report.completed()).isTrue();
            assertThat(report.sources().get(BootstrapSource.PLAYBACK).applied()).isEqualTo(2);
            assertThat(report.sources().get(BootstrapSource.PLAYBACK).rejected()).isZero();
            assertThat(rows(jdbc, STORED_ROWS)).isEqualTo(before);
            assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(1, 1, 30000));
        }
        assertThat(source.playbackCalls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(2);
    }

    @Test
    void profileDeletionRemovesLinksButKeepsStatistics() {
        var session = session("deleted", "account-1", "profile-1");
        accept(event(session, 1, 30000, 30000, BEFORE_MIDNIGHT));
        var before = rows(jdbc, STORED_ROWS);
        var deletion = fixtures.template(InputTopic.PROFILES, "profile-deleted");
        deletion.root().put("aggregateId", session.profile().toString()).put("occurredAt", AFTER_MIDNIGHT.toString());
        deletion.payload().put("profileId", session.profile().toString()).put("accountId", session.account().toString());
        accept(deletion);

        assertThat(rows(jdbc, STORED_ROWS)).isEqualTo(before);
        assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(1, 1, 30000));
        assertThat(jdbc.queryForObject("SELECT profile_id FROM playback_facts WHERE session_id = ?", UUID.class,
                session.id())).isNull();
        assertThat(playback.session(session.id()).orElseThrow().profileId()).isNull();
    }

    @Test
    void dailyReadIncludesEmptyDaysInAscendingOrder() {
        accept(event(session("sparse", "account-1", "profile-1"), 1, 30000, 30000, AFTER_MIDNIGHT));

        assertThat(statistics.days(new StatisticsWindow(DAY_1, DAY_1.plusDays(3)))).containsExactly(
                new DayTotals(DAY_1, StatisticsTotals.ZERO),
                new DayTotals(DAY_2, new StatisticsTotals(1, 1, 30000)),
                new DayTotals(DAY_1.plusDays(2), StatisticsTotals.ZERO),
                new DayTotals(DAY_1.plusDays(3), StatisticsTotals.ZERO));
    }

    @ParameterizedTest
    @ValueSource(longs = {41001, 41002, 41003})
    void shuffledSchedulesReconcileWithFactsAndSessionsForEverySubWindow(long seed) {
        List<Event> schedule = randomSchedule(seed);
        assertThat(schedule).as("seed=%s event count", seed).hasSize(60);
        assertThatCode(() -> schedule.forEach(this::accept)).as("seed=%s intake schedule", seed)
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT account_id) FROM playback_facts", Long.class))
                .as("seed=%s accounts", seed).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT profile_id) FROM playback_facts", Long.class))
                .as("seed=%s profiles", seed).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_sessions", Long.class))
                .as("seed=%s sessions", seed).isEqualTo(15);
        assertThat(jdbc.queryForList("SELECT DISTINCT (occurred_at AT TIME ZONE 'UTC')::date FROM playback_facts "
                + "ORDER BY 1", LocalDate.class)).as("seed=%s UTC days", seed)
                .containsExactly(DAY_1, DAY_2, DAY_1.plusDays(2), DAY_1.plusDays(3));
        List<AccountDay> recomputed = rows(jdbc, RECOMPUTED_ROWS);
        assertThat(rows(jdbc, STORED_ROWS)).as("seed=%s V6 row reconciliation", seed).isEqualTo(recomputed);
        for (int from = 0; from < 4; from++) {
            for (int to = from; to < 4; to++) {
                var window = new StatisticsWindow(DAY_1.plusDays(from), DAY_1.plusDays(to));
                assertThat(statisticsStore.totals(window)).as("seed=%s window=%s", seed, window)
                        .isEqualTo(recomputedTotals(recomputed, window));
                List<DayTotals> expectedDays = window.from().datesUntil(window.to().plusDays(1))
                        .map(day -> new DayTotals(day,
                                recomputedTotals(recomputed, new StatisticsWindow(day, day))))
                        .toList();
                assertThat(statistics.days(window)).as("seed=%s daily window=%s", seed, window)
                        .containsExactlyElementsOf(expectedDays);
            }
        }
    }

    @Test
    void v6BackfillsExistingFactsAndSessionsAndOmitsZeroOnlyRows() {
        // A separate fresh PostgreSQL keeps Spring's already-migrated database untouched.
        try (var legacy = new PostgreSQLContainer("postgres:18.6-alpine")
                .withDatabaseName("statistics_v5").withUsername("statistics_v5")
                .withPassword(UUID.randomUUID().toString())) {
            legacy.start();
            Flyway.configure().dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
                    .target("5").load().migrate();
            var old = new JdbcTemplate(new DriverManagerDataSource(
                    legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword()));
            assertThat(old.queryForObject("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = 'statistics_account_days'", Long.class)).isZero();
            var qualified = session("legacy-qualified", "account-1", "profile-1");
            var unqualified = session("legacy-unqualified", "account-2", "profile-2");
            var zero = session("legacy-zero", "account-2", "profile-3");
            insertLegacySession(old, qualified, List.of(
                    new LegacyFact(1, 10000, 10000, BEFORE_MIDNIGHT),
                    new LegacyFact(2, 20000, 30000, AFTER_MIDNIGHT)), 2L, AFTER_MIDNIGHT);
            insertLegacySession(old, unqualified,
                    List.of(new LegacyFact(1, 12000, 12000, BEFORE_MIDNIGHT)), null, null);
            insertLegacySession(old, zero, List.of(new LegacyFact(1, 0, 0, AFTER_MIDNIGHT)), null, null);

            Flyway.configure().dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
                    .load().migrate();

            assertThat(rows(old, STORED_ROWS)).containsExactlyInAnyOrder(
                    new AccountDay(DAY_1, qualified.account(), 0, 10000),
                    new AccountDay(DAY_2, qualified.account(), 1, 20000),
                    new AccountDay(DAY_1, unqualified.account(), 0, 12000));
        }
    }

    @Test
    void concurrentSessionsOfOneAccountMovingViewsAcrossDaysReconcile() throws Exception {
        int rounds = 10;
        for (int round = 0; round < rounds; round++) {
            var a = session("race-a-" + round, "account-race", "profile-race-a");
            var b = session("race-b-" + round, "account-race", "profile-race-b");
            // Both sessions first qualify after midnight, then a late lower sequence moves each view back before it:
            // both transactions update the same account's rows of both days at the same time.
            inParallel(List.of(event(a, 3, 0, 30000, AFTER_MIDNIGHT), event(a, 2, 30000, 30000, BEFORE_MIDNIGHT)),
                    List.of(event(b, 3, 0, 30000, AFTER_MIDNIGHT), event(b, 2, 30000, 30000, BEFORE_MIDNIGHT)));
        }
        assertThat(rows(jdbc, STORED_ROWS)).isEqualTo(rows(jdbc, RECOMPUTED_ROWS));
        assertThat(totals(DAY_1, DAY_1)).isEqualTo(new StatisticsTotals(2L * rounds, 1, 2L * rounds * 30000));
        assertThat(totals(DAY_2, DAY_2)).isEqualTo(StatisticsTotals.ZERO);
        assertThat(totals(DAY_1, DAY_2)).isEqualTo(new StatisticsTotals(2L * rounds, 1, 2L * rounds * 30000));
    }

    @Test
    void twoQualifyingFactsOfOneSessionArrivingTogetherCountOneViewOnTheLowerSequencesDay() throws Exception {
        int rounds = 10;
        for (int round = 0; round < rounds; round++) {
            var session = session("same-session-" + round, "account-same", "profile-same");
            inParallel(List.of(event(session, 3, 5000, 35000, AFTER_MIDNIGHT)),
                    List.of(event(session, 2, 30000, 30000, BEFORE_MIDNIGHT)));
        }
        assertThat(rows(jdbc, STORED_ROWS)).isEqualTo(rows(jdbc, RECOMPUTED_ROWS));
        assertThat(totals(DAY_1, DAY_1)).isEqualTo(new StatisticsTotals(rounds, 1, 30000L * rounds));
        assertThat(totals(DAY_2, DAY_2)).isEqualTo(new StatisticsTotals(0, 0, 5000L * rounds));
    }

    @Test
    void aViewMoveWithoutItsRollupRowRollsTheWholeFactBack() {
        var session = session("corrupt", "account-corrupt", "profile-corrupt");
        accept(event(session, 3, 0, 30000, AFTER_MIDNIGHT));
        // Simulate a rollup that lost the row holding the session's view.
        jdbc.update("DELETE FROM statistics_account_days");
        long receipts = jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class);
        var late = event(session, 2, 30000, 30000, BEFORE_MIDNIGHT);

        assertThatThrownBy(() -> intake.accept(late.topic(), late.key(), late.json()))
                .hasMessage("Statistics rollup has no view to move");

        // Nothing of the fact survives: it is retried and then parked, never half-applied.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ?", Long.class,
                session.id())).isEqualTo(1);
        assertThat(playback.session(session.id()).orElseThrow().qualifiedSequence()).isEqualTo(3L);
        assertThat(rows(jdbc, STORED_ROWS)).isEmpty();
    }

    @Test
    void anInstantPostgresqlWouldRoundIntoTheNextDayKeepsViewAndWatchedTimeOnOneDay() {
        // PgJDBC rounds nanoseconds to microseconds; 23:59:59.9999995Z would become midnight of the next day.
        var session = session("boundary", "account-boundary", "profile-boundary");
        accept(event(session, 1, 30000, 30000, Instant.parse("2026-09-07T23:59:59.999999500Z")));

        assertThat(rows(jdbc, STORED_ROWS)).containsExactly(new AccountDay(DAY_1, session.account(), 1, 30000));
        assertThat(rows(jdbc, STORED_ROWS)).isEqualTo(rows(jdbc, RECOMPUTED_ROWS));
    }

    /** Runs the two lists on two threads, step by step: both threads start each step together. */
    private void inParallel(List<Event> first, List<Event> second) throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (List<Event> events : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    for (Event event : events) {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        accept(event);
                    }
                    return null;
                }));
            }
            for (var future : futures) { future.get(30, java.util.concurrent.TimeUnit.SECONDS); }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void appendReturnsQualificationBeforeAndAfterEachFact() {
        var session = session("qualification", "account-1", "profile-1");
        // Only this store-contract test bypasses intake, to observe append's return value inside a transaction.
        assertThat(append(event(session, 1, 10000, 10000, BEFORE_MIDNIGHT.minusSeconds(10))))
                .isEqualTo(new Qualification(null, null));
        assertThat(append(event(session, 3, 0, 30000, AFTER_MIDNIGHT)))
                .isEqualTo(new Qualification(null, AFTER_MIDNIGHT));
        assertThat(append(event(session, 4, 5000, 35000, AFTER_MIDNIGHT.plusSeconds(10))))
                .isEqualTo(new Qualification(AFTER_MIDNIGHT, AFTER_MIDNIGHT));
        assertThat(append(event(session, 2, 20000, 30000, BEFORE_MIDNIGHT)))
                .isEqualTo(new Qualification(AFTER_MIDNIGHT, BEFORE_MIDNIGHT));
    }

    @Test
    void rollupCheckConstraintRejectsDirectNegativeViews() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO statistics_account_days "
                + "(day, account_id, qualified_views, accepted_watched_ms) VALUES (?, ?, -1, 0)",
                DAY_1, id("negative-account"))).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getSQLState())
                        .isEqualTo("23514"));
        assertThat(rows(jdbc, STORED_ROWS)).isEmpty();
    }

    private Event event(Session session, long sequence, long watched, long total, Instant at) {
        var event = fixtures.playback(sequence, watched, total);
        event.root().put("aggregateId", session.id().toString()).put("occurredAt", at.toString())
                .put("eventId", id("event:" + session.id() + ":" + sequence).toString())
                .put("correlationId", id("correlation:" + session.id() + ":" + sequence).toString());
        event.payload().put("sessionId", session.id().toString()).put("accountId", session.account().toString())
                .put("profileId", session.profile().toString()).put("contentId", id("content").toString())
                .put("assetId", id("asset").toString());
        return event;
    }

    private void accept(Event event) {
        assertThat(intake.accept(event.topic(), event.key(), event.json())).isEqualTo(APPLIED);
    }

    private Qualification append(Event event) {
        var fact = (PlaybackProgress) decoder.decode(event.topic(), event.key(), event.json());
        return transactions.inTransaction(() -> playback.append(fact)).qualification();
    }

    private StatisticsTotals totals(LocalDate from, LocalDate to) {
        return statisticsStore.totals(new StatisticsWindow(from, to));
    }

    private List<Event> randomSchedule(long seed) {
        var random = new Random(seed);
        var events = new ArrayList<Event>();
        for (int s = 0; s < 15; s++) {
            int profile = s % 6;
            var session = session(seed + "-session-" + s, "account-" + profile % 4, "profile-" + profile);
            int day = s % 3;
            long total = 0;
            long first = 10000 + random.nextInt(5001);
            for (int sequence = 1; sequence <= 4; sequence++) {
                long watched = sequence == 1 ? first : sequence == 2 ? 30000 - first : random.nextInt(5001);
                total += watched;
                long seconds = switch (sequence) {
                    case 1 -> -20 + random.nextInt(5);
                    case 2 -> random.nextInt(5);
                    case 3 -> 20 + random.nextInt(5);
                    default -> 40 + random.nextInt(5);
                };
                events.add(event(session, sequence, watched, total,
                        BEFORE_MIDNIGHT.plusSeconds(day * 86400L + seconds)));
            }
        }
        // Retain a seeded shuffle while guaranteeing at least one qualification moves back across midnight.
        Event early = events.get(1);
        Event late = events.get(2);
        Collections.shuffle(events, random);
        events.remove(late);
        events.addFirst(late);
        events.remove(early);
        events.addLast(early);
        return events;
    }

    private static List<AccountDay> rows(JdbcTemplate source, String sql) {
        return source.query(sql, (row, index) -> new AccountDay(row.getObject("day", LocalDate.class),
                row.getObject("account_id", UUID.class), row.getLong("qualified_views"),
                row.getLong("accepted_watched_ms")));
    }

    private static StatisticsTotals recomputedTotals(List<AccountDay> rows, StatisticsWindow window) {
        long views = 0;
        long watched = 0;
        var viewers = new HashSet<UUID>();
        for (AccountDay row : rows) {
            if (row.day().isBefore(window.from()) || row.day().isAfter(window.to())) { continue; }
            views += row.views();
            watched += row.watched();
            if (row.views() > 0) { viewers.add(row.account()); }
        }
        return new StatisticsTotals(views, viewers.size(), watched);
    }

    private static void insertLegacySession(JdbcTemplate db, Session session, List<LegacyFact> facts,
            Long qualifiedSequence, Instant qualifiedAt) {
        for (LegacyFact fact : facts) {
            db.update("""
                    INSERT INTO playback_facts (session_id, sequence, event_id, account_id, profile_id, content_id,
                        asset_id, asset_version, position_ms, accepted_watched_ms, total_watched_ms, qualified,
                        state, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 'PLAYING', ?)
                    """, session.id(), fact.sequence(), id("legacy:" + session.id() + ":" + fact.sequence()),
                    session.account(), session.profile(), id("content"), id("asset"), fact.total(), fact.watched(),
                    fact.total(), fact.total() >= 30000, Timestamp.from(fact.at()));
        }
        LegacyFact last = facts.getLast();
        db.update("""
                INSERT INTO playback_sessions (session_id, account_id, profile_id, content_id, fact_count,
                    accepted_watched_ms, max_sequence, max_total_watched_ms, qualified_sequence, qualified_at,
                    first_occurred_at, last_occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session.id(), session.account(), session.profile(), id("content"), facts.size(),
                facts.stream().mapToLong(LegacyFact::watched).sum(), last.sequence(), last.total(), qualifiedSequence,
                qualifiedAt == null ? null : Timestamp.from(qualifiedAt), Timestamp.from(facts.getFirst().at()),
                Timestamp.from(last.at()));
    }

    private static Session session(String session, String account, String profile) {
        return new Session(id(session), id(account), id(profile));
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("statistics-rollup:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private record Session(UUID id, UUID account, UUID profile) {}
    private record AccountDay(LocalDate day, UUID account, long views, long watched) {}
    private record LegacyFact(long sequence, long watched, long total, Instant at) {}

    private static final class ReplaySource implements CoreSnapshots {
        private final List<SnapshotPages.ReplayedEvent> events;
        private int playbackCalls;

        private ReplaySource(List<Event> events) {
            this.events = events.stream().map(event -> new SnapshotPages.ReplayedEvent(event.key(), event.json())).toList();
        }

        @Override
        public SnapshotPages.ProfilePage profiles(UUID after, int limit) {
            return new SnapshotPages.ProfilePage(List.of(), null);
        }

        @Override
        public SnapshotPages.CatalogPage catalog(UUID after, int limit) {
            return new SnapshotPages.CatalogPage(List.of(), null);
        }

        @Override
        public SnapshotPages.PlaybackPage playback(String after, int limit) {
            assertThat(after).isNull();
            playbackCalls++;
            return new SnapshotPages.PlaybackPage(events, null);
        }
    }
}
