package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.RecommendationFixtures;
import com.libra.streaming.recommendation.preferences.application.ProfilePreferences;
import com.libra.streaming.recommendation.preferences.domain.InterestRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class RecommendationsApiIntegrationTest extends InternalApiFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CORRELATION = "12345678-1234-4234-8234-123456789abc";
    @Autowired EventIntake intake;
    @Autowired ProfilePreferences preferences;
    private RecommendationFixtures events;
    private boolean checkpointWrites;
    private final UUID account = UUID.randomUUID();
    private final UUID profile = UUID.randomUUID();
    private final Instant at = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
    private Instant checkpoint;

    @BeforeEach
    void setup() {
        checkpointWrites = false;
        clearFacts();
        events = new RecommendationFixtures(intake);
    }

    @AfterEach
    void resetOwnData() {
        clearFacts();
        if (checkpointWrites) { jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL"); }
    }

    @Override boolean expectsCheckpointWrites() { return checkpointWrites; }

    private void clearFacts() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, playback_facts, playback_sessions, "
                + "processed_events, catalog_nodes, profiles");
    }

    @Test
    void noCheckpointReturnsNotReady() throws Exception {
        problem(query(body(account, profile, 20)), 503, "ANALYTICS_NOT_READY");
    }

    @Test
    void playbackAndCatalogWithoutProfilesRemainNotReady() throws Exception {
        confirm(InputTopic.PLAYBACK, InputTopic.CATALOG);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM input_checkpoints WHERE confirmed_at IS NULL",
                Integer.class)).isEqualTo(1);
        problem(query(body(account, profile, 20)), 503, "ANALYTICS_NOT_READY");
    }

    @Test
    void newProfileGetsPopularByViewersThenNewestAndAContractValidSnapshot() throws Exception {
        UUID popular = movie("drama", at.minusSeconds(100));
        UUID lessPopular = movie("comedy", at.minusSeconds(50));
        UUID newest = movie("horror", at.plusSeconds(20));
        UUID older = movie("horror", at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), popular, 30000, 30000, at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), popular, 30000, 30000, at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), lessPopular, 30000, 30000, at);
        events.profile(account, profile, false);
        ready();
        assertThat(ids(account, profile, 20)).containsExactly(popular, lessPopular, newest, older);
        assertThat(ids(account, profile, 2)).containsExactly(popular, lessPopular);
    }

    @Test
    void genreHistorySeparatesProfilesAndWrongAccountGetsColdStartWhileCompletedMoviesAreAbsent() throws Exception {
        UUID xHistory = movie("drama", at.minusSeconds(100));
        UUID yHistory = movie("comedy", at.minusSeconds(90));
        UUID x = movie("drama", at);
        UUID y = movie("comedy", at.plusSeconds(10));
        UUID otherProfile = UUID.randomUUID();
        events.profile(account, profile, false);
        events.profile(account, otherProfile, false);
        events.watch(account, profile, xHistory, 10000, 114000, at);
        events.watch(account, otherProfile, yHistory, 10000, 114000, at);
        ready();
        List<UUID> first = ids(account, profile, 20);
        List<UUID> second = ids(account, otherProfile, 20);
        assertThat(first).startsWith(x).doesNotContain(xHistory);
        assertThat(second).startsWith(y).doesNotContain(yHistory);
        assertThat(first).isNotEqualTo(second);
        UUID wrongAccount = UUID.randomUUID();
        assertThat(ids(wrongAccount, profile, 20)).containsExactly(y, x, yHistory, xHistory)
                .isEqualTo(ids(wrongAccount, UUID.randomUUID(), 20));
    }

    @Test
    void severalWatchedEpisodesYieldOneSeriesAndNeverEpisodeOrSeasonIds() throws Exception {
        UUID series = events.title("SERIES", null, "drama", at, null, true);
        UUID season = events.title("SEASON", series, "drama", at, null, true);
        UUID first = events.title("EPISODE", season, "drama", at, "READY", true);
        UUID second = events.title("EPISODE", season, "drama", at, "READY", true);
        events.watch(account, profile, first, 10000, 10000, at);
        events.watch(account, profile, second, 10000, 10000, at);
        ready();
        assertThat(ids(account, profile, 20)).containsExactly(series).doesNotContain(season, first, second);
    }

    @Test
    void invalidBodiesProduceClosedProblemsBeforeAndAfterReadiness() throws Exception {
        String valid = body(account, profile, 20);
        for (boolean ready : List.of(false, true)) {
            if (ready) { ready(); }
            for (String invalid : List.of("", "null", "[]", "{", "{}",
                    valid.replace("20}", "0}"), valid.replace("20}", "101}"),
                    valid.replace("20}", "1.5}"), valid.replace("20}", "\"20\"}"),
                    valid.replace(profile.toString(), "not-a-uuid"),
                    valid.replace("20}", "20,\"extra\":true}"),
                    valid.replace("20}", "20,\"limit\":1}"))) {
                problem(query(invalid), 400, "INVALID_REQUEST");
            }
            problem(query("x".repeat(2048)), 413, "PAYLOAD_TOO_LARGE");
            problem(request(valid, "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE");
        }
    }

    @Test
    void tombstonedProfileGetsExactlyUnknownProfilesColdStartIncludingItsCompletedMovie() throws Exception {
        UUID history = movie("drama", at.minusSeconds(100));
        UUID x = movie("drama", at);
        UUID newest = movie("comedy", at.plusSeconds(10));
        UUID popular = movie("comedy", at.minusSeconds(200));
        events.profile(account, profile, false);
        events.watch(account, profile, history, 10000, 115200, at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), popular, 30000, 30000, at);
        ready();
        assertThat(ids(account, profile, 20)).containsExactly(x, popular, newest);
        events.profile(account, profile, true);
        assertThat(preferences.interests(account, profile).isEmpty()).isTrue();
        assertThat(ids(account, profile, 20)).containsExactly(popular, newest, x, history)
                .isEqualTo(ids(account, UUID.randomUUID(), 20));
    }

    @Test
    void littleHistoryRanksUnwatchedGenreMatchesThenPopularByViewersThenNewlyPublished() throws Exception {
        UUID history = movie("drama", at.minusSeconds(100));
        UUID xOlder = movie("drama", at.minusSeconds(10));
        UUID xNewer = movie("drama", at);
        UUID popular = movie("comedy", at.minusSeconds(200));
        UUID lessPopular = movie("horror", at.plusSeconds(30));
        UUID newest = movie("comedy", at.plusSeconds(20));
        UUID older = movie("horror", at.plusSeconds(10));
        events.profile(account, profile, false);
        events.watch(account, profile, history, 10000, 115200, at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), popular, 30000, 30000, at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), popular, 30000, 30000, at);
        events.watch(UUID.randomUUID(), UUID.randomUUID(), lessPopular, 30000, 30000, at);
        ready();
        assertThat(ids(account, profile, 20))
                .containsExactly(xNewer, xOlder, popular, lessPopular, newest, older);
    }

    @Test
    void repeatViewingCapsInterestAtItemDurationSoLongerYWatchOutranksRepeatedX() throws Exception {
        UUID xHistory = events.title("MOVIE", null, "drama", at.minusSeconds(100), "READY", true, 60);
        UUID yHistory = events.title("MOVIE", null, "comedy", at.minusSeconds(90), "READY", true, 3600);
        UUID x = movie("drama", at.plusSeconds(10));
        UUID y = movie("comedy", at);
        events.profile(account, profile, false);
        for (int session = 0; session < 10; session++) {
            UUID sessionId = UUID.randomUUID();
            for (int sequence = 1; sequence <= 2; sequence++) {
                events.accept(events.playback(account, profile, xHistory, sessionId, sequence,
                        30000, sequence * 30000L, sequence * 30000L,
                        at.plusSeconds(session * 60L + sequence * 30L)));
            }
        }
        UUID ySession = UUID.randomUUID();
        for (int sequence = 1; sequence <= 4; sequence++) {
            events.accept(events.playback(account, profile, yHistory, ySession, sequence,
                    30000, sequence * 30000L, sequence * 30000L,
                    at.plusSeconds(600 + sequence * 30L)));
        }
        // A seek to the end closes Y without adding watched time beyond the two accepted minutes.
        events.accept(events.playback(account, profile, yHistory, ySession, 5,
                0, 120000, 3456000, at.plusSeconds(750)));
        assertThat(jdbc.queryForObject("SELECT sum(accepted_watched_ms) FROM profile_watch_days "
                + "WHERE profile_id = ? AND content_id = ?", Long.class, profile, xHistory)).isEqualTo(600000L);
        assertThat(jdbc.queryForObject("SELECT sum(accepted_watched_ms) FROM profile_watch_days "
                + "WHERE profile_id = ? AND content_id = ?", Long.class, profile, yHistory)).isEqualTo(120000L);
        assertThat(preferences.rules()).isEqualTo(InterestRules.DEFAULT);
        assertThat(preferences.interests(account, profile).weights())
                .containsExactlyInAnyOrderEntriesOf(Map.of("drama", 60000.0, "comedy", 120000.0));
        ready();
        assertThat(ids(account, profile, 20)).containsExactly(y, x);
    }

    private void ready() { confirm(InputTopic.PLAYBACK, InputTopic.CATALOG, InputTopic.PROFILES); }

    private void confirm(InputTopic... topics) {
        checkpointWrites = true;
        checkpoint = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456000);
        for (InputTopic topic : topics) { assertThat(freshness.confirm(topic, checkpoint)).isTrue(); }
        clearInvocations(freshness);
    }

    private UUID movie(String genre, Instant publishedAt) {
        return events.title("MOVIE", null, genre, publishedAt, "READY", true);
    }

    private List<UUID> ids(UUID owner, UUID requestedProfile, int limit) throws Exception {
        var response = query(body(owner, requestedProfile, limit));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Encoding")).isNull();
        assertThat(response.getContentAsByteArray()).hasSizeLessThanOrEqualTo(65536);
        JsonNode snapshot = JSON.readTree(response.getContentAsString());
        validate(snapshot, candidateSchema());
        assertThat(snapshot.path("profileId").asText()).isEqualTo(requestedProfile.toString());
        Instant updated = Instant.parse(snapshot.path("updatedAt").asText());
        assertThat(updated).isEqualTo(checkpoint.truncatedTo(ChronoUnit.SECONDS)).isBeforeOrEqualTo(Instant.now());
        assertThat(updated.getNano()).isZero();
        List<UUID> ids = new ArrayList<>();
        snapshot.path("contentIds").forEach(id -> ids.add(UUID.fromString(id.asText())));
        assertThat(ids).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(limit);
        List<UUID> titles = jdbc.queryForList("SELECT content_id FROM catalog_nodes WHERE kind IN ('MOVIE', 'SERIES')",
                UUID.class);
        assertThat(titles).containsAll(ids);
        return ids;
    }

    private MockHttpServletResponse query(String body) throws Exception { return request(body, "application/json"); }

    private MockHttpServletResponse request(String body, String mediaType) throws Exception {
        var beforeRequest = rows();
        var response = mvc.perform(post(RECOMMENDATIONS)
                .header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE)))
                .header("X-Correlation-ID", CORRELATION).accept("application/json")
                .contentType(mediaType).content(body)).andReturn().getResponse();
        assertThat(rows()).as("recommendation requests never write checkpoints").isEqualTo(beforeRequest);
        return response;
    }

    private static String body(UUID account, UUID profile, int limit) {
        return "{\"accountId\":\"" + account + "\",\"profileId\":\"" + profile + "\",\"limit\":" + limit + "}";
    }

    private static void problem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "type", "title", "status", "detail", "code", "correlationId");
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("correlationId").asText()).isEqualTo(CORRELATION);
    }

    private static JsonNode candidateSchema() throws Exception {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path contract = path.resolve("contracts/http/analytics-internal.v1.json");
            if (Files.isRegularFile(contract)) {
                return JSON.readTree(Files.readString(contract)).path("components").path("schemas").path("CandidateResponse");
            }
        }
        throw new IllegalStateException("Analytics HTTP contract not found");
    }

    /** Validates every keyword used by CandidateResponse; unexpected schema types fail closed. */
    private static void validate(JsonNode value, JsonNode schema) {
        switch (schema.path("type").asText()) {
            case "object" -> {
                assertThat(value.isObject()).isTrue();
                schema.path("required").forEach(name -> assertThat(value.has(name.asText())).isTrue());
                assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
                assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(schema.path("properties").propertyNames());
                schema.path("properties").properties().forEach(field -> validate(value.path(field.getKey()), field.getValue()));
            }
            case "array" -> {
                assertThat(value.isArray()).isTrue();
                assertThat(value.size()).isLessThanOrEqualTo(schema.path("maxItems").asInt());
                value.forEach(item -> validate(item, schema.path("items")));
            }
            case "string" -> {
                assertThat(value.isString()).isTrue();
                switch (schema.path("format").asText()) {
                    case "uuid" -> assertThat(UUID.fromString(value.asText()).toString()).isEqualTo(value.asText());
                    case "date-time" -> Instant.parse(value.asText());
                    default -> throw new AssertionError("Unsupported string format in CandidateResponse");
                }
            }
            default -> throw new AssertionError("Unsupported type in CandidateResponse");
        }
    }
}
