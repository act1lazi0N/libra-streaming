package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.OutboxEvents;
import com.libra.streaming.media.events.application.OutboxRelay;
import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** M26: the event a transition may write, delivery through a broker that hangs mid-send, and the delivery gauges. */
@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
// Its containers stop with the class; a cached context would keep pools to them open in the shared failsafe JVM.
@DirtiesContext
class OutboxDeliveryHardeningIntegrationTest {
    private static final String TOPIC = "media.assets.v1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_delivery_test").withUsername("media_delivery_test")
            .withPassword(UUID.randomUUID().toString());
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void topic() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired JobLeases leases;
    @Autowired MediaPersistenceService uploads;
    @Autowired OutboxEvents events;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MeterRegistry metrics;
    private final List<String> reports = new CopyOnWriteArrayList<>();

    @BeforeEach void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    @Test
    void onlyTheExactContractEventOfTheTransitionJustWrittenCanBeInserted() {
        var lease = claim();
        // A second PROCESSING announcement at the next version is valid in shape and in fact: the control case.
        UnaryOperator<ObjectNode> valid = payload -> payload;
        assertThat(insertAtNextVersion(lease, valid, Map.of())).isNull();

        var payloadCases = new LinkedHashMap<String, UnaryOperator<ObjectNode>>();
        payloadCases.put("another content", p -> p.put("contentId", UUID.randomUUID().toString()));
        payloadCases.put("another binding", p -> p.put("bindingId", UUID.randomUUID().toString()));
        payloadCases.put("another asset", p -> p.put("assetId", UUID.randomUUID().toString()));
        payloadCases.put("another asset version", p -> p.put("assetVersion", 2));
        payloadCases.put("asset version as text", p -> p.put("assetVersion", "1"));
        payloadCases.put("a state the asset is not in", p -> p.put("state", "FAILED"));
        payloadCases.put("a state outside the contract", p -> p.put("state", "QUEUED"));
        payloadCases.put("a duration outside READY", p -> p.put("durationSeconds", 7));
        payloadCases.put("an extra key", p -> p.put("note", "x"));
        payloadCases.put("a missing key", p -> { p.remove("bindingId"); return p; });
        payloadCases.put("upper-case identifiers", p -> p.put("assetId", lease.assetId().toString().toUpperCase()));
        for (var entry : payloadCases.entrySet()) {
            assertThat(insertAtNextVersion(lease, entry.getValue(), Map.of())).as(entry.getKey())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        var columnCases = new LinkedHashMap<String, Map<String, Object>>();
        columnCases.put("a version the asset has not reached", Map.of("aggregate_version", 3));
        columnCases.put("another correlation", Map.of("correlation_id", UUID.randomUUID()));
        columnCases.put("already leased", Map.of("delivery_state", "LEASED", "lease_token", UUID.randomUUID(),
                "lease_until", java.sql.Timestamp.from(Instant.now().plusSeconds(30))));
        columnCases.put("already attempted", Map.of("attempt_count", 1));
        columnCases.put("already failing", Map.of("last_error_code", "BROKER_REJECTED"));
        for (var entry : columnCases.entrySet()) {
            assertThat(insertAtNextVersion(lease, valid, entry.getValue())).as(entry.getKey())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("media outbox event");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isEqualTo(1);

        // An unknown failure code, or one left on a SENT event, is refused too.
        assertThatThrownBy(() -> jdbc.update("UPDATE media_outbox_events SET last_error_code = 'BROKER_DOWN'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        var leased = events.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE media_outbox_events SET delivery_state = 'SENT', sent_at = clock_timestamp(),
                    lease_token = NULL, lease_until = NULL, last_error_code = 'BROKER_REJECTED'
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.sent(leased)).isTrue();
    }

    @Test
    void aBrokerThatHangsMidSendLosesNothingRecordsWhyAndRecovers() throws Exception {
        var first = claim();
        assertThat(leases.fail(first, ProcessingFailure.CORRUPT_INPUT)).isTrue();
        try (var publisher = new KafkaEventPublisher(KAFKA.getBootstrapServers(), Duration.ofSeconds(1));
                var relay = relay(publisher)) {
            relay.start();
            await(() -> unsent() == 0);
            assertThat(gauge("libra.media.outbox.backlog", "state", "PENDING")).isZero();

            // The producer is connected and has metadata; the broker now stops answering.
            var docker = KAFKA.getDockerClient();
            docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
            JobLease hung;
            try {
                hung = claim();
                assertThat(leases.fail(hung, ProcessingFailure.CORRUPT_INPUT)).isTrue();
                await(() -> "BROKER_UNAVAILABLE".equals(error(hung, 1)));
                // The successor waits behind its unsent predecessor, and the gauges show the stuck delivery.
                assertThat(state(hung, 2)).isEqualTo("PENDING");
                assertThat(gauge("libra.media.outbox.failing", "failure", "BROKER_UNAVAILABLE")).isEqualTo(1);
                assertThat(gauge("libra.media.outbox.backlog", "state", "PENDING")
                        + gauge("libra.media.outbox.backlog", "state", "LEASED")).isEqualTo(2);
                assertThat(gauge("libra.media.outbox.oldest.seconds", null, null)).isPositive();
            } finally {
                docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
            }
            await(() -> unsent() == 0);
            var versions = versions(hung);
            // Nothing lost; the first copy of each version arrives in order. A timed-out send may still have
            // reached the broker, so version 1 can appear more than once (the same event ID each time).
            assertThat(versions).contains(1L, 2L);
            assertThat(versions.indexOf(1L)).isLessThan(versions.indexOf(2L));
            assertThat(eventIds(hung)).hasSize(2);
            assertThat(error(hung, 1)).isNull();
            assertThat(gauge("libra.media.outbox.failing", "failure", "BROKER_UNAVAILABLE")).isZero();
            assertThat(gauge("libra.media.outbox.oldest.seconds", null, null)).isZero();
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "m26-evidence"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target", "m26-evidence", "broker-pause.txt"),
                    "broker order of versions for the asset sent while the broker hung: " + versions + "\n");
        }
        assertThat(reports).contains("OUTBOX_BROKER_UNAVAILABLE", OutboxRelay.ACKNOWLEDGED)
                .containsOnly("OUTBOX_BROKER_UNAVAILABLE", OutboxRelay.ACKNOWLEDGED);
    }

    @Test
    void theGaugesReadTheDatabaseAndAreNanWhenItCannotBeRead() {
        claim();
        assertThat(gauge("libra.media.outbox.backlog", "state", "PENDING")).isEqualTo(1);
        assertThat(gauge("libra.media.outbox.failing", "failure", "BROKER_REJECTED")).isZero();
        var leased = events.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(events.retry(leased, com.libra.streaming.media.events.domain.DeliveryFailure.BROKER_REJECTED,
                Duration.ofSeconds(1))).isTrue();
        assertThat(gauge("libra.media.outbox.failing", "failure", "BROKER_REJECTED")).isEqualTo(1);
        jdbc.execute("ALTER TABLE media_outbox_events RENAME TO media_outbox_events_hidden");
        try {
            assertThat(gauge("libra.media.outbox.backlog", "state", "PENDING")).isNaN();
            assertThat(gauge("libra.media.outbox.oldest.seconds", null, null)).isNaN();
        } finally {
            jdbc.execute("ALTER TABLE media_outbox_events_hidden RENAME TO media_outbox_events");
        }
        assertThat(gauge("libra.media.outbox.backlog", "state", "PENDING")).isEqualTo(1);
    }

    /** Bumps the asset's version and inserts a PROCESSING event for it, in one transaction that is rolled back. */
    private Throwable insertAtNextVersion(JobLease lease, UnaryOperator<ObjectNode> payload,
            Map<String, Object> columns) {
        var asset = jdbc.queryForMap("""
                SELECT a.content_id, a.binding_id, u.request_id FROM media_assets a
                JOIN media_uploads u USING (asset_id, asset_version) WHERE a.asset_id = ?
                """, lease.assetId());
        var node = MAPPER.createObjectNode().put("contentId", asset.get("content_id").toString())
                .put("bindingId", asset.get("binding_id").toString()).put("assetId", lease.assetId().toString())
                .put("assetVersion", 1).put("state", "PROCESSING");
        var values = new LinkedHashMap<String, Object>();
        values.put("event_id", UUID.randomUUID());
        values.put("asset_id", lease.assetId());
        values.put("asset_version", 1);
        values.put("aggregate_version", 2);
        values.put("correlation_id", asset.get("request_id"));
        values.put("payload", MAPPER.writeValueAsString(payload.apply(node)));
        values.put("next_attempt_at", java.sql.Timestamp.from(Instant.now()));
        values.put("created_at", java.sql.Timestamp.from(Instant.now()));
        values.putAll(columns);
        String sql = "INSERT INTO media_outbox_events(" + String.join(", ", values.keySet()) + ") VALUES ("
                + String.join(", ", values.keySet().stream().map(key -> key.equals("payload") ? "CAST(? AS jsonb)" : "?")
                .toList()) + ")";
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE media_assets SET aggregate_version = 2 WHERE asset_id = ?", lease.assetId());
                jdbc.update(sql, values.values().toArray());
                status.setRollbackOnly();
            });
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private OutboxRelay relay(KafkaEventPublisher publisher) {
        return new OutboxRelay(events, publisher, new OutboxRelay.Settings(Duration.ofMillis(50), Duration.ofSeconds(4),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10)), reports::add);
    }

    private JobLease claim() {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
        var lease = leases.claim(Duration.ofSeconds(60)).orElseThrow();
        assertThat(lease.assetId()).isEqualTo(upload.assetId());
        return lease;
    }

    private double gauge(String name, String tag, String value) {
        var search = metrics.get(name);
        if (tag != null) { search = search.tag(tag, value); }
        return search.gauge().value();
    }

    private String error(JobLease lease, long version) {
        return jdbc.queryForObject("SELECT last_error_code FROM media_outbox_events WHERE asset_id = ? "
                + "AND aggregate_version = ?", String.class, lease.assetId(), version);
    }

    private String state(JobLease lease, long version) {
        return jdbc.queryForObject("SELECT delivery_state FROM media_outbox_events WHERE asset_id = ? "
                + "AND aggregate_version = ?", String.class, lease.assetId(), version);
    }

    private int unsent() {
        return jdbc.queryForObject("SELECT count(*) FROM media_outbox_events WHERE delivery_state <> 'SENT'",
                Integer.class);
    }

    /** The asset's versions in broker order (one key, one partition), read until the stream is quiet. */
    private List<Long> versions(JobLease lease) { return this.<Long>records(lease, "aggregateVersion"); }

    private List<String> eventIds(JobLease lease) {
        return this.<String>records(lease, "eventId").stream().distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> records(JobLease lease, String field) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "media-hardening-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var values = new ArrayList<T>();
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long quiet = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < Math.min(quiet, end)) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (!record.key().equals(lease.assetId().toString())) { continue; }
                    var node = MAPPER.readTree(record.value()).get(field);
                    values.add((T) (node.isNumber() ? (Object) node.asLong() : node.asString()));
                    quiet = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                }
            }
        }
        return values;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) { throw new AssertionError("Condition not reached in 30 s"); }
            Thread.sleep(50);
        }
    }
}
