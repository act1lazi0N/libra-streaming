package com.libra.streaming.recommendation.internalapi.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Core's actual headers over a real socket, with service authentication and PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "libra.analytics.consumers.enabled=false",
        "libra.analytics.freshness.checkpoints.enabled=false",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false"
})
@DirtiesContext
class StatisticsHttpCompatibilityIntegrationTest extends InternalApiFixture {
    @LocalServerPort int port;
    @Autowired Clock clock;
    private boolean checkpointWrites;

    @Override boolean expectsCheckpointWrites() { return checkpointWrites; }

    @AfterEach
    void resetOwnCheckpoint() {
        if (checkpointWrites) { jdbc.update("UPDATE input_checkpoints SET confirmed_at = NULL"); }
    }

    @Test
    void coreCanReadAReadySnapshotWithinItsWireAndLatencyLimits() throws Exception {
        checkpointWrites = true;
        Instant checkpoint = clock.instant().minusSeconds(60);
        assertThat(freshness.confirm(InputTopic.PLAYBACK, checkpoint)).isTrue();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(send(client).statusCode()).isEqualTo(200); // Warm server, JWT decoder and connection.
            var checkpoints = rows();
            var rollups = jdbc.queryForList("SELECT * FROM statistics_account_days ORDER BY day, account_id");
            long started = System.nanoTime();
            HttpResponse<byte[]> response = send(client);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(elapsed).isLessThan(Duration.ofMillis(500));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(response.headers().firstValue("Content-Encoding").orElse("identity")).isEqualTo("identity");
            assertThat(response.body()).hasSizeLessThanOrEqualTo(65536);
            var body = new ObjectMapper().readTree(response.body());
            Instant updatedAt = Instant.parse(body.path("updatedAt").asText());
            assertThat(updatedAt).isEqualTo(checkpoint.truncatedTo(ChronoUnit.SECONDS));
            assertThat(updatedAt).isBeforeOrEqualTo(clock.instant());
            assertThat(body.path("uniqueViewers").asLong()).isLessThanOrEqualTo(body.path("qualifiedViews").asLong());
            assertThat(rows()).isEqualTo(checkpoints);
            assertThat(jdbc.queryForList("SELECT * FROM statistics_account_days ORDER BY day, account_id"))
                    .isEqualTo(rollups);
        }
    }

    @Test
    void coreReceivesAProblemDocumentBeforeTheFirstCheckpoint() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response = send(client);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
            var body = new ObjectMapper().readTree(response.body());
            assertThat(body.path("code").asText()).isEqualTo("ANALYTICS_NOT_READY");
        }
    }

    private HttpResponse<byte[]> send(HttpClient client) throws Exception {
        LocalDate to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        String body = "{\"from\":\"" + to.minusDays(6) + "\",\"to\":\"" + to + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + STATISTICS))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", bearer(ServiceTokens.token(STATISTICS_SCOPE)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Correlation-ID", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
