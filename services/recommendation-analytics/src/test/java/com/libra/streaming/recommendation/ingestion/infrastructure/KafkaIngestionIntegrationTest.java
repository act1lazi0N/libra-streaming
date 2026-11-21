package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.application.*;
import com.libra.streaming.recommendation.ingestion.domain.CoreEvent;
import com.libra.streaming.recommendation.playback.application.PlaybackStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.libra.streaming.recommendation.ingestion.infrastructure.IngestionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real Kafka, PostgreSQL and production listeners. Only IntakeProbe injects faults or gates a commit. */
@Testcontainers
@SpringBootTest(properties = {
        "libra.analytics.consumers.enabled=true",
        "libra.analytics.freshness.checkpoints.enabled=false", "libra.analytics.dead-letters.redrive-on-startup=false",
        "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.validation-timeout=500",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2",
        "spring.kafka.producer.properties.compression.type=none",
        // Container error handlers can include ConsumerRecord values in diagnostic logs.
        "logging.level.org.springframework.kafka=OFF", "logging.level.org.apache.kafka=OFF"})
@DirtiesContext
@Import(KafkaIngestionIntegrationTest.Probes.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaIngestionIntegrationTest {
    static final String CATALOG = "core.catalog.v1", PROFILES = "core.profiles.v1", PLAYBACK = "core.playback.v1";
    static final String DLT = ConsumerConfiguration.DEAD_LETTER_TOPIC;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_kafka").withUsername("analytics_kafka").withPassword(UUID.randomUUID().toString());
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    static final AtomicBoolean TOPICS_CREATED = new AtomicBoolean();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        // Create before Spring starts its listeners. DLT rejection is the first test, then restores the normal size.
        if (TOPICS_CREATED.compareAndSet(false, true)) {
            try (var admin = admin()) {
                admin.createTopics(List.of(new NewTopic(CATALOG, 1, (short) 1), new NewTopic(PROFILES, 1, (short) 1),
                        new NewTopic(PLAYBACK, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)
                        .configs(Map.of("max.message.bytes", "64")))).all().get(15, TimeUnit.SECONDS);
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @TestConfiguration static class Probes {
        @Bean FaultProbe intakeProbe() { return new FaultProbe(); }
    }
    static class FaultProbe implements IntakeProbe {
        volatile UUID failId, blockId, observeId;
        final AtomicBoolean failing = new AtomicBoolean();
        final AtomicInteger applied = new AtomicInteger(), duplicates = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public void beforeCommit(CoreEvent event) {
            if (event.eventId().equals(failId) && failing.get()) { throw new IllegalStateException("Injected intake failure"); }
            if (event.eventId().equals(blockId)) {
                entered.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Commit gate timed out"); } }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Commit gate interrupted"); }
            }
        }
        @Override public void afterCommit(CoreEvent event, EventIntake.Outcome outcome) {
            if (event.eventId().equals(observeId)) {
                if (outcome == EventIntake.Outcome.APPLIED) { applied.incrementAndGet(); }
                else { duplicates.incrementAndGet(); }
            }
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlaybackStore playback;
    @Autowired DeadLetterRedrive redrive;
    @Autowired FaultProbe probe;
    KafkaProducer<String, String> producer;
    AdminClient admin;
    IngestionFixtures f;
    @BeforeEach void setup() {
        f = new IngestionFixtures(); admin = admin();
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000));
    }
    @AfterEach void close() {
        probe.release.countDown(); probe.blockId = null; probe.failing.set(false);
        producer.close(Duration.ofSeconds(3)); admin.close(Duration.ofSeconds(3));
    }

    @Test @Order(1)
    void failedDeadLetterPublicationBlocksFollowingRecordAndSourceCommitUntilRecovered() throws Exception {
        var poison = f.playback(1, 10000, 10000); poison.payload().remove("totalWatchedMs");
        var valid = new IngestionFixtures().playback(1, 10000, 10000);
        var source = send(poison); var following = send(valid);
        try {
            assertThat(following.partition()).isEqualTo(source.partition());
            assertThat(following.offset()).isEqualTo(source.offset() + 1);
            await().pollInterval(Duration.ofMillis(200)).during(Duration.ofSeconds(15)).atMost(Duration.ofSeconds(18)).untilAsserted(() -> {
                assertThat(deadCount(source)).isZero(); assertThat(receipts(valid)).isZero();
                assertThat(playback.session(UUID.fromString(valid.key()))).isEmpty();
                assertThat(committed(source)).isLessThanOrEqualTo(source.offset());
            });
        } finally { dltMaxBytes("1048576"); }
        waitForDead(source, "SCHEMA_INVALID"); waitApplied(valid, 10000);
        awaitCommitted(following);
    }

    @Test @Order(2)
    void validEventsOfAllTopicsCommitOffsetsOnlyAfterRowsExist() throws Exception {
        var catalog = f.movie(1, "CatalogPublished"); var profile = f.profile(1, "ProfileCreated");
        var progress = f.playback(1, 10000, 10000);
        for (Event event : List.of(catalog, profile, progress)) {
            probe.blockId = event.id(); probe.entered = new CountDownLatch(1); probe.release = new CountDownLatch(1);
            var source = send(event);
            try {
                assertThat(probe.entered.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(receipts(event)).isZero(); assertThat(effectCount(event)).isZero();
                assertThat(committed(source)).isLessThanOrEqualTo(source.offset());
            } finally { probe.release.countDown(); }
            long end = admin.listOffsets(Map.of(partition(source), OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS)
                    .get(partition(source)).offset();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                long offset = committed(source);
                if (offset >= end) { assertThat(receipts(event)).isEqualTo(1); assertThat(effectCount(event)).isEqualTo(1); }
                assertThat(offset).isEqualTo(end);
            });
        }
        assertThat(jdbc.queryForObject("SELECT version FROM catalog_nodes WHERE content_id = ?", Long.class, f.content)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM profiles WHERE profile_id = ?", Long.class, f.profile)).isEqualTo(1);
        waitApplied(progress, 10000);
    }

    @Test @Order(3)
    void schemaInvalidRecordIsCapturedWithOriginAndDoesNotBlockFollowingRecord() throws Exception {
        var poison = f.playback(1, 10000, 10000); poison.payload().remove("totalWatchedMs");
        var valid = new IngestionFixtures().playback(1, 10000, 10000);
        var source = send(poison); var following = send(valid);
        assertThat(following.partition()).isEqualTo(source.partition());
        waitForDead(source, "SCHEMA_INVALID"); waitApplied(valid, 10000); awaitCommitted(following);
        var row = deadRow(source);
        // Read the actual DLT record as well as its database capture: no mock recoverer or broker.
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            int p = ((Number) row.get("dlt_partition")).intValue(); long offset = ((Number) row.get("dlt_offset")).longValue();
            var partition = new TopicPartition(DLT, p); consumer.assign(List.of(partition)); consumer.seek(partition, offset);
            var found = new AtomicReference<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(200))) { if (record.offset() == offset) { found.set(record); } }
                return found.get() != null;
            });
            var record = found.get();
            assertThat(record.key().equals(poison.key())).isTrue(); assertThat(record.value().equals(poison.json())).isTrue();
            assertThat(new String(record.headers().lastHeader(ConsumerConfiguration.ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8)).isEqualTo(source.topic());
            assertThat(ByteBuffer.wrap(record.headers().lastHeader(ConsumerConfiguration.ORIGINAL_PARTITION).value()).getInt()).isEqualTo(source.partition());
            assertThat(ByteBuffer.wrap(record.headers().lastHeader(ConsumerConfiguration.ORIGINAL_OFFSET).value()).getLong()).isEqualTo(source.offset());
            assertThat(new String(record.headers().lastHeader(ConsumerConfiguration.REASON).value(), StandardCharsets.US_ASCII)).isEqualTo("SCHEMA_INVALID");
        }
    }

    @Test @Order(4)
    void occupiedSequenceIsParkedAsEventConflict() throws Exception {
        var first = f.playback(1, 10000, 10000); send(first); waitApplied(first, 10000);
        var conflict = first.fresh(); var source = send(conflict); waitForDead(source, "EVENT_CONFLICT"); awaitCommitted(source);
        assertThat(receipts(conflict)).isZero(); assertThat(playback.session(f.session).orElseThrow().factCount()).isEqualTo(1);
        assertThat(playback.session(f.session).orElseThrow().acceptedWatchedMs()).isEqualTo(10000);
    }

    @Test @Order(5)
    void retryExhaustedRecordRedrivesOnceAndReheldReplayIsDuplicate() throws Exception {
        var event = f.playback(1, 10000, 10000);
        probe.failId = event.id(); probe.observeId = event.id(); probe.failing.set(true);
        probe.applied.set(0); probe.duplicates.set(0);
        var source = send(event); waitForDead(source, "RETRY_EXHAUSTED"); awaitCommitted(source);
        assertThat(receipts(event)).isZero(); assertThat(playback.session(f.session)).isEmpty();
        probe.failing.set(false);
        assertThat(redrive.redriveHeld(10).redriven()).isEqualTo(1);
        waitApplied(event, 10000); assertThat(deadRow(source).get("state")).isEqualTo("REDRIVEN");
        assertThat(redrive.redriveHeld(10).redriven()).isZero();
        jdbc.update("UPDATE dead_letters SET state = 'HELD', redriven_at = NULL WHERE id = ?", deadRow(source).get("id"));
        assertThat(redrive.redriveHeld(10).redriven()).isEqualTo(1);
        assertThat(probe.applied.get()).isEqualTo(1); assertThat(probe.duplicates.get()).isEqualTo(1);
        assertThat(receipts(event)).isEqualTo(1); assertThat(deadRow(source).get("state")).isEqualTo("REDRIVEN");
        assertThat(playback.session(f.session).orElseThrow().acceptedWatchedMs()).isEqualTo(10000);
        assertThat(playback.session(f.session).orElseThrow().factCount()).isEqualTo(1);
    }

    @Test @Order(6)
    void databaseOutageRetriesWithoutParkingOrDoubleCounting() throws Exception {
        var event = f.playback(1, 10000, 10000);
        var docker = DockerClientFactory.instance().client();
        RecordMetadata source;
        docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            source = send(event);
            // Deliberate outage longer than socket/connection timeouts; no JDBC assertion may block this interval.
            Thread.sleep(8000);
            assertThat(committed(source)).isLessThanOrEqualTo(source.offset());
        } finally { docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec(); }
        waitApplied(event, 10000); awaitCommitted(source);
        assertThat(deadCount(source)).isZero();
        assertThat(playback.session(f.session).orElseThrow().factCount()).isEqualTo(1);
        assertThat(receipts(event)).isEqualTo(1);
    }

    RecordMetadata send(Event event) throws Exception {
        return producer.send(new ProducerRecord<>(event.topic().wireName(), 0, event.key(), event.json())).get(10, TimeUnit.SECONDS);
    }
    void waitApplied(Event event, long watched) {
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            assertThat(receipts(event)).isEqualTo(1);
            assertThat(playback.session(UUID.fromString(event.key())).orElseThrow().acceptedWatchedMs()).isEqualTo(watched);
        });
    }
    int effectCount(Event event) {
        String sql = switch (event.topic()) {
            case CATALOG -> "SELECT count(*) FROM catalog_nodes WHERE content_id = ?";
            case PROFILES -> "SELECT count(*) FROM profiles WHERE profile_id = ?";
            case PLAYBACK -> "SELECT count(*) FROM playback_facts WHERE session_id = ?";
        };
        return jdbc.queryForObject(sql, Integer.class, UUID.fromString(event.key()));
    }
    int receipts(Event event) { return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, event.id()); }
    int deadCount(RecordMetadata source) {
        return jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE source_topic = ? AND source_partition = ? AND source_offset = ?",
                Integer.class, source.topic(), source.partition(), source.offset());
    }
    Map<String, Object> deadRow(RecordMetadata source) {
        return jdbc.queryForMap("SELECT id, dlt_partition, dlt_offset, source_topic, source_partition, source_offset, reason, state FROM dead_letters "
                + "WHERE source_topic = ? AND source_partition = ? AND source_offset = ?", source.topic(), source.partition(), source.offset());
    }
    void waitForDead(RecordMetadata source, String reason) {
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            assertThat(deadCount(source)).isEqualTo(1); var row = deadRow(source);
            assertThat(row.get("source_topic")).isEqualTo(source.topic());
            assertThat(((Number) row.get("source_partition")).intValue()).isEqualTo(source.partition());
            assertThat(((Number) row.get("source_offset")).longValue()).isEqualTo(source.offset());
            assertThat(row.get("reason")).isEqualTo(reason); assertThat(row.get("state")).isEqualTo("HELD");
        });
    }
    long committed(RecordMetadata source) throws Exception {
        String group = switch (source.topic()) {
            case CATALOG -> "analytics-catalog-v1"; case PROFILES -> "analytics-profiles-v1"; case PLAYBACK -> "analytics-playback-v1";
            default -> throw new IllegalArgumentException("Unknown source");
        };
        var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var offset = offsets.get(partition(source)); return offset == null ? -1 : offset.offset();
    }
    void awaitCommitted(RecordMetadata source) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(source)).isGreaterThanOrEqualTo(source.offset() + 1));
    }
    static TopicPartition partition(RecordMetadata source) { return new TopicPartition(source.topic(), source.partition()); }
    void dltMaxBytes(String bytes) throws Exception {
        var resource = new ConfigResource(ConfigResource.Type.TOPIC, DLT);
        admin.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(new ConfigEntry("max.message.bytes", bytes),
                AlterConfigOp.OpType.SET)))).all().get(10, TimeUnit.SECONDS);
    }
    static AdminClient admin() { return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000)); }
}
