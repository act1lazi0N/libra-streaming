package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Core's exact V2 request over a socket, with a populated maximum-size report. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class StatisticsReportHttpCompatibilityIntegrationTest extends InternalApiFixture {
    @LocalServerPort int port;
    @Autowired Clock clock;
    @Autowired EventIntake intake;
    private final List<Seed> seeds = new ArrayList<>();
    private boolean checkpointWrites;
    private LocalDate to;

    @Override boolean expectsCheckpointWrites() { return checkpointWrites; }

    @AfterEach
    void removeOwnRows() {
        for (Seed seed : seeds) {
            jdbc.update("DELETE FROM playback_facts WHERE session_id = ?", seed.session());
            jdbc.update("DELETE FROM playback_sessions WHERE session_id = ?", seed.session());
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", seed.event());
            jdbc.update("DELETE FROM statistics_account_days WHERE account_id = ?", seed.account());
            jdbc.update("DELETE FROM profile_watch_days WHERE profile_id = ?", seed.profile());
        }
        if (checkpointWrites) {
            jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL WHERE topic = ?", InputTopic.PLAYBACK.wireName());
        }
    }

    @Test
    void fullReportFitsCoreWireAndLatencyBudgetAfterOneWarmUp() throws Exception {
        to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (int title = 0; title < 60; title++) {
            UUID content = UUID.randomUUID();
            for (int view = 0; view < 3; view++) {
                var seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                seeds.add(seed);
                var event = new IngestionFixtures().playback(1, 30000, 30000);
                Instant at = to.minusDays((title + view * 11) % 31).atStartOfDay().toInstant(ZoneOffset.UTC);
                event.root().put("aggregateId", seed.session().toString()).put("eventId", seed.event().toString())
                        .put("occurredAt", at.toString());
                event.payload().put("sessionId", seed.session().toString()).put("accountId", seed.account().toString())
                        .put("profileId", seed.profile().toString()).put("contentId", content.toString());
                assertThat(intake.accept(event.topic(), event.key(), event.json())).isEqualTo(EventIntake.Outcome.APPLIED);
            }
        }
        checkpointWrites = true;
        Instant checkpoint = clock.instant().minusSeconds(60);
        assertThat(freshness.confirm(InputTopic.PLAYBACK, checkpoint)).isTrue();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(send(client).statusCode()).isEqualTo(200);
            long started = System.nanoTime();
            HttpResponse<byte[]> response = send(client);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(elapsed).isLessThan(Duration.ofMillis(500));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(response.body()).hasSizeLessThanOrEqualTo(65536);
            var body = new ObjectMapper().readTree(response.body());
            assertThat(body.path("days").size()).isEqualTo(31);
            assertThat(body.path("topContent").size()).isEqualTo(50);
            assertThat(body.path("totals").path("qualifiedViews").asLong()).isEqualTo(180);
            assertThat(body.path("updatedAt").asText()).isEqualTo(checkpoint.truncatedTo(ChronoUnit.SECONDS).toString());
            for (int i = 0; i < 31; i++) {
                assertThat(body.path("days").get(i).path("day").asText()).isEqualTo(to.minusDays(30).plusDays(i).toString());
                assertThat(body.path("days").get(i).path("qualifiedViews").asLong()).isPositive();
            }
        }
    }

    private HttpResponse<byte[]> send(HttpClient client) throws Exception {
        String body = "{\"from\":\"" + to.minusDays(30) + "\",\"to\":\"" + to + "\",\"topLimit\":50}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v2/statistics/query"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Correlation-ID", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private record Seed(UUID session, UUID account, UUID profile, UUID event) {}
}
