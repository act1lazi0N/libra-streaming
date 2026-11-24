package com.libra.streaming.recommendation.internalapi.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RA05.4: the four V2 lists stay inside Core's 500 ms budget on a catalog and history far larger than the V1 product
 * (2,000 titles, a profile with 20,000 facts on 400 items, 6,000 qualified sessions in the popularity window), over a
 * real socket with Core's request. RA05.2 left this unmeasured: every request reads the whole catalog, the profile's
 * facts and the window's sessions.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
class RecommendationListLoadIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_load").withUsername("analytics_load").withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("libra.analytics.core-service.enabled", () -> "true");
        registry.add("libra.analytics.core-service.public-key", () -> ServiceTokens.publicKey(ServiceTokens.CORE));
        registry.add("libra.analytics.core-service.key-id", () -> ServiceTokens.KID);
    }

    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PROFILE = UUID.fromString("4a4a4a4a-4444-4444-8444-444444444444");
    private static final Duration BUDGET = Duration.ofMillis(500);
    private static boolean seeded;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll static void reset() { seeded = false; }

    private void seed() {
        if (seeded) { return; }
        Instant now = Instant.now();
        String genres = "ARRAY['g' || (n % 20), 'g' || ((n * 7) % 20)]::text[]";
        // 1,500 READY movies.
        jdbc.update("""
                INSERT INTO catalog_nodes (content_id, version, kind, published, active, genres, access_tier, active_state,
                    duration_seconds, published_at, published_at_version, changed_at)
                SELECT gen_random_uuid(), 1, 'MOVIE', '{}'::jsonb, '{}'::jsonb, %s, 'FREE', 'READY', 3600,
                    ?::timestamptz - n * interval '1 hour', 1, ?::timestamptz
                FROM generate_series(1, 1500) AS n
                """.formatted(genres), Timestamp.from(now), Timestamp.from(now));
        // 500 series, each with one season and one READY episode.
        jdbc.update("""
                WITH series AS (
                    INSERT INTO catalog_nodes (content_id, version, kind, published, genres, access_tier, published_at,
                        published_at_version, changed_at)
                    SELECT gen_random_uuid(), 1, 'SERIES', '{}'::jsonb, %s, 'FREE',
                        ?::timestamptz - n * interval '90 minutes', 1, ?::timestamptz
                    FROM generate_series(1, 500) AS n
                    RETURNING content_id
                ), seasons AS (
                    INSERT INTO catalog_nodes (content_id, version, kind, parent_id, ordinal, published, changed_at)
                    SELECT gen_random_uuid(), 1, 'SEASON', content_id, 1, '{}'::jsonb, ?::timestamptz FROM series
                    RETURNING content_id
                )
                INSERT INTO catalog_nodes (content_id, version, kind, parent_id, ordinal, published, active, active_state,
                    duration_seconds, changed_at)
                SELECT gen_random_uuid(), 1, 'EPISODE', content_id, 1, '{}'::jsonb, '{}'::jsonb, 'READY', 1800,
                    ?::timestamptz FROM seasons
                """.replace("%s", genres.replace("n %", "(n + 3) %").replace("(n * 7)", "((n + 3) * 7)")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        List<UUID> movies = jdbc.queryForList("SELECT content_id FROM catalog_nodes WHERE kind = 'MOVIE' ORDER BY content_id",
                UUID.class);
        // The profile's history: 400 movies, 50 heartbeats each (20,000 facts), and its 30-day interests.
        jdbc.update("""
                WITH watched AS (
                    SELECT content_id, row_number() OVER (ORDER BY content_id) AS i
                    FROM catalog_nodes WHERE kind = 'MOVIE' ORDER BY content_id LIMIT 400
                ), sessions AS (
                    SELECT content_id, i, gen_random_uuid() AS session_id FROM watched
                )
                INSERT INTO playback_facts (session_id, sequence, event_id, account_id, profile_id, content_id, asset_id,
                    asset_version, position_ms, accepted_watched_ms, total_watched_ms, qualified, state, occurred_at)
                SELECT session_id, q, gen_random_uuid(), ?, ?, content_id, gen_random_uuid(), 1, q * 10000, 10000,
                    q * 10000, q * 10000 >= 30000, 'PLAYING', ?::timestamptz - (i * interval '1 hour') + q * interval '10 seconds'
                FROM sessions, generate_series(1, 50) AS q
                """, ACCOUNT, PROFILE, Timestamp.from(now.minus(Duration.ofDays(1))));
        jdbc.update("""
                INSERT INTO profile_watch_days (profile_id, content_id, day, account_id, accepted_watched_ms)
                SELECT ?, content_id, (min(occurred_at) AT TIME ZONE 'UTC')::date, ?, sum(accepted_watched_ms)
                FROM playback_facts WHERE profile_id = ? GROUP BY content_id
                """, PROFILE, ACCOUNT, PROFILE);
        // Popularity: 6,000 qualified sessions of other accounts in the last 7 days.
        List<UUID> shuffled = new ArrayList<>(movies);
        Collections.shuffle(shuffled, new java.util.Random(20261123L));
        jdbc.update("""
                INSERT INTO playback_sessions (session_id, account_id, profile_id, content_id, fact_count,
                    accepted_watched_ms, max_sequence, max_total_watched_ms, qualified_sequence, qualified_at,
                    first_occurred_at, last_occurred_at)
                SELECT gen_random_uuid(), gen_random_uuid(), NULL, ids[1 + (n %% array_length(ids, 1))], 3, 30000, 3,
                    30000, 3, ?::timestamptz - (n %% 600) * interval '15 minutes', ?::timestamptz - interval '7 days',
                    ?::timestamptz
                FROM generate_series(1, 6000) AS n, (SELECT ?::uuid[] AS ids) AS pool
                """.replace("%%", "%"), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                shuffled.subList(0, 300).toArray(UUID[]::new));
        jdbc.update("ANALYZE");
        jdbc.update("UPDATE input_checkpoints SET confirmed_at = ?", Timestamp.from(now.minusSeconds(30)));
        seeded = true;
    }

    private HttpResponse<String> query(String list, UUID seed) throws Exception {
        String body = "{\"accountId\":\"" + ACCOUNT + "\",\"profileId\":\"" + PROFILE + "\",\"list\":\"" + list
                + "\",\"seedContentId\":" + (seed == null ? "null" : "\"" + seed + "\"") + ",\"limit\":100}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v2/recommendations/query"))
                .header("Authorization", "Bearer " + ServiceTokens.token("recommendations:read"))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("X-Correlation-ID", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyListStaysInsideCoresBudget() throws Exception {
        seed();
        UUID seedTitle = jdbc.queryForObject("SELECT content_id FROM catalog_nodes WHERE kind = 'MOVIE' "
                // Two distinct genres: a seed whose two genres coincide shares them with only 99 other titles.
                + "AND genres[1] <> genres[2] ORDER BY content_id OFFSET 1000 LIMIT 1", UUID.class);
        for (String list : List.of("PERSONAL", "SIMILAR", "POPULAR", "NEW")) {
            UUID seed = list.equals("SIMILAR") ? seedTitle : null;
            for (int warm = 0; warm < 5; warm++) { assertThat(query(list, seed).statusCode()).isEqualTo(200); }
            List<Long> millis = new ArrayList<>();
            JsonNode last = null;
            for (int run = 0; run < 20; run++) {
                long started = System.nanoTime();
                var response = query(list, seed);
                millis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body().length()).isLessThanOrEqualTo(65_536);
                last = json.readTree(response.body());
            }
            Collections.sort(millis);
            System.out.printf("RA05.4 load %s: median %d ms, p95 %d ms, max %d ms%n", list, millis.get(10),
                    millis.get(18), millis.get(19));
            assertThat(millis.get(19)).as("slowest of 20 %s requests (ms)", list).isLessThan(BUDGET.toMillis());
            assertThat(last.path("items").size()).as(list).isEqualTo(100);
        }
    }

    /**
     * A home page asks for three lists at once and Core lets 16 Analytics calls run in parallel: 16 concurrent
     * requests (all four lists) must each still answer inside the budget.
     */
    @Test
    void sixteenConcurrentRequestsStayInsideCoresBudget() throws Exception {
        seed();
        UUID seedTitle = jdbc.queryForObject("SELECT content_id FROM catalog_nodes WHERE kind = 'MOVIE' "
                + "AND genres[1] <> genres[2] ORDER BY content_id OFFSET 1000 LIMIT 1", UUID.class);
        List<String> lists = List.of("PERSONAL", "SIMILAR", "POPULAR", "NEW");
        for (int warm = 0; warm < 3; warm++) {
            for (String list : lists) { query(list, list.equals("SIMILAR") ? seedTitle : null); }
        }
        for (int round = 0; round < 3; round++) {
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<Long>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    String list = lists.get(i % lists.size());
                    UUID seed = list.equals("SIMILAR") ? seedTitle : null;
                    futures.add(pool.submit(() -> {
                        start.await();
                        long began = System.nanoTime();
                        assertThat(query(list, seed).statusCode()).isEqualTo(200);
                        return Duration.ofNanos(System.nanoTime() - began).toMillis();
                    }));
                }
                start.countDown();
                List<Long> millis = new ArrayList<>();
                for (var future : futures) { millis.add(future.get()); }
                Collections.sort(millis);
                System.out.printf("RA05.4 load 16 concurrent, round %d: median %d ms, max %d ms%n", round,
                        millis.get(8), millis.get(15));
                assertThat(millis.get(15)).as("slowest of 16 concurrent requests (ms)").isLessThan(BUDGET.toMillis());
            }
        }
    }

    @Test
    void completedTitlesAreLeftOutAtScale() throws Exception {
        seed();
        // Every watched movie's latest position is 500 s of a 3,600 s movie: none is completed, all stay eligible.
        jdbc.update("UPDATE catalog_nodes SET duration_seconds = 500 WHERE content_id IN "
                + "(SELECT content_id FROM playback_facts WHERE profile_id = ? GROUP BY content_id LIMIT 50)", PROFILE);
        try {
            var completed = jdbc.queryForList("SELECT content_id FROM catalog_nodes WHERE duration_seconds = 500", UUID.class);
            for (String list : List.of("PERSONAL", "NEW")) {
                var items = json.readTree(query(list, null).body()).path("items");
                for (JsonNode item : items) {
                    assertThat(completed).as(list).doesNotContain(UUID.fromString(item.path("contentId").stringValue()));
                }
            }
        } finally {
            jdbc.update("UPDATE catalog_nodes SET duration_seconds = 3600 WHERE duration_seconds = 500");
        }
    }
}
