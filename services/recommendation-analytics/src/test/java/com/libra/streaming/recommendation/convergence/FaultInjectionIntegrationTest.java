package com.libra.streaming.recommendation.convergence;

import com.libra.streaming.recommendation.RecommendationAnalyticsApplication;
import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.IntakeProbe;
import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;
import com.libra.streaming.recommendation.ingestion.infrastructure.ConsumerConfiguration;
import com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real listeners, transactions, broker outage, restart and competing group members. No logging overrides. */
@Testcontainers
@SpringBootTest(properties = {"libra.analytics.consumers.enabled=true",
        "libra.analytics.freshness.checkpoints.enabled=false", "libra.analytics.bootstrap.enabled=false",
        "libra.analytics.dead-letters.redrive-on-startup=false", "spring.kafka.producer.properties.compression.type=none"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@Import(FaultInjectionIntegrationTest.Probes.class)
class FaultInjectionIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_fault").withUsername("analytics_fault").withPassword(UUID.randomUUID().toString());
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    static final List<String> LISTENERS = List.of("analytics-catalog", "analytics-profiles", "analytics-playback");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        try (var admin = admin()) {
            var topics = new ArrayList<NewTopic>();
            for (InputTopic topic : InputTopic.values()) { topics.add(new NewTopic(topic.wireName(), 3, (short) 1)); }
            topics.add(new NewTopic(ConsumerConfiguration.DEAD_LETTER_TOPIC, 1, (short) 1));
            admin.createTopics(topics).all().get(15, TimeUnit.SECONDS);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @TestConfiguration(proxyBeanMethods = false) static class Probes {
        @Bean FaultProbe intakeProbe() { return new FaultProbe(); }
    }
    static final class FaultProbe implements IntakeProbe {
        volatile Set<UUID> beforeIds = Set.of(), afterIds = Set.of();
        final Set<UUID> beforeFired = ConcurrentHashMap.newKeySet(), afterFired = ConcurrentHashMap.newKeySet();
        final Set<UUID> duplicateRedeliveries = ConcurrentHashMap.newKeySet();
        final AtomicInteger beforeCount = new AtomicInteger(), afterCount = new AtomicInteger();

        @Override public void beforeCommit(CoreEvent event) {
            if (beforeIds.contains(event.eventId()) && beforeFired.add(event.eventId())) {
                beforeCount.incrementAndGet(); throw new IllegalStateException("Injected before commit");
            }
        }
        @Override public void afterCommit(CoreEvent event, EventIntake.Outcome outcome) {
            UUID id = event.eventId();
            if (afterFired.contains(id) && outcome == EventIntake.Outcome.DUPLICATE) { duplicateRedeliveries.add(id); }
            if (afterIds.contains(id) && afterFired.add(id)) {
                afterCount.incrementAndGet(); throw new IllegalStateException("Injected after commit");
            }
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FaultProbe probe;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test @Timeout(value = 220, unit = TimeUnit.SECONDS)
    void convergesAfterFaultsBrokerPauseRestartsAndRebalanceWithoutPayloadLogging(CapturedOutput output) throws Exception {
        var canonical = CanonicalEvents.create();
        ProjectionDump reference = reference(canonical);
        var stream = canonical.shuffled(CanonicalEvents.SEED + 100);
        int half = stream.size() / 2;
        var candidates = new ArrayList<>(stream.subList(0, half).stream().map(CanonicalEvents.Delivery::eventId).distinct().toList());
        Collections.shuffle(candidates, new Random(CanonicalEvents.SEED));
        int quarter = canonical.ordered.size() / 4;
        assertThat(candidates.size()).isGreaterThanOrEqualTo(quarter * 2);
        probe.beforeIds = Set.copyOf(candidates.subList(0, quarter));
        probe.afterIds = Set.copyOf(candidates.subList(quarter, quarter * 2));
        assertThat(Collections.disjoint(probe.beforeIds, probe.afterIds)).isTrue();

        try (var admin = admin(); var producer = producer()) {
            var invalid = CanonicalEvents.invalid();
            for (int i = 0; i < half; i++) {
                send(producer, stream.get(i));
                // Five distinct source offsets, never duplicated with the canonical deliveries.
                if (i < invalid.size()) {
                    var event = invalid.get(i);
                    send(producer, new CanonicalEvents.Delivery(event.topic(), event.key(), event.json(), event.id()));
                }
            }
            await().atMost(Duration.ofSeconds(35)).until(() -> jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class) > 5);
            var docker = DockerClientFactory.instance().client();
            docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
            try { Thread.sleep(8000); }
            finally { docker.unpauseContainerCmd(KAFKA.getContainerId()).exec(); }

            // Settle the first half's one-shot probes before the probe-free member joins.
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                assertOffsetsAtEnd(admin);
                assertThat(probe.beforeFired).containsExactlyInAnyOrderElementsOf(probe.beforeIds);
                assertThat(probe.afterFired).containsExactlyInAnyOrderElementsOf(probe.afterIds);
                assertThat(probe.duplicateRedeliveries).containsAll(probe.afterFired);
            });
            var original = stream.getFirst();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, original.eventId())).isEqualTo(1);
            var conflict = (ObjectNode) IngestionFixtures.JSON.readTree(original.json());
            long version = conflict.path("aggregateVersion").longValue() + 100;
            conflict.put("aggregateVersion", version);
            if (original.topic() == InputTopic.PLAYBACK) { ((ObjectNode) conflict.path("payload")).put("sequence", version); }
            send(producer, new CanonicalEvents.Delivery(original.topic(), original.key(), conflict.toString(), original.eventId()));

            // Each restart occurs between batches, with source deliveries still outstanding.
            restart();
            int middle = half + (stream.size() - half) / 3;
            for (int i = half; i < middle; i++) { send(producer, stream.get(i)); }
            try (var competing = context(POSTGRES.getJdbcUrl(), true)) {
                assertThat(competing.getBeansOfType(IntakeProbe.class)).isEmpty();
                var competingListeners = competing.getBean(KafkaListenerEndpointRegistry.class);
                await().atMost(Duration.ofSeconds(20)).until(() -> LISTENERS.stream().anyMatch(id -> {
                    var assigned = competingListeners.getListenerContainer(id).getAssignedPartitions();
                    return assigned != null && !assigned.isEmpty();
                }));
                restart();
                int threeQuarters = middle + (stream.size() - middle) / 2;
                for (int i = middle; i < threeQuarters; i++) { send(producer, stream.get(i)); }
                var metrics = competing.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                await().atMost(Duration.ofSeconds(20)).until(() -> metrics.find("libra.analytics.events").counters()
                        .stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum() > 0);
                Thread.sleep(3000);
            }
            for (int i = middle + (stream.size() - middle) / 2; i < stream.size(); i++) { send(producer, stream.get(i)); }
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                assertOffsetsAtEnd(admin);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letters", Integer.class)).isEqualTo(6);
            });
        }
        // Count event IDs without printing them. Payload identities remain forbidden under default logging.
        String captured = output.getAll();
        long loggedEventIds = canonical.ordered.stream().filter(event -> captured.contains(event.eventId().toString())).count();
        System.out.println("Canonical event IDs present in Spring/Kafka diagnostics: " + loggedEventIds);
        var leakedIds = canonical.payloadIds.stream().filter(captured::contains).count();
        var actual = ProjectionDump.read(jdbc);
        try (var softly = new org.assertj.core.api.AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.equals(reference)).as("fault stream convergence\n" + reference.diff(actual)).isTrue();
            softly.assertThat(jdbc.queryForList("SELECT reason || ':' || state AS value FROM dead_letters ORDER BY reason", String.class))
                    .containsExactly("EVENT_CONFLICT:HELD", "SCHEMA_INVALID:HELD", "SCHEMA_INVALID:HELD", "SCHEMA_INVALID:HELD",
                            "SEMANTIC_INVALID:HELD", "SEMANTIC_INVALID:HELD");
            softly.assertThat(probe.beforeCount.get()).as("before-commit faults fired").isGreaterThan(0);
            softly.assertThat(probe.afterCount.get()).as("after-commit faults fired").isGreaterThan(0);
            softly.assertThat(probe.duplicateRedeliveries).containsAll(probe.afterFired);
            softly.assertThat(leakedIds).as("payload UUID count in default Spring/Kafka diagnostics").isZero();
            softly.assertThat(loggedEventIds).as("event ID count in default Spring/Kafka diagnostics").isZero();
        }
        ProjectionDump.assertTotals(jdbc, canonical);
    }

    private void restart() {
        // stop() waits for the consumer to stop; do not race an asynchronous stop with start().
        LISTENERS.forEach(id -> listeners.getListenerContainer(id).stop());
        LISTENERS.forEach(id -> listeners.getListenerContainer(id).start());
    }

    private static ProjectionDump reference(CanonicalEvents canonical) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) { statement.execute("CREATE DATABASE reference"); }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/reference";
        try (var reference = context(url, false)) {
            assertThat(reference.getBeansOfType(IntakeProbe.class)).isEmpty();
            var intake = reference.getBean(EventIntake.class);
            canonical.ordered.forEach(event -> intake.accept(event.topic(), event.key(), event.json()));
            var jdbc = reference.getBean(JdbcTemplate.class);
            ProjectionDump.assertTotals(jdbc, canonical);
            return ProjectionDump.read(jdbc);
        }
    }

    private static ConfigurableApplicationContext context(String url, boolean consumers) {
        return new SpringApplicationBuilder(RecommendationAnalyticsApplication.class).run("--server.port=0",
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--libra.analytics.freshness.checkpoints.enabled=false",
                "--libra.analytics.consumers.enabled=" + consumers, "--libra.analytics.bootstrap.enabled=false",
                "--libra.analytics.dead-letters.redrive-on-startup=false", "--spring.kafka.producer.properties.compression.type=none");
    }

    private static void assertOffsetsAtEnd(AdminClient admin) throws Exception {
        for (InputTopic topic : InputTopic.values()) {
            var request = new HashMap<TopicPartition, OffsetSpec>();
            for (int p = 0; p < 3; p++) { request.put(new TopicPartition(topic.wireName(), p), OffsetSpec.latest()); }
            var ends = admin.listOffsets(request).all().get(5, TimeUnit.SECONDS);
            String group = switch (topic) {
                case CATALOG -> "analytics-catalog-v1";
                case PROFILES -> "analytics-profiles-v1";
                case PLAYBACK -> "analytics-playback-v1";
            };
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            for (var entry : ends.entrySet()) {
                var committed = offsets.get(entry.getKey());
                // An empty partition need not have a committed offset yet.
                long offset = committed == null ? 0 : committed.offset();
                assertThat(offset).as("committed offset for " + group + "/" + entry.getKey().partition()).isEqualTo(entry.getValue().offset());
            }
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000));
    }
    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000));
    }
    private static void send(KafkaProducer<String, String> producer, CanonicalEvents.Delivery event) throws Exception {
        producer.send(new ProducerRecord<>(event.topic().wireName(), event.key(), event.json())).get(10, TimeUnit.SECONDS);
    }
}
