package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class StatisticsReportApiIntegrationTest extends InternalApiFixture {
    private static final String REPORT = "/internal/v2/statistics/query";
    private static final String CORRELATION = "12345678-1234-4234-8234-123456789abc";
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired Clock clock;
    @Autowired EventIntake intake;
    private final List<UUID> sessions = new ArrayList<>();
    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> profiles = new ArrayList<>();
    private final List<UUID> events = new ArrayList<>();
    private final List<UUID> nodes = new ArrayList<>();
    private boolean checkpointWrites;
    private Instant checkpoint;

    @Override boolean expectsCheckpointWrites() { return checkpointWrites; }

    @AfterEach
    void removeOwnData() {
        for (UUID session : sessions) {
            jdbc.update("DELETE FROM playback_facts WHERE session_id = ?", session);
            jdbc.update("DELETE FROM playback_sessions WHERE session_id = ?", session);
        }
        for (UUID event : events) { jdbc.update("DELETE FROM processed_events WHERE event_id = ?", event); }
        for (UUID account : accounts) { jdbc.update("DELETE FROM statistics_account_days WHERE account_id = ?", account); }
        for (UUID profile : profiles) { jdbc.update("DELETE FROM profile_watch_days WHERE profile_id = ?", profile); }
        for (UUID node : nodes) { jdbc.update("DELETE FROM catalog_nodes WHERE content_id = ?", node); }
        if (checkpointWrites) {
            jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL WHERE topic = ?", InputTopic.PLAYBACK.wireName());
        }
    }

    @Test
    void noCheckpointIsNotReady() throws Exception {
        problem(query(body(50)), 503, "ANALYTICS_NOT_READY", body(50));
    }

    @Test
    void checkpointWithNoDataIncludesEveryZeroDay() throws Exception {
        confirm();
        JsonNode report = report(50);
        counts(report.path("totals"), 0, 0, 0);
        assertThat(report.path("topContent").size()).isZero();
        for (JsonNode day : report.path("days")) { counts(day, 0, 0, 0); }
    }

    @Test
    void mapsMoviesKnownEpisodesAndUnknownPlacementWithoutLeakingIdentity() throws Exception {
        UUID movie = node("MOVIE", null);
        UUID series = node("SERIES", null);
        UUID season = node("SEASON", series);
        UUID episode1 = node("EPISODE", season);
        UUID episode2 = node("EPISODE", season);
        UUID unknown = node("EPISODE", UUID.randomUUID());
        UUID account = UUID.randomUUID();
        play(movie, account, 30000, at());
        play(episode1, account, 30000, at());
        play(episode2, account, 30000, at());
        play(unknown, UUID.randomUUID(), 30000, at());
        confirm();
        JsonNode report = report(50);
        counts(title(report, movie), 1, 1, 30000);
        counts(title(report, series), 2, 1, 60000);
        counts(title(report, unknown), 1, 1, 30000);
        assertThat(ids(report)).containsExactly(series.toString(),
                movie.toString().compareTo(unknown.toString()) < 0 ? movie.toString() : unknown.toString(),
                movie.toString().compareTo(unknown.toString()) < 0 ? unknown.toString() : movie.toString());
        assertThat(ids(report)).doesNotContain(season.toString(), episode1.toString(), episode2.toString());
        consistency(report);
    }

    @Test
    void midnightSessionSplitsWatchedTimeAndCountsViewOnQualifyingDay() throws Exception {
        UUID content = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant midnight = today().atStartOfDay().toInstant(ZoneOffset.UTC);
        fact(content, account, profile, session, 1, 10000, 10000, midnight.minusSeconds(10));
        fact(content, account, profile, session, 2, 20000, 30000, midnight.plusSeconds(20));
        confirm();
        JsonNode report = report(50);
        counts(report.path("days").get(0), 0, 0, 10000);
        counts(report.path("days").get(1), 1, 1, 20000);
        counts(title(report, content), 1, 1, 30000);
        consistency(report);
    }

    @Test
    void rankingUsesEveryTieBreakerAndLimitExcludesZeroTitles() throws Exception {
        UUID sameAccount = UUID.randomUUID();
        UUID mostViews = UUID.randomUUID();
        UUID moreViewers = UUID.randomUUID();
        UUID moreWatched = UUID.randomUUID();
        UUID low = UUID.fromString("7aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
        UUID high = UUID.fromString("8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2");
        UUID watchedOnly = UUID.randomUUID();
        UUID zero = node("MOVIE", null);
        for (int i = 0; i < 3; i++) { play(mostViews, sameAccount, 30000, at()); }
        play(moreViewers, UUID.randomUUID(), 30000, at());
        play(moreViewers, UUID.randomUUID(), 30000, at());
        play(moreWatched, sameAccount, 30000, at());
        play(moreWatched, sameAccount, 30000, at());
        play(moreWatched, sameAccount, 10000, at());
        for (UUID id : List.of(low, high)) {
            play(id, sameAccount, 30000, at());
            play(id, sameAccount, 30000, at());
        }
        for (int i = 0; i < 6; i++) { play(watchedOnly, sameAccount, 20000, at()); }
        play(zero, sameAccount, 0, at());
        confirm();
        JsonNode all = report(50);
        assertThat(ids(all)).containsExactly(mostViews.toString(), moreViewers.toString(), moreWatched.toString(),
                low.toString(), high.toString(), watchedOnly.toString());
        counts(title(all, watchedOnly), 0, 0, 120000);
        assertThat(ids(all)).doesNotContain(zero.toString());
        assertThat(ids(report(3))).containsExactly(mostViews.toString(), moreViewers.toString(), moreWatched.toString());
        consistency(all);
    }

    @Test
    void repeatedReportsLeaveCountsAndContentDigestsUnchanged() throws Exception {
        play(UUID.randomUUID(), UUID.randomUUID(), 30000, at());
        confirm();
        var before = databaseState();
        for (int i = 0; i < 3; i++) {
            consistency(report(50));
            assertThat(databaseState()).isEqualTo(before);
        }
    }

    @Test
    void failuresAreClosedCorrelatedAndNeverEchoInput() throws Exception {
        String marker = "private-body-never-echo";
        problem(request(marker, "text/plain", STATISTICS_SCOPE), 415, "UNSUPPORTED_MEDIA_TYPE", marker);
        problem(query(marker.repeat(100)), 413, "PAYLOAD_TOO_LARGE", marker);
        problem(query("{\"private\":\"" + marker + "\"}"), 400, "INVALID_REQUEST", marker);
        LocalDate to = today();
        for (String invalid : List.of(body(to.minusDays(31), to, 10), body(to, to.minusDays(1), 10),
                body(to, to.plusDays(2), 10))) {
            problem(query(invalid), 400, "INVALID_REQUEST", invalid);
        }
    }

    @Test
    void wrongScopeIsDeniedBeforeBodyRead() throws Exception {
        var guarded = new AtomicReference<MockHttpServletRequest>();
        var response = mvc.perform(post(REPORT)
                .header("Authorization", bearer(ServiceTokens.token(RECOMMENDATIONS_SCOPE)))
                .header("X-Correlation-ID", CORRELATION).accept("application/json")
                .contentType("application/json").content("private-body-never-echo".repeat(100))
                .with(request -> { guarded.set(spy(request)); return guarded.get(); })).andReturn().getResponse();
        problem(response, 403, "ACCESS_DENIED", "private-body-never-echo");
        verify(guarded.get(), never()).getInputStream();
        verify(guarded.get(), never()).getReader();
    }

    @Test
    void everyReportQueryFromV2ManifestIsValidated() throws Exception {
        Path directory = fixtureDirectory();
        JsonNode manifest = JSON.readTree(Files.readString(directory.resolve("manifest.json")));
        confirm();
        // Keep clock-relative future-date cases clear of the UTC skew margin, as the V1 corpus test does.
        Instant now = clock.instant();
        Instant midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (now.plusSeconds(30).isAfter(midnight)) {
            Thread.sleep(java.time.Duration.between(now, midnight).plusSeconds(1));
        }
        LocalDate to = today();
        int cases = 0;
        for (JsonNode entry : manifest.path("fixtures")) {
            String id = entry.path("id").asText();
            if (!id.startsWith("report-query-")) { continue; }
            String body = Files.readString(directory.resolve(entry.path("file").asText()));
            for (var placeholder : manifest.path("placeholders").properties()) {
                String value = switch (placeholder.getKey()) {
                    case "to" -> to.toString();
                    case "from" -> to.minusDays(6).toString();
                    case "tomorrow" -> to.plusDays(1).toString();
                    case "from32" -> to.minusDays(31).toString();
                    case "dayBefore" -> to.minusDays(7).toString();
                    case "day1", "day2", "day3", "day4", "day5", "day6", "day7" ->
                            to.minusDays(7 - Integer.parseInt(placeholder.getKey().substring(3))).toString();
                    case "now" -> clock.instant().toString();
                    case "updatedAt" -> clock.instant().minusSeconds(60).toString();
                    case "staleUpdatedAt" -> clock.instant().minusSeconds(901).toString();
                    case "futureUpdatedAt" -> clock.instant().plusSeconds(1).toString();
                    default -> placeholder.getValue().asText();
                };
                body = body.replace("{{" + placeholder.getKey() + "}}", value);
            }
            assertThat(body).as(id).doesNotContain("{{");
            var response = query(body);
            if (entry.path("expect").asText().equals("valid")) {
                assertThat(response.getStatus()).as(id).isEqualTo(200);
            } else { problem(response, 400, "INVALID_REQUEST", body); }
            cases++;
        }
        assertThat(cases).as("report-query-* fixtures in analytics-v2 manifest").isPositive();
    }

    private JsonNode report(int limit) throws Exception {
        var response = query(body(limit));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Encoding")).isNull();
        String text = response.getContentAsString();
        for (UUID id : accounts) { assertThat(text).doesNotContain(id.toString()); }
        for (UUID id : profiles) { assertThat(text).doesNotContain(id.toString()); }
        for (UUID id : sessions) { assertThat(text).doesNotContain(id.toString()); }
        JsonNode result = JSON.readTree(text);
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("from", "to", "updatedAt", "totals", "days", "topContent");
        assertThat(result.path("from").asText()).isEqualTo(today().minusDays(1).toString());
        assertThat(result.path("to").asText()).isEqualTo(today().toString());
        assertThat(result.path("updatedAt").asText()).isEqualTo(checkpoint.truncatedTo(ChronoUnit.SECONDS).toString());
        assertThat(result.path("days").size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(result.path("days").get(i).path("day").asText()).isEqualTo(today().minusDays(1).plusDays(i).toString());
        }
        return result;
    }

    private void consistency(JsonNode report) throws Exception {
        var v1 = mvc.perform(post(STATISTICS).header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE)))
                .header("X-Correlation-ID", CORRELATION).accept("application/json").contentType("application/json")
                .content("{\"from\":\"" + today().minusDays(1) + "\",\"to\":\"" + today() + "\"}"))
                .andReturn().getResponse();
        assertThat(v1.getStatus()).isEqualTo(200);
        JsonNode totals = JSON.readTree(v1.getContentAsString());
        for (String field : List.of("qualifiedViews", "uniqueViewers", "acceptedWatchedMs")) {
            assertThat(report.path("totals").path(field)).isEqualTo(totals.path(field));
        }
        for (String field : List.of("qualifiedViews", "acceptedWatchedMs")) {
            for (String collection : List.of("days", "topContent")) {
                long sum = 0;
                for (JsonNode row : report.path(collection)) { sum = Math.addExact(sum, row.path(field).asLong()); }
                assertThat(sum).as("%s.%s", collection, field).isEqualTo(totals.path(field).asLong());
            }
        }
    }

    private List<String> databaseState() throws Exception {
        var state = new ArrayList<String>();
        for (String table : List.of("input_checkpoints", "statistics_account_days", "playback_facts", "playback_sessions",
                "profile_watch_days", "processed_events", "catalog_nodes")) {
            String rows = jdbc.queryForObject("SELECT COALESCE(string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text), '') FROM "
                    + table + " t", String.class);
            state.add(table + ":" + jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class) + ":"
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.getBytes(StandardCharsets.UTF_8))));
        }
        return state;
    }

    private UUID node(String kind, UUID parent) {
        UUID id = UUID.randomUUID();
        nodes.add(id);
        jdbc.update("INSERT INTO catalog_nodes (content_id, version, kind, parent_id, ordinal, changed_at) VALUES (?, 1, ?, ?, ?, ?)",
                id, kind, parent, parent == null ? null : 1, Timestamp.from(clock.instant()));
        return id;
    }

    private void play(UUID content, UUID account, long watched, Instant at) {
        fact(content, account, UUID.randomUUID(), UUID.randomUUID(), 1, watched, watched, at);
    }

    private void fact(UUID content, UUID account, UUID profile, UUID session, long sequence, long watched, long total, Instant at) {
        UUID eventId = UUID.randomUUID();
        sessions.add(session); accounts.add(account); profiles.add(profile); events.add(eventId);
        var event = new IngestionFixtures().playback(sequence, watched, total);
        event.root().put("aggregateId", session.toString()).put("eventId", eventId.toString()).put("occurredAt", at.toString());
        event.payload().put("sessionId", session.toString()).put("accountId", account.toString())
                .put("profileId", profile.toString()).put("contentId", content.toString());
        assertThat(intake.accept(event.topic(), event.key(), event.json())).isEqualTo(EventIntake.Outcome.APPLIED);
    }

    private void confirm() {
        checkpointWrites = true;
        checkpoint = clock.instant().minusSeconds(60);
        assertThat(freshness.confirm(InputTopic.PLAYBACK, checkpoint)).isTrue();
    }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private Instant at() { return today().minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC); }
    private String body(int limit) { return body(today().minusDays(1), today(), limit); }
    private static String body(LocalDate from, LocalDate to, int limit) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"topLimit\":" + limit + "}";
    }
    private MockHttpServletResponse query(String body) throws Exception { return request(body, "application/json", STATISTICS_SCOPE); }
    private MockHttpServletResponse request(String body, String mediaType, String scope) throws Exception {
        return mvc.perform(post(REPORT).header("Authorization", bearer(ServiceTokens.token(scope)))
                .header("X-Correlation-ID", CORRELATION).accept("application/json").contentType(mediaType).content(body))
                .andReturn().getResponse();
    }
    private static void counts(JsonNode node, long views, long viewers, long watched) {
        assertThat(node.path("qualifiedViews").asLong()).isEqualTo(views);
        assertThat(node.path("uniqueViewers").asLong()).isEqualTo(viewers);
        assertThat(node.path("acceptedWatchedMs").asLong()).isEqualTo(watched);
    }
    private static JsonNode title(JsonNode report, UUID id) {
        for (JsonNode title : report.path("topContent")) {
            if (title.path("contentId").asText().equals(id.toString())) { return title; }
        }
        throw new AssertionError("Missing title " + id);
    }
    private static List<String> ids(JsonNode report) {
        var ids = new ArrayList<String>();
        for (JsonNode title : report.path("topContent")) { ids.add(title.path("contentId").asText()); }
        return ids;
    }
    private static void problem(MockHttpServletResponse response, int status, String code, String input) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).doesNotContain(input);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "code", "correlationId");
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("correlationId").asText()).isEqualTo(CORRELATION);
    }
    private static Path fixtureDirectory() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path directory = path.resolve("contracts/fixtures/analytics-v2");
            if (Files.isRegularFile(directory.resolve("manifest.json"))) { return directory; }
        }
        throw new IllegalStateException("Required contracts/fixtures/analytics-v2/manifest.json not found walking up from working directory");
    }
}
