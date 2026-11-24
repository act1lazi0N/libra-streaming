package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.RecommendationFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real PostgreSQL through InternalApiFixture's Testcontainers; MockMvc retains the real security chain. */
@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class RecommendationListApiIntegrationTest extends InternalApiFixture {
    private static final String LIST = "/internal/v2/recommendations/query";
    private static final String CORRELATION = "12345678-1234-4234-8234-123456789abc";
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired EventIntake intake;
    private RecommendationFixtures events;
    private final UUID account = UUID.randomUUID();
    private final UUID profile = UUID.randomUUID();
    private final Instant at = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
    private Instant checkpoint;
    private boolean checkpointWrites;

    @BeforeEach
    void setup() {
        checkpointWrites = false;
        clearFacts();
        events = new RecommendationFixtures(intake);
    }

    @AfterEach
    void cleanup() {
        clearFacts();
        if (checkpointWrites) { jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL"); }
    }

    @Override boolean expectsCheckpointWrites() { return checkpointWrites; }

    private void clearFacts() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, playback_facts, playback_sessions, "
                + "processed_events, catalog_nodes, profiles");
    }

    @Test
    void everyListNeedsAllThreeCheckpoints() throws Exception {
        for (InputTopic missing : InputTopic.values()) {
            jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL");
            for (InputTopic topic : InputTopic.values()) {
                if (topic != missing) { confirm(topic); }
            }
            for (String list : List.of("PERSONAL", "SIMILAR", "POPULAR", "NEW")) {
                problem(query(body(list, list.equals("SIMILAR") ? UUID.randomUUID() : null, 100)),
                        503, "ANALYTICS_NOT_READY");
            }
        }
        jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL");
        ready();
        assertThat(snapshot("NEW", null, 100).path("items")).isEmpty();
    }

    @Test
    void personalMatchesV1OrderAndReasonsAreTheSelectingTiers() throws Exception {
        UUID history = movie(at.minusSeconds(100), "drama");
        UUID genre = movie(at, "drama");
        UUID popular = movie(at.minusSeconds(200), "comedy");
        UUID newest = movie(at.plusSeconds(20), "horror");
        events.profile(account, profile, false);
        events.watch(account, profile, history, 10000, 115200, at);
        watch(popular, at);
        ready();
        for (int limit : new int[] {1, 2, 100}) {
            JsonNode v2 = snapshot("PERSONAL", null, limit);
            var v1 = mvc.perform(post(RECOMMENDATIONS)
                    .header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE)))
                    .contentType("application/json").content("{\"accountId\":\"" + account
                            + "\",\"profileId\":\"" + profile + "\",\"limit\":" + limit + "}"))
                    .andReturn().getResponse();
            assertThat(v1.getStatus()).isEqualTo(200);
            var v1Ids = new ArrayList<String>();
            JSON.readTree(v1.getContentAsString()).path("contentIds").forEach(id -> v1Ids.add(id.asText()));
            assertThat(ids(v2)).containsExactlyElementsOf(v1Ids);
        }
        JsonNode all = snapshot("PERSONAL", null, 100);
        assertThat(ids(all)).containsExactly(genre.toString(), popular.toString(), newest.toString());
        assertThat(reasons(all)).containsExactly("GENRE_INTEREST", "POPULAR", "NEWLY_PUBLISHED");
    }

    @Test
    void similarUsesCosineWithoutSeedUnrelatedOrFillAndUnknownOrUnpublishedSeedsAreEmpty() throws Exception {
        UUID seed = movie(at, "drama", "crime");
        UUID exact = movie(at.minusSeconds(10), "crime", "drama");
        UUID single = movie(at, "drama");
        UUID diluted = movie(at.plusSeconds(10), "crime", "horror", "comedy");
        movie(at.plusSeconds(20), "comedy");
        UUID unpublished = events.title("MOVIE", null, "drama", at, "READY", false);
        watch(diluted, at);
        ready();
        JsonNode result = snapshot("SIMILAR", seed, 100);
        assertThat(ids(result)).containsExactly(exact.toString(), single.toString(), diluted.toString());
        assertThat(reasons(result)).containsOnly("SIMILAR_GENRES");
        assertThat(ids(snapshot("SIMILAR", seed, 1))).containsExactly(exact.toString());
        assertThat(ids(snapshot("SIMILAR", UUID.randomUUID(), 100))).isEmpty();
        assertThat(ids(snapshot("SIMILAR", unpublished, 100))).isEmpty();
        jdbc.update("UPDATE catalog_nodes SET genres = '{}' WHERE content_id = ?", seed);
        assertThat(ids(snapshot("SIMILAR", seed, 100))).isEmpty();
    }

    @Test
    void popularUsesQualifiedSevenDayDistinctAccountsAndEpisodesCollapseToSeries() throws Exception {
        UUID series = events.title("SERIES", null, "drama", at, null, true);
        UUID season = events.title("SEASON", series, "drama", at, null, true);
        UUID ep1 = events.title("EPISODE", season, "drama", at, "READY", true);
        UUID ep2 = events.title("EPISODE", season, "drama", at, "READY", true);
        UUID movie = movie(at.minusSeconds(1), "drama");
        UUID old = movie(at.plusSeconds(1), "drama");
        UUID unqualified = movie(at.plusSeconds(2), "drama");
        UUID viewer = UUID.randomUUID();
        events.watch(viewer, UUID.randomUUID(), ep1, 30000, 30000, at);
        events.watch(viewer, UUID.randomUUID(), ep2, 30000, 30000, at);
        watch(movie, at);
        watch(movie, at);
        watch(old, at.minusSeconds(8 * 86400));
        events.watch(UUID.randomUUID(), UUID.randomUUID(), unqualified, 10000, 10000, at);
        ready();
        JsonNode result = snapshot("POPULAR", null, 100);
        assertThat(ids(result)).containsExactly(movie.toString(), series.toString());
        assertThat(reasons(result)).containsOnly("POPULAR");
        assertThat(ids(snapshot("POPULAR", null, 1))).containsExactly(movie.toString());
    }

    @Test
    void newIncludesEveryPlayableTitleNewestFirstAndCompletedMoviesAndSeriesLeaveAllLists() throws Exception {
        UUID seed = movie(at.minusSeconds(100), "drama");
        UUID older = movie(at, "drama");
        UUID newer = movie(at.plusSeconds(10), "drama");
        UUID done = movie(at.plusSeconds(20), "drama");
        UUID series = events.title("SERIES", null, "drama", at.plusSeconds(30), null, true);
        UUID season = events.title("SEASON", series, "drama", at, null, true);
        UUID episode = events.title("EPISODE", season, "drama", at, "READY", true);
        events.title("MOVIE", null, "drama", at.plusSeconds(100), null, true);
        events.title("MOVIE", null, "drama", at.plusSeconds(110), "READY", false);
        events.title("SERIES", null, "drama", at.plusSeconds(120), null, true);
        ready();
        assertThat(ids(snapshot("NEW", null, 100))).containsExactly(series.toString(), done.toString(),
                newer.toString(), older.toString(), seed.toString());
        events.profile(account, profile, false);
        events.watch(account, profile, done, 30000, 115200, at);
        events.watch(account, profile, episode, 30000, 115200, at);
        for (String list : List.of("PERSONAL", "SIMILAR", "POPULAR", "NEW")) {
            assertThat(ids(snapshot(list, list.equals("SIMILAR") ? seed : null, 100)))
                    .doesNotContain(done.toString(), series.toString(), episode.toString(), season.toString());
        }
        JsonNode result = snapshot("NEW", null, 100);
        assertThat(ids(result)).containsExactly(newer.toString(), older.toString(), seed.toString());
        assertThat(reasons(result)).containsOnly("NEWLY_PUBLISHED");
    }

    @Test
    void wrongScopeIsDeniedBeforeBodyRead() throws Exception {
        var guarded = new AtomicReference<MockHttpServletRequest>();
        var response = mvc.perform(post(LIST)
                .header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE)))
                .header("X-Correlation-ID", CORRELATION).contentType("application/json")
                .content("private-body-never-echo".repeat(100))
                .with(request -> { guarded.set(spy(request)); return guarded.get(); })).andReturn().getResponse();
        problem(response, 403, "ACCESS_DENIED");
        verify(guarded.get(), never()).getInputStream();
        verify(guarded.get(), never()).getReader();
        verifyNoInteractions(freshness);
    }

    @Test
    void everyListQueryFixtureIsValidatedBeforeAndAfterReadiness() throws Exception {
        Path directory = root().resolve("contracts/fixtures/analytics-v2");
        JsonNode manifest = JSON.readTree(Files.readString(directory.resolve("manifest.json")));
        for (boolean ready : List.of(false, true)) {
            if (ready) { ready(); }
            int cases = 0;
            for (JsonNode entry : manifest.path("fixtures")) {
                if (!entry.path("id").asText().startsWith("list-query-")) { continue; }
                String body = Files.readString(directory.resolve(entry.path("file").asText()));
                for (var placeholder : manifest.path("placeholders").properties()) {
                    body = body.replace("{{" + placeholder.getKey() + "}}", placeholder.getValue().asText());
                }
                assertThat(body).doesNotContain("{{");
                var response = query(body);
                if (entry.path("expect").asText().equals("valid")) {
                    if (ready) { assertThat(response.getStatus()).as(entry.path("id").asText()).isEqualTo(200); }
                    else { problem(response, 503, "ANALYTICS_NOT_READY"); }
                } else { problem(response, 400, "INVALID_REQUEST"); }
                cases++;
            }
            assertThat(cases).isEqualTo(13);
        }
    }

    private JsonNode snapshot(String list, UUID seed, int limit) throws Exception {
        var response = query(body(list, seed, limit));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray()).hasSizeLessThanOrEqualTo(65536);
        assertThat(response.getContentAsString()).doesNotContain(account.toString());
        JsonNode result = JSON.readTree(response.getContentAsString());
        JsonNode contract = JSON.readTree(Files.readString(root().resolve("contracts/http/analytics-internal.v2.json")));
        validate(result, contract.path("components").path("schemas").path("RecommendationList"), contract);
        assertThat(result.path("profileId").asText()).isEqualTo(profile.toString());
        assertThat(result.path("list").asText()).isEqualTo(list);
        assertThat(result.path("algorithmVersion").asText()).isEqualTo("rules-v1");
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(checkpoint.truncatedTo(ChronoUnit.SECONDS));
        assertThat(ids(result)).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(limit);
        return result;
    }

    private MockHttpServletResponse query(String body) throws Exception {
        var beforeRequest = rows();
        var response = mvc.perform(post(LIST).header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE)))
                .header("X-Correlation-ID", CORRELATION).contentType("application/json").accept("application/json")
                .content(body)).andReturn().getResponse();
        assertThat(rows()).as("Queries never advance freshness").isEqualTo(beforeRequest);
        return response;
    }

    private String body(String list, UUID seed, int limit) {
        return "{\"accountId\":\"" + account + "\",\"profileId\":\"" + profile + "\",\"list\":\"" + list
                + "\",\"seedContentId\":" + (seed == null ? "null" : "\"" + seed + "\"") + ",\"limit\":" + limit + "}";
    }

    private UUID movie(Instant publishedAt, String... genres) {
        UUID id = events.title("MOVIE", null, genres[0], publishedAt, "READY", true);
        jdbc.update("UPDATE catalog_nodes SET genres = ? WHERE content_id = ?", genres, id);
        return id;
    }

    private void watch(UUID title, Instant when) {
        events.watch(UUID.randomUUID(), UUID.randomUUID(), title, 30000, 30000, when);
    }

    private void ready() { confirm(InputTopic.PLAYBACK, InputTopic.CATALOG, InputTopic.PROFILES); }

    private void confirm(InputTopic... topics) {
        checkpointWrites = true;
        checkpoint = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456000);
        for (var topic : topics) { assertThat(freshness.confirm(topic, checkpoint)).isTrue(); }
        clearInvocations(freshness);
    }

    private static List<String> ids(JsonNode result) { return values(result, "contentId"); }
    private static List<String> reasons(JsonNode result) { return values(result, "reason"); }
    private static List<String> values(JsonNode result, String field) {
        var values = new ArrayList<String>();
        result.path("items").forEach(item -> values.add(item.path(field).asText()));
        return values;
    }

    private static void problem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).doesNotContain("private-body-never-echo");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "code", "correlationId");
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("correlationId").asText()).isEqualTo(CORRELATION);
    }

    private static Path root() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("contracts/http/analytics-internal.v2.json"))) { return path; }
        }
        throw new IllegalStateException("Analytics V2 contract not found");
    }

    /** Every keyword used by RecommendationList, including its local reference; unknown keywords fail closed. */
    private static void validate(JsonNode value, JsonNode schema, JsonNode contract) {
        if (schema.has("$ref")) {
            assertThat(schema.path("$ref").asText()).startsWith("#/");
            validate(value, contract.at(schema.path("$ref").asText().substring(1)), contract);
            return;
        }
        assertThat(schema.propertyNames()).isSubsetOf("type", "additionalProperties", "required", "properties",
                "maxItems", "items", "format", "enum", "pattern");
        switch (schema.path("type").asText()) {
            case "object" -> {
                assertThat(value.isObject()).isTrue();
                assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
                schema.path("required").forEach(name -> assertThat(value.has(name.asText())).isTrue());
                assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(schema.path("properties").propertyNames());
                schema.path("properties").properties().forEach(field -> validate(value.path(field.getKey()), field.getValue(), contract));
            }
            case "array" -> {
                assertThat(value.isArray()).isTrue();
                assertThat(value.size()).isLessThanOrEqualTo(schema.path("maxItems").asInt());
                value.forEach(item -> validate(item, schema.path("items"), contract));
            }
            case "string" -> {
                assertThat(value.isString()).isTrue();
                if (schema.has("format")) {
                    switch (schema.path("format").asText()) {
                        case "uuid" -> assertThat(UUID.fromString(value.asText()).toString()).isEqualTo(value.asText());
                        case "date-time" -> Instant.parse(value.asText());
                        default -> throw new AssertionError("Unsupported format");
                    }
                }
                if (schema.has("pattern")) { assertThat(value.asText()).matches(schema.path("pattern").asText()); }
                if (schema.has("enum")) {
                    var allowed = new ArrayList<String>();
                    schema.path("enum").forEach(item -> allowed.add(item.asText()));
                    assertThat(value.asText()).isIn(allowed);
                }
            }
            default -> throw new AssertionError("Unsupported schema type");
        }
    }
}
