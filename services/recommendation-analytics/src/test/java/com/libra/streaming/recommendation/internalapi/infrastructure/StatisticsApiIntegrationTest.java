package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.CoreEventDecoder;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real decoder, transactional intake, PostgreSQL rollups and authenticated HTTP handlers. */
@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class StatisticsApiIntegrationTest extends InternalApiFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired EventIntake intake;
    @Autowired CoreEventDecoder decoder;
    private boolean checkpointWrites;

    @BeforeEach
    void clearFacts() {
        checkpointWrites = false;
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, playback_facts, playback_sessions, processed_events");
    }

    @AfterEach
    void resetOwnData() {
        jdbc.execute("TRUNCATE profile_watch_days, statistics_account_days, playback_facts, playback_sessions, processed_events");
        if (checkpointWrites) { jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL"); }
    }

    @Override boolean expectsCheckpointWrites() { return checkpointWrites; }

    @Test
    void noCheckpointIsNotReadyRatherThanZero() throws Exception {
        problem(query(statisticsBody()), 503, "ANALYTICS_NOT_READY");
    }

    @Test
    void playbackAloneMakesAnEmptyProjectionReady() throws Exception {
        Instant checkpoint = Instant.now().minusSeconds(60);
        confirm(checkpoint);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM input_checkpoints WHERE confirmed_at IS NULL",
                Integer.class)).isEqualTo(2);
        assertSnapshot(query(statisticsBody()), statisticsBody(), checkpoint, 0, 0, 0);
    }

    @Test
    void realFactsHaveExactTotalsAndRepeatedQueriesNeverWrite() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant at = today.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC);
        UUID account = UUID.randomUUID();
        accept(account, UUID.randomUUID(), 30000, at);
        accept(account, UUID.randomUUID(), 30000, at.plusSeconds(1));
        accept(UUID.randomUUID(), UUID.randomUUID(), 30000, at.plusSeconds(2));
        // A nonqualifying session adds accepted time but no view or unique viewer.
        accept(UUID.randomUUID(), UUID.randomUUID(), 10000, at.plusSeconds(3));
        Instant checkpoint = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456000);
        confirm(checkpoint);
        var checkpoints = rows();
        var rollups = rollups();
        String request = statisticsBody();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertSnapshot(query(request), request, checkpoint, 3, 2, 100000);
            assertThat(rows()).isEqualTo(checkpoints);
            assertThat(rollups()).isEqualTo(rollups);
        }
    }

    @Test
    void a901SecondOldCheckpointIsStillServed() throws Exception {
        Instant checkpoint = Instant.now().minusSeconds(901);
        confirm(checkpoint);
        assertSnapshot(query(statisticsBody()), statisticsBody(), checkpoint, 0, 0, 0);
    }

    @ParameterizedTest(name = "{0}, checkpoint={3}")
    @MethodSource("manifestQueries")
    void everyManifestQueryHonorsValidationBeforeReadiness(String id, String expectation, String body,
            boolean ready) throws Exception {
        if (ready) { confirm(Instant.now().minusSeconds(60)); }
        var response = query(body);
        switch (expectation) {
            case "valid" -> {
                if (ready) { assertThat(response.getStatus()).as(id).isEqualTo(200); }
                else { problem(response, 503, "ANALYTICS_NOT_READY"); }
            }
            case "schema-invalid", "semantic-invalid" -> {
                problem(response, 400, "INVALID_REQUEST");
                String problemBody = response.getContentAsString();
                assertThat(problemBody).as(id).doesNotContain(body);
                // Neither a date nor an extra request value may leak into the fixed problem text.
                JSON.readTree(body).properties().forEach(field ->
                        assertThat(problemBody).doesNotContain(field.getValue().asText()));
                verifyNoInteractions(freshness);
            }
            default -> throw new AssertionError("Unhandled manifest expectation: " + expectation);
        }
    }

    static Stream<Arguments> manifestQueries() throws Exception {
        Path directory = fixtureDirectory();
        JsonNode manifest = JSON.readTree(Files.readString(directory.resolve("manifest.json")));
        // "tomorrow" is accepted in the last 5 s of a UTC day (skew margin) and "today" changes at midnight. The
        // cases take a few seconds, so start them clear of the boundary instead of failing intermittently.
        Instant now = Instant.now();
        Instant midnight = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (now.plusSeconds(60).isAfter(midnight)) { Thread.sleep(java.time.Duration.between(now, midnight).plusSeconds(1)); }
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode entry : manifest.path("fixtures")) {
            String id = entry.path("id").asText();
            if (!id.startsWith("statistics-query-")) { continue; }
            String body = Files.readString(directory.resolve(entry.path("file").asText()));
            for (var placeholder : manifest.path("placeholders").properties()) {
                String value = switch (placeholder.getKey()) {
                    case "to" -> to.toString();
                    case "from" -> to.minusDays(6).toString();
                    case "tomorrow" -> to.plusDays(1).toString();
                    default -> placeholder.getValue().asText();
                };
                body = body.replace("{{" + placeholder.getKey() + "}}", value);
            }
            assertThat(body).doesNotContain("{{");
            for (boolean ready : List.of(false, true)) {
                cases.add(Arguments.of(id, entry.path("expect").asText(), body, ready));
            }
        }
        assertThat(cases).isNotEmpty();
        return cases.stream();
    }

    @Test
    void mediaSizeAndWindowFailuresAreClosedCorrelatedProblems() throws Exception {
        problem(request(statisticsBody(), "text/plain", STATISTICS_SCOPE), 415, "UNSUPPORTED_MEDIA_TYPE");
        problem(query("x".repeat(2048)), 413, "PAYLOAD_TOO_LARGE");
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        problem(query("{\"from\":\"" + to.minusDays(31) + "\",\"to\":\"" + to + "\"}"),
                400, "INVALID_REQUEST");
        verifyNoInteractions(freshness);
    }

    @Test
    void wrongScopeRejectsBeforeReadingTheBody() throws Exception {
        var guarded = new AtomicReference<MockHttpServletRequest>();
        var response = mvc.perform(post(STATISTICS)
                .header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE)))
                .header("X-Correlation-ID", CORRELATION).accept("application/json")
                .contentType("application/json").content("unparsed-private-request".repeat(100))
                .with(request -> {
                    guarded.set(spy(request));
                    return guarded.get();
                })).andReturn().getResponse();
        problem(response, 403, "ACCESS_DENIED");
        verify(guarded.get(), never()).getInputStream();
        verify(guarded.get(), never()).getReader();
        verifyNoInteractions(freshness);
    }

    private void confirm(Instant at) {
        checkpointWrites = true;
        assertThat(freshness.confirm(InputTopic.PLAYBACK, at)).isTrue();
        clearInvocations(freshness);
    }

    private void accept(UUID account, UUID profile, long watched, Instant occurredAt) {
        var event = new IngestionFixtures().playback(1, watched, watched);
        event.root().put("occurredAt", occurredAt.toString());
        event.payload().put("accountId", account.toString()).put("profileId", profile.toString());
        assertThat(decoder.decode(event.topic(), event.key(), event.json())).isInstanceOf(PlaybackProgress.class);
        assertThat(intake.accept(event.topic(), event.key(), event.json())).isEqualTo(EventIntake.Outcome.APPLIED);
    }

    private List<Map<String, Object>> rollups() {
        return jdbc.queryForList("SELECT * FROM statistics_account_days ORDER BY day, account_id");
    }

    private MockHttpServletResponse query(String body) throws Exception {
        return request(body, "application/json", STATISTICS_SCOPE);
    }

    private MockHttpServletResponse request(String body, String mediaType, String scope) throws Exception {
        return mvc.perform(post(STATISTICS).header("Authorization", bearer(ServiceTokens.token(scope)))
                .header("X-Correlation-ID", CORRELATION).accept("application/json")
                .contentType(mediaType).content(body)).andReturn().getResponse();
    }

    private static final String CORRELATION = "12345678-1234-4234-8234-123456789abc";

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

    private static void assertSnapshot(MockHttpServletResponse response, String request, Instant checkpoint,
            long views, long viewers, long watched) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Encoding")).isNull();
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "from", "to", "updatedAt", "qualifiedViews", "uniqueViewers", "acceptedWatchedMs");
        JsonNode query = JSON.readTree(request);
        assertThat(body.path("from")).isEqualTo(query.path("from"));
        assertThat(body.path("to")).isEqualTo(query.path("to"));
        assertThat(body.path("updatedAt").asText()).isEqualTo(checkpoint.truncatedTo(ChronoUnit.SECONDS).toString());
        assertThat(body.path("qualifiedViews").asLong()).isEqualTo(views);
        assertThat(body.path("uniqueViewers").asLong()).isEqualTo(viewers);
        assertThat(body.path("acceptedWatchedMs").asLong()).isEqualTo(watched);
    }

    private static Path fixtureDirectory() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path directory = path.resolve("contracts/fixtures/analytics-v1");
            if (Files.isRegularFile(directory.resolve("manifest.json"))) { return directory; }
        }
        throw new IllegalStateException("Analytics fixture manifest not found");
    }
}
