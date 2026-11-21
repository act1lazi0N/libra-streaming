package com.libra.streaming.recommendation.freshness.infrastructure;

import com.libra.streaming.recommendation.freshness.application.CheckpointAdvancer;
import com.libra.streaming.recommendation.freshness.application.CheckpointStore;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.infrastructure.ConsumerConfiguration;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real scheduler, listeners, PostgreSQL projections and authenticated API through lag and broker failure. */
@Testcontainers
@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=true",
        "libra.analytics.freshness.checkpoints.enabled=true",
        "libra.analytics.freshness.checkpoints.interval=1s",
        "libra.analytics.freshness.checkpoints.admin-timeout=1s",
        "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false",
        "spring.kafka.producer.properties.compression.type=none",
        "logging.level.org.springframework.kafka=OFF",
        "logging.level.org.apache.kafka=OFF"
})
@DirtiesContext
class CheckpointFreshnessIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_freshness").withUsername("analytics_freshness")
            .withPassword(UUID.randomUUID().toString());
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        // Listeners and the scheduler must never race topic creation at application startup.
        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000))) {
            var topics = new ArrayList<NewTopic>();
            for (InputTopic topic : InputTopic.values()) {
                topics.add(new NewTopic(topic.wireName(), 1, (short) 1));
            }
            topics.add(new NewTopic(ConsumerConfiguration.DEAD_LETTER_TOPIC, 1, (short) 1));
            admin.createTopics(topics).all().get(15, TimeUnit.SECONDS);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("libra.analytics.core-service.enabled", () -> true);
        registry.add("libra.analytics.core-service.public-key", () -> ServiceTokens.publicKey(ServiceTokens.CORE));
        registry.add("libra.analytics.core-service.key-id", () -> ServiceTokens.KID);
    }

    @Autowired WebApplicationContext context;
    @Autowired CheckpointStore checkpoints;
    @Autowired CheckpointAdvancer advancer;
    @Autowired CheckpointScheduler scheduler;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    private MockMvc mvc;
    private String query;

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void checkpointsTrackConsumptionSurviveOutagesAndAreNeverAdvancedByRequests() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Pin both event time and query window to yesterday, including if the test crosses UTC midnight.
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        query = "{\"from\":\"" + day + "\",\"to\":\"" + day + "\"}";
        Instant occurredAt = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(checkpoints.confirmed()).containsOnlyKeys(InputTopic.values());
            assertTotals(statistics(), 0);
        });

        try (var producer = producer()) {
            var first = event(occurredAt);
            Instant before = Instant.now();
            send(producer, first);
            awaitPlayback(before, 1);
            stalledPlaybackKeepsLastStatistics(producer, occurredAt.plusSeconds(1));
        }
        brokerPauseKeepsAllCheckpointsAndStatistics();
        requestsCannotAdvanceCheckpoints();
    }

    private void stalledPlaybackKeepsLastStatistics(KafkaProducer<String, String> producer, Instant occurredAt)
            throws Exception {
        var playback = listeners.getListenerContainer("analytics-playback");
        assertThat(playback).isNotNull();
        playback.stop();
        try {
            assertThat(playback.isRunning()).isFalse();
            assertThat(listeners.getListenerContainer("analytics-catalog").isRunning()).isTrue();
            assertThat(listeners.getListenerContainer("analytics-profiles").isRunning()).isTrue();
            var second = event(occurredAt);
            JsonNode old;
            Instant t0;
            // Exclude an in-flight tick between the baseline and send. With the first event committed and
            // confirmed, the quiet playback topic has no outstanding watermark from before this send.
            synchronized (advancer) {
                old = statistics();
                assertTotals(old, 1);
                t0 = Instant.now();
                send(producer, second);
            }
            continuouslyFor(Duration.ofSeconds(6), () -> {
                assertThat(checkpoints.confirmed().get(InputTopic.PLAYBACK)).isBefore(t0);
                JsonNode current = statistics();
                assertTotals(current, 1);
                assertThat(current.path("updatedAt")).isEqualTo(old.path("updatedAt"));
            });
            // Quiet topics keep confirming: their stored checkpoint (watermark less the 5 s margin) passes t0 while
            // the stalled playback checkpoint stays behind it.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<InputTopic, Instant> progressed = checkpoints.confirmed();
                assertThat(progressed.get(InputTopic.CATALOG)).isAfter(t0);
                assertThat(progressed.get(InputTopic.PROFILES)).isAfter(t0);
                assertThat(progressed.get(InputTopic.PLAYBACK)).isBefore(t0);
            });
            playback.start();
            awaitPlayback(t0, 2);
        } finally {
            if (!playback.isRunning()) { playback.start(); }
        }
    }

    private void brokerPauseKeepsAllCheckpointsAndStatistics() throws Exception {
        var docker = DockerClientFactory.instance().client();
        double failedBefore = failedTicks();
        Map<InputTopic, Instant> before;
        JsonNode last;
        Instant pausedAt;
        boolean paused = false;
        try {
            // Pause between complete ticks so an already successful admin response cannot confirm a
            // checkpoint after the baseline has been taken.
            synchronized (advancer) {
                docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
                paused = true;
                pausedAt = Instant.now();
                before = checkpoints.confirmed();
                last = statistics();
                assertTotals(last, 2);
            }
            continuouslyFor(Duration.ofSeconds(8), () -> {
                assertThat(checkpoints.confirmed()).isEqualTo(before);
                JsonNode current = statistics();
                assertTotals(current, 2);
                assertThat(current.path("updatedAt")).isEqualTo(last.path("updatedAt"));
            });
            assertThat(failedTicks()).isGreaterThan(failedBefore);
        } finally {
            if (paused) { docker.unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<InputTopic, Instant> recovered = checkpoints.confirmed();
            for (InputTopic topic : InputTopic.values()) {
                assertThat(recovered.get(topic)).as(topic.wireName()).isAfter(pausedAt);
            }
            assertTotals(statistics(), 2);
        });
    }

    private void requestsCannotAdvanceCheckpoints() throws Exception {
        scheduler.stop();
        // stop() interrupts but does not join the executor. Drain that last tick before comparing rows.
        var executor = (ScheduledExecutorService) ReflectionTestUtils.getField(scheduler, "executor");
        assertThat(executor).isNotNull();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        List<Map<String, Object>> before = checkpointRows();
        JsonNode last = statistics();
        assertTotals(last, 2);
        for (int request = 0; request < 20; request++) {
            JsonNode current = statistics();
            assertTotals(current, 2);
            assertThat(current.path("updatedAt")).isEqualTo(last.path("updatedAt"));
            assertThat(checkpointRows()).isEqualTo(before);
        }
        assertThat(checkpointRows()).isEqualTo(before);
    }

    private void awaitPlayback(Instant producedAt, long events) {
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            synchronized (advancer) {
                Instant confirmed = checkpoints.confirmed().get(InputTopic.PLAYBACK);
                assertThat(confirmed).isAfter(producedAt);
                JsonNode current = statistics();
                assertTotals(current, events);
                assertThat(Instant.parse(current.path("updatedAt").asText()))
                        .isEqualTo(confirmed.truncatedTo(ChronoUnit.SECONDS));
            }
        });
    }

    @FunctionalInterface
    private interface CheckedAssertion {
        void check() throws Exception;
    }

    /** Fail on the first violation; a later successful poll must never hide an outage response or write. */
    private static void continuouslyFor(Duration duration, CheckedAssertion assertion) throws Exception {
        long start = System.nanoTime();
        do {
            assertion.check();
            Thread.sleep(200);
        } while (System.nanoTime() - start < duration.toNanos());
        assertion.check();
    }

    private JsonNode statistics() throws Exception {
        var response = mvc.perform(post("/internal/v1/statistics/query")
                .header("Authorization", "Bearer " + ServiceTokens.token("statistics:read"))
                .contentType("application/json").accept("application/json").content(query))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return IngestionFixtures.JSON.readTree(response.getContentAsString());
    }

    private static void assertTotals(JsonNode body, long events) {
        assertThat(body.path("qualifiedViews").asLong()).isEqualTo(events);
        assertThat(body.path("uniqueViewers").asLong()).isEqualTo(events);
        assertThat(body.path("acceptedWatchedMs").asLong()).isEqualTo(events * 30000);
        assertThat(body.path("updatedAt").asText()).isNotBlank();
    }

    private List<Map<String, Object>> checkpointRows() {
        return jdbc.queryForList("SELECT topic, confirmed_at FROM input_checkpoints ORDER BY topic");
    }

    private double failedTicks() {
        return metrics.find("libra.analytics.freshness.ticks").tag("outcome", "failed")
                .counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    private static IngestionFixtures.Event event(Instant occurredAt) {
        var event = new IngestionFixtures().playback(1, 30000, 30000);
        event.root().put("occurredAt", occurredAt.toString());
        return event;
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000));
    }

    private static void send(KafkaProducer<String, String> producer, IngestionFixtures.Event event) throws Exception {
        producer.send(new ProducerRecord<>(event.topic().wireName(), 0, event.key(), event.json()))
                .get(10, TimeUnit.SECONDS);
    }
}
