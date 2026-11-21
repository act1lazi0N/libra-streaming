package com.libra.streaming.recommendation.bootstrap.infrastructure;

import com.libra.streaming.recommendation.bootstrap.application.Bootstrap;
import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots;
import com.libra.streaming.recommendation.bootstrap.domain.SnapshotPages;
import com.libra.streaming.recommendation.bootstrap.application.BootstrapProgress;
import com.libra.streaming.recommendation.bootstrap.application.CoreSnapshots.SnapshotUnavailableException;
import com.libra.streaming.recommendation.bootstrap.domain.BootstrapSource;
import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.catalog.application.CatalogStore;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.ingestion.infrastructure.JsonCoreEventDecoder;
import com.libra.streaming.recommendation.internalapi.infrastructure.CoreServiceProperties;
import com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens;
import com.libra.streaming.recommendation.playback.application.PlaybackStore;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import com.libra.streaming.recommendation.profiles.application.ProfileStore;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real Flyway/JDBC projections and receipts, with Core replaced only at its HTTP boundary. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.core-service.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class BootstrapIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_bootstrap").withUsername("analytics_bootstrap")
            .withPassword(UUID.randomUUID().toString());
    static final KeyPair KEY = ServiceTokens.pair(2048);
    static final Instant NOW = Instant.parse("2026-11-16T01:02:03Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String KID = "analytics-bootstrap-integration";
    static final int PAGE_SIZE = 2;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired BootstrapProgress progress;
    @Autowired Transactions transactions;
    @Autowired ProfileProjection profileProjection;
    @Autowired CatalogProjection catalogProjection;
    @Autowired EventIntake intake;
    @Autowired CatalogStore catalog;
    @Autowired ProfileStore profiles;
    @Autowired PlaybackStore playback;
    @Autowired ApplicationContext context;
    FakeCore core;
    HttpCoreSnapshots http;
    Bootstrap bootstrap;

    @BeforeEach
    void start() throws Exception {
        clearData();
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT version || ':' || success FROM flyway_schema_history ORDER BY installed_rank",
                String.class)).containsExactly("1:true", "2:true", "3:true", "4:true", "5:true", "6:true", "7:true", "8:true");
        assertThat(context.getBeansOfType(BootstrapRunner.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("libra.analytics.consumers.enabled", Boolean.class)).isFalse();
        core = new FakeCore();
        var properties = new BootstrapProperties(true, core.origin(),
                Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded()), ServiceTokens.publicKey(KEY), KID, 1, PAGE_SIZE);
        var tokens = new BootstrapTokens(properties, new CoreServiceProperties(false, "", ""), CLOCK);
        http = new HttpCoreSnapshots(properties, tokens, CLOCK);
        // The application runner is disabled; exercise the real adapters and Spring-managed JDBC collaborators directly.
        // A fixed clock preserves every changed_at column when comparing independent rebuilds.
        bootstrap = new Bootstrap(http, progress, transactions, profileProjection, catalogProjection, intake, CLOCK);
    }

    @AfterEach
    void stop() {
        try {
            if (core != null) { core.assertHealthy(); }
        } finally {
            if (http != null) { http.close(); }
            if (core != null) { core.close(); }
        }
    }

    @Test
    void snapshotsAndLiveEventsObeyVersionOrderingAndTerminalProfileDeletion() {
        var fixtures = new Fixtures();
        var liveSeven = fixtures.movie(7, "live-seven");
        UUID contentId = liveSeven.aggregateId();
        accept(liveSeven);
        accept(fixtures.profile(1, "ProfileCreated"));
        var updated = fixtures.profile(2, "ProfileUpdated");
        accept(updated);
        UUID profileId = updated.aggregateId();
        assertThat(profiles.find(profileId).orElseThrow().deleted()).isFalse();
        var before = catalog.find(contentId).orElseThrow();

        var tombstone = profileItem(fixtures.profile(3, "ProfileDeleted"), true);
        core.plan = new Plan(List.of(tombstone), List.of(catalogItem(fixtures.movie(5, "snapshot-five"))), List.of());
        assertThat(run(1).completed()).isTrue();
        assertThat(catalog.find(contentId)).contains(before);
        assertThat(catalog.find(contentId).orElseThrow().version()).isEqualTo(7);
        var deleted = profiles.find(profileId).orElseThrow();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.version()).isEqualTo(3);
        accept(fixtures.profile(4, "ProfileCreated"));
        assertThat(profiles.find(profileId)).contains(deleted);

        core.plan = new Plan(List.of(tombstone), List.of(catalogItem(fixtures.movie(9, "snapshot-nine"))), List.of());
        assertThat(run(2).completed()).isTrue();
        var nine = catalog.find(contentId).orElseThrow();
        assertThat(nine.version()).isEqualTo(9);
        assertThat(nine.published().metadata().title()).isEqualTo("snapshot-nine");
        var afterSnapshot = dump();
        accept(fixtures.movie(8, "late-live-eight"));
        assertThat(catalog.find(contentId)).contains(nine);
        // The new live receipt is expected; every projection column must remain untouched.
        assertThat(projectionDump()).isEqualTo(withoutReceipts(afterSnapshot));
        assertThat(profiles.find(profileId)).contains(deleted);
    }

    @Test
    void liveAndReplayedEventIdsConvergeOnCanonicalSumsAndRejectOnlyTheInvalidFixture() {
        var fixtures = new Fixtures();
        var profile = fixtures.profile(1, "ProfileCreated");
        var movie = fixtures.movie(7, "movie");
        accept(profile);
        accept(movie);
        var events = fixtures.canonicalPlayback();
        // Live delivery has already committed two of the exact objects served by replay.
        accept(events.get(1));
        accept(events.getFirst());
        UUID session = events.getFirst().aggregateId();
        assertThat(playback.session(session).orElseThrow().acceptedWatchedMs()).isEqualTo(10_000);
        var invalid = fixtures.invalidPlayback();
        assertSemanticRejection(invalid);
        core.plan = new Plan(List.of(profileItem(profile, false)), List.of(catalogItem(movie)),
                List.of(events.get(0), events.get(1), invalid, events.get(2), events.get(3)));

        var first = run(1);
        assertCounts(first, BootstrapSource.PLAYBACK, 4, 1);
        assertCanonicalPlayback(session, events);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                invalid.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE event_id = ?", Integer.class,
                invalid.eventId())).isZero();
        var rebuilt = dump();
        int calls = core.calls.size();
        assertThat(run(1).sources()).isEqualTo(first.sources());
        assertThat(core.calls).hasSize(calls); // A completed generation performs no reads.
        assertThat(run(2).sources()).isEqualTo(first.sources());
        assertThat(core.calls.size()).isGreaterThan(calls); // This replay really passed through HTTP again.
        assertThat(dump()).isEqualTo(rebuilt);
        assertCanonicalPlayback(session, events);
    }

    @Test
    void rebuildingAndResumingAfterSecondCatalogPageFailureMatchAnUninterruptedRunInEveryColumn() {
        var fixtures = new Fixtures();
        var profile = fixtures.profile(1, "ProfileCreated");
        var events = fixtures.canonicalPlayback();
        var invalid = fixtures.invalidPlayback();
        assertSemanticRejection(invalid);
        var catalogItems = new ArrayList<ObjectNode>();
        catalogItems.add(catalogItem(fixtures.movie(9, "main movie")));
        catalogItems.add(catalogItem(new Fixtures().movie(4, "second movie")));
        catalogItems.add(catalogItem(new Fixtures().movie(6, "third movie")));
        catalogItems.sort(Comparator.comparing(item -> item.path("contentId").stringValue()));
        core.plan = new Plan(List.of(profileItem(profile, false)), catalogItems,
                List.of(events.get(0), events.get(1), invalid, events.get(2), events.get(3)));

        var uninterrupted = run(1);
        assertCounts(uninterrupted, BootstrapSource.PROFILES, 1, 0);
        assertCounts(uninterrupted, BootstrapSource.CATALOG, 3, 0);
        assertCounts(uninterrupted, BootstrapSource.PLAYBACK, 4, 1);
        var expected = dump();
        assertThat(expected.get("catalog_nodes")).hasSize(3);
        assertThat(expected.get("profiles")).hasSize(1);
        assertThat(expected.get("playback_facts")).hasSize(4);
        assertThat(expected.get("playback_sessions")).hasSize(1);
        assertThat(run(2).sources()).isEqualTo(uninterrupted.sources());
        assertThat(dump()).isEqualTo(expected);

        clearData();
        core.calls.clear();
        core.failSecondCatalogPageOnce.set(true);
        assertThatThrownBy(() -> bootstrap.run(1, PAGE_SIZE)).isInstanceOf(SnapshotUnavailableException.class)
                .hasMessage("CORE_STATUS");
        core.assertHealthy();
        String firstCursor = catalogItems.get(1).path("contentId").stringValue();
        assertThat(progress.find(1, BootstrapSource.PROFILES).orElseThrow().completed()).isTrue();
        assertThat(progress.find(1, BootstrapSource.CATALOG)).contains(
                new BootstrapProgress.Position(firstCursor, 2, 0, false));
        assertThat(progress.find(1, BootstrapSource.PLAYBACK)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_nodes", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts", Integer.class)).isZero();

        var resumed = run(1);
        assertThat(resumed.sources()).isEqualTo(uninterrupted.sources());
        assertThat(dump()).isEqualTo(expected);
        assertThat(core.calls.stream().filter(call -> call.path().endsWith("/profiles")).count()).isEqualTo(1);
        var catalogCalls = core.calls.stream().filter(call -> call.path().endsWith("/catalog")).toList();
        assertThat(catalogCalls).extracting(Call::after).containsExactly(null, firstCursor, firstCursor);
        assertThat(catalogCalls).extracting(Call::status).containsExactly(200, 500, 200);
        assertCanonicalPlayback(events.getFirst().aggregateId(), events);
    }

    @Test
    void concurrentSnapshotPagesAndLiveVersionsConvergeWithoutResurrectingProfiles() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            var active = new Fixtures();
            var erased = new Fixtures();
            var activeSnapshot = active.profile(5, "ProfileUpdated");
            var erasedSnapshot = erased.profile(5, "ProfileUpdated");
            var movie = active.movie(5, "snapshot-five");
            core.plan = new Plan(List.of(profileItem(activeSnapshot, false), profileItem(erasedSnapshot, false)),
                    List.of(catalogItem(movie)), List.of());
            var profileGate = new CyclicBarrier(5);
            var catalogGate = new CyclicBarrier(5);
            Bootstrap racing = gatedBootstrap(profileGate, catalogGate, null);
            long generation = iteration + 1;
            var tasks = new ArrayList<Runnable>();
            tasks.add(() -> assertThat(racing.run(generation, PAGE_SIZE).completed()).isTrue());
            long[] versions = {3, 7, 9, 4};
            for (long version : versions) {
                tasks.add(() -> {
                    await(profileGate);
                    accept(active.profile(version, "ProfileUpdated"));
                    accept(erased.profile(version == 4 ? 11 : version,
                            version == 4 ? "ProfileDeleted" : "ProfileUpdated"));
                    await(catalogGate);
                    accept(active.movie(version, "live-" + version));
                });
            }
            runConcurrent(tasks);
            var storedProfile = profiles.find(activeSnapshot.aggregateId()).orElseThrow();
            assertThat(storedProfile.version()).isEqualTo(9);
            assertThat(storedProfile.deleted()).isFalse();
            var tombstone = profiles.find(erasedSnapshot.aggregateId()).orElseThrow();
            assertThat(tombstone.version()).isEqualTo(11);
            assertThat(tombstone.deleted()).isTrue();
            var storedMovie = catalog.find(movie.aggregateId()).orElseThrow();
            assertThat(storedMovie.version()).isEqualTo(9);
            assertThat(storedMovie.published().metadata().title()).isEqualTo("live-9");
            // Late deliveries after convergence also must not overwrite a higher version or revive a tombstone.
            accept(active.profile(2, "ProfileUpdated"));
            accept(active.movie(2, "late-two"));
            accept(erased.profile(12, "ProfileUpdated"));
            assertThat(profiles.find(activeSnapshot.aggregateId())).contains(storedProfile);
            assertThat(profiles.find(erasedSnapshot.aggregateId())).contains(tombstone);
            assertThat(catalog.find(movie.aggregateId())).contains(storedMovie);
        }
    }

    @Test
    void concurrentReplayAndLivePlaybackCountEachDistinctSequenceAndReceiptOnce() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            var fixtures = new Fixtures();
            var events = fixtures.canonicalPlayback();
            core.plan = new Plan(List.of(), List.of(), events);
            var replayGate = new CyclicBarrier(5);
            Bootstrap racing = gatedBootstrap(null, null, replayGate);
            long generation = iteration + 1;
            var tasks = new ArrayList<Runnable>();
            tasks.add(() -> assertThat(racing.run(generation, PAGE_SIZE).completed()).isTrue());
            for (int worker = 0; worker < 4; worker++) {
                boolean reverse = worker % 2 == 0;
                tasks.add(() -> {
                    await(replayGate);
                    for (Event event : reverse ? events.reversed() : events) {
                        assertThat(intake.accept(event.topic(), event.aggregateId().toString(), event.root().toString()))
                                .isIn(EventIntake.Outcome.APPLIED, EventIntake.Outcome.DUPLICATE);
                    }
                });
            }
            runConcurrent(tasks);
            UUID session = events.getFirst().aggregateId();
            assertCanonicalPlayback(session, events);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_facts WHERE session_id = ?",
                    Integer.class, session)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE aggregate_id = ?",
                    Integer.class, session)).isEqualTo(4);
            for (Event event : events) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, event.eventId())).isEqualTo(1);
            }
        }
    }

    private Bootstrap gatedBootstrap(CyclicBarrier profilesGate, CyclicBarrier catalogGate, CyclicBarrier replayGate) {
        // Keep the real HTTP/page validation boundary. Gate the first fetched page against live delivery.
        CoreSnapshots snapshots = new CoreSnapshots() {
            @Override public SnapshotPages.ProfilePage profiles(UUID after, int limit) {
                var page = http.profiles(after, limit);
                if (after == null && profilesGate != null) { await(profilesGate); }
                return page;
            }
            @Override public SnapshotPages.CatalogPage catalog(UUID after, int limit) {
                var page = http.catalog(after, limit);
                if (after == null && catalogGate != null) { await(catalogGate); }
                return page;
            }
            @Override public SnapshotPages.PlaybackPage playback(String after, int limit) {
                var page = http.playback(after, limit);
                if (after == null && replayGate != null) { await(replayGate); }
                return page;
            }
        };
        return new Bootstrap(snapshots, progress, transactions, profileProjection, catalogProjection, intake, CLOCK);
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(15, TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent delivery interrupted", failure);
        } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("Concurrent delivery barrier failed", failure);
        }
    }

    private static void runConcurrent(List<Runnable> tasks) throws Exception {
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(tasks.size());
        var futures = new ArrayList<Future<?>>();
        try {
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Concurrent start timed out"); }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Concurrent start interrupted", failure);
                    }
                    task.run();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) { future.get(45, TimeUnit.SECONDS); }
        } finally {
            start.countDown();
            for (Future<?> future : futures) { future.cancel(true); }
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    Bootstrap.Report run(long generation) {
        var result = bootstrap.run(generation, PAGE_SIZE);
        core.assertHealthy();
        assertThat(result.completed()).isTrue();
        assertThat(bootstrap.completed(generation)).isTrue();
        return result;
    }

    void accept(Event event) {
        assertThat(intake.accept(event.topic(), event.aggregateId().toString(), event.root().toString()))
                .isEqualTo(EventIntake.Outcome.APPLIED);
    }

    static void assertSemanticRejection(Event invalid) {
        var rejection = assertThrows(EventRejection.class, () -> new JsonCoreEventDecoder().decode(invalid.topic(),
                invalid.aggregateId().toString(), invalid.root().toString()));
        assertThat(rejection.reason()).isEqualTo(EventRejection.Reason.SEMANTIC_INVALID);
    }

    void assertCanonicalPlayback(UUID session, List<Event> canonical) {
        long sum = canonical.stream().mapToLong(event -> event.payload().path("acceptedWatchedMs").longValue()).sum();
        assertThat(sum).isEqualTo(35_000);
        var total = playback.session(session).orElseThrow();
        assertThat(total.acceptedWatchedMs()).isEqualTo(sum);
        assertThat(total.maxTotalWatchedMs()).isEqualTo(sum);
        assertThat(total.factCount()).isEqualTo(canonical.size());
        assertThat(total.maxSequence()).isEqualTo(4);
        assertThat(total.complete()).isTrue();
        assertThat(total.qualifiedSequence()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sum(accepted_watched_ms) FROM playback_facts WHERE session_id = ?",
                Long.class, session)).isEqualTo(sum);
        assertThat(jdbc.queryForList("SELECT event_id FROM playback_facts WHERE session_id = ? ORDER BY sequence",
                UUID.class, session)).containsExactlyElementsOf(canonical.stream().map(Event::eventId).toList());
    }

    static void assertCounts(Bootstrap.Report report, BootstrapSource source, long applied, long rejected) {
        var position = report.sources().get(source);
        assertThat(position.completed()).isTrue();
        assertThat(position.applied()).isEqualTo(applied);
        assertThat(position.rejected()).isEqualTo(rejected);
    }

    void clearData() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, bootstrap_progress, processed_events, catalog_nodes, profiles, playback_facts, playback_sessions, dead_letters");
    }

    Map<String, List<String>> projectionDump() { return withoutReceipts(dump()); }

    static Map<String, List<String>> withoutReceipts(Map<String, List<String>> dump) {
        var projections = new LinkedHashMap<>(dump);
        projections.remove("processed_events");
        return projections;
    }

    Map<String, List<String>> dump() {
        // PostgreSQL serializes every column, including arrays/jsonb/timestamps, avoiding JDBC object identity equality.
        // These are constant table names/order keys, never HTTP input. No projection columns are dropped.
        var dumps = new LinkedHashMap<String, List<String>>();
        dumps.put("catalog_nodes", jdbc.queryForList("SELECT to_jsonb(stored)::text FROM catalog_nodes AS stored ORDER BY content_id", String.class));
        dumps.put("profiles", jdbc.queryForList("SELECT to_jsonb(stored)::text FROM profiles AS stored ORDER BY profile_id", String.class));
        dumps.put("playback_facts", jdbc.queryForList("SELECT to_jsonb(stored)::text FROM playback_facts AS stored ORDER BY session_id, sequence", String.class));
        dumps.put("playback_sessions", jdbc.queryForList("SELECT to_jsonb(stored)::text FROM playback_sessions AS stored ORDER BY session_id", String.class));
        dumps.put("processed_events", jdbc.queryForList("SELECT (to_jsonb(stored) - 'processed_at')::text FROM processed_events AS stored ORDER BY event_id", String.class));
        dumps.put("profile_watch_days", jdbc.queryForList("SELECT to_jsonb(stored)::text FROM profile_watch_days AS stored ORDER BY profile_id, content_id, day", String.class));
        return dumps;
    }

    static ObjectNode catalogItem(Event event) {
        ObjectNode item = event.payload().deepCopy();
        item.put("version", event.root().path("aggregateVersion").longValue());
        item.put("publishedAt", event.root().path("occurredAt").stringValue());
        return item;
    }

    static ObjectNode profileItem(Event event, boolean deleted) {
        ObjectNode item = event.payload().deepCopy();
        item.put("version", event.root().path("aggregateVersion").longValue());
        item.put("deleted", deleted);
        return item;
    }

    record Event(InputTopic topic, ObjectNode root) {
        UUID aggregateId() { return UUID.fromString(root.path("aggregateId").stringValue()); }
        UUID eventId() { return UUID.fromString(root.path("eventId").stringValue()); }
        ObjectNode payload() { return (ObjectNode) root.path("payload"); }
    }

    /** Reuse the existing package-private fixture loader without widening this one-file change. */
    static final class Fixtures {
        final Object delegate;

        Fixtures() {
            try {
                Class<?> type = Class.forName("com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures");
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                delegate = constructor.newInstance();
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Fixture loader unavailable", failure); }
        }

        Event movie(long version, String title) {
            Event event = invoke(InputTopic.CATALOG, "movie", new Class<?>[] {long.class, String.class}, version, "CatalogPublished");
            ((ObjectNode) event.payload().path("published").path("metadata")).put("title", title);
            return event;
        }

        Event profile(long version, String type) {
            return invoke(InputTopic.PROFILES, "profile", new Class<?>[] {long.class, String.class}, version, type);
        }

        List<Event> canonicalPlayback() {
            var result = new ArrayList<Event>();
            long[] increments = {0, 10_000, 25_000, 0};
            long total = 0;
            for (int index = 0; index < increments.length; index++) {
                total += increments[index];
                result.add(invoke(InputTopic.PLAYBACK, "playback", new Class<?>[] {long.class, long.class, long.class},
                        (long) index + 1, increments[index], total));
            }
            return List.copyOf(result);
        }

        Event invalidPlayback() {
            return invoke(InputTopic.PLAYBACK, "template", new Class<?>[] {InputTopic.class, String.class},
                    InputTopic.PLAYBACK, "playback-aggregate-not-session");
        }

        Event invoke(InputTopic topic, String name, Class<?>[] parameters, Object... arguments) {
            try {
                Method method = delegate.getClass().getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                Object fixture = method.invoke(delegate, arguments);
                Method root = fixture.getClass().getDeclaredMethod("root");
                root.setAccessible(true);
                return new Event(topic, ((ObjectNode) root.invoke(fixture)).deepCopy());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Fixture generation failed", failure); }
        }
    }

    record Plan(List<ObjectNode> profiles, List<ObjectNode> catalog, List<Event> playback) {
        Plan {
            profiles = profiles.stream().sorted(Comparator.comparing(item -> item.path("profileId").stringValue())).toList();
            catalog = catalog.stream().sorted(Comparator.comparing(item -> item.path("contentId").stringValue())).toList();
            playback = List.copyOf(playback);
        }
    }

    record Call(String path, String after, int status) {}

    static final class FakeCore implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Set<String> tokenIds = ConcurrentHashMap.newKeySet();
        final AtomicBoolean failSecondCatalogPageOnce = new AtomicBoolean();
        volatile Plan plan = new Plan(List.of(), List.of(), List.of());

        FakeCore() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/internal/v1/analytics/", this::handle);
            server.start();
        }

        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

        void assertHealthy() { assertThat(failures).isEmpty(); }

        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                try {
                    verifyToken(exchange);
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/json");
                    Map<String, String> query = new LinkedHashMap<>();
                    for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                        String[] pair = part.split("=", 2);
                        assertThat(pair).hasSize(2);
                        assertThat(query.put(pair[0], pair[1])).isNull();
                    }
                    assertThat(query.keySet()).contains("limit").isSubsetOf("limit", "after");
                    int limit = Integer.parseInt(query.get("limit"));
                    assertThat(limit).isEqualTo(PAGE_SIZE);
                    String after = query.get("after");
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/snapshots/catalog") && after != null
                            && failSecondCatalogPageOnce.compareAndSet(true, false)) {
                        calls.add(new Call(path, after, 500));
                        respond(exchange, 500, "{}");
                        return;
                    }
                    ObjectNode page = switch (path) {
                        case "/internal/v1/analytics/snapshots/profiles" -> snapshot(plan.profiles(), "profileId", after, limit);
                        case "/internal/v1/analytics/snapshots/catalog" -> snapshot(plan.catalog(), "contentId", after, limit);
                        case "/internal/v1/analytics/events/playback" -> replay(plan.playback(), after, limit);
                        default -> throw new AssertionError("Unexpected Core path");
                    };
                    calls.add(new Call(path, after, 200));
                    respond(exchange, 200, page.toString());
                } catch (Exception | AssertionError failure) {
                    failures.add(failure);
                    respond(exchange, 401, "{}");
                }
            }
        }

        void verifyToken(HttpExchange exchange) throws Exception {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            assertThat(authorization != null && authorization.startsWith("Bearer ")).isTrue();
            var token = SignedJWT.parse(authorization.substring(7));
            assertThat(token.verify(new RSASSAVerifier((RSAPublicKey) KEY.getPublic()))).isTrue();
            assertThat(token.getHeader().toJSONObject()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("alg", "RS256", "kid", KID, "typ", "JWT"));
            var claims = token.getJWTClaimsSet();
            String jti = claims.getJWTID();
            assertThat(UUID.fromString(jti).version()).isEqualTo(4);
            assertThat(tokenIds.add(jti)).isTrue();
            var expected = JSON.readTree(JSON.writeValueAsString(Map.of("iss", "libra-analytics-services", "sub", "libra-analytics",
                    "aud", List.of("libra-core-internal"), "purpose", "service-access", "scope", "analytics.bootstrap:read",
                    "jti", jti, "iat", NOW.getEpochSecond(), "exp", NOW.plusSeconds(60).getEpochSecond())));
            assertThat(JSON.readTree(token.getPayload().toString())).isEqualTo(expected);
        }

        static ObjectNode snapshot(List<ObjectNode> source, String id, String after, int limit) {
            var items = source.stream().filter(item -> after == null || item.path(id).stringValue().compareTo(after) > 0)
                    .limit(limit).toList();
            ObjectNode page = JSON.createObjectNode();
            var array = page.putArray("items");
            items.forEach(array::add);
            if (items.size() == limit) { page.put("next", items.getLast().path(id).stringValue()); }
            else { page.putNull("next"); }
            return page;
        }

        static ObjectNode replay(List<Event> source, String after, int limit) {
            int start = 0;
            if (after != null) {
                start = -1;
                for (int index = 0; index < source.size(); index++) {
                    if (cursor(source, index).equals(after)) { start = index + 1; break; }
                }
                assertThat(start).isNotNegative();
            }
            int end = Math.min(start + limit, source.size());
            ObjectNode page = JSON.createObjectNode();
            var array = page.putArray("events");
            for (int index = start; index < end; index++) { array.add(source.get(index).root()); }
            if (end - start == limit) { page.put("next", cursor(source, end - 1)); }
            else { page.putNull("next"); }
            return page;
        }

        static String cursor(List<Event> source, int index) { return (1000L + index) + "." + source.get(index).eventId(); }

        static void respond(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
