package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.events.application.OutboxEvents;
import com.libra.streaming.media.events.application.OutboxRelay;
import com.libra.streaming.media.events.domain.DeliveryFailure;
import com.libra.streaming.media.events.domain.OutboxDelivery;
import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * Real PostgreSQL and a real Kafka broker. Events come from the production transitions (claim, READY selection,
 * terminal failure); only the stages between claim and FINALIZING are set directly, because this suite is about the
 * events, not the encode.
 */
@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
// Its containers stop with the class; a cached context would keep pools to them open in the shared failsafe JVM.
@DirtiesContext
class OutboxPublicationIntegrationTest {
    private static final String TOPIC = "media.assets.v1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_outbox_test").withUsername("media_outbox_test")
            .withPassword(UUID.randomUUID().toString());
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void topic() throws Exception {
        // Several partitions, so per-asset order rests on the key and on the relay, not on a single partition.
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired JobLeases leases;
    @Autowired OutputSelections selections;
    @Autowired MediaPersistenceService uploads;
    @Autowired OutboxEvents events;
    @Autowired JdbcTemplate jdbc;
    private final List<String> reports = new CopyOnWriteArrayList<>();

    @BeforeEach void clean() { jdbc.execute("TRUNCATE media_assets CASCADE"); }

    @Test
    void realTransitionsReachTheBrokerAsTheContractExamplesInVersionOrder() throws Exception {
        var ready = claim();
        select(ready, 7);
        var failed = claim();
        assertThat(leases.fail(failed, ProcessingFailure.CORRUPT_INPUT)).isTrue();

        try (var publisher = publisher(Duration.ofSeconds(5)); var relay = relay(publisher, Duration.ofSeconds(5))) {
            relay.start();
            await(() -> unsent() == 0);
        }
        var records = consume(Set.of(key(ready), key(failed)), 4);

        assertThat(versions(records, ready)).containsExactly(1L, 2L);
        assertThat(versions(records, failed)).containsExactly(1L, 2L);
        assertWire(records, ready, 1, "processing");
        assertWire(records, ready, 2, "ready");
        assertWire(records, failed, 1, "processing");
        assertWire(records, failed, 2, "failed");
        for (var record : records) {
            assertThat(record.headers().toArray()).isEmpty();
            assertThat(record.value().length()).isLessThan(1024);
        }
        assertThat(jdbc.queryForList("""
                SELECT delivery_state, attempt_count, lease_token, lease_until, sent_at IS NOT NULL AS sent
                FROM media_outbox_events
                """)).hasSize(4).allSatisfy(row -> assertThat(row).containsEntry("delivery_state", "SENT")
                .containsEntry("attempt_count", 1).containsEntry("lease_token", null)
                .containsEntry("lease_until", null).containsEntry("sent", true));
        assertThat(reports).containsOnly(OutboxRelay.ACKNOWLEDGED);
    }

    @Test
    void anUnreachableBrokerLeavesTheEventPendingWithBackoffUntilTheBrokerReturns() throws Exception {
        var lease = claim();
        var waiting = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        try (var down = new KafkaEventPublisher("127.0.0.1:1", Duration.ofSeconds(1));
                var relay = relay(down, Duration.ofSeconds(1))) {
            relay.start();
            // Observed while the relay runs: closing it during a later attempt would return the event at once.
            await(() -> {
                var row = jdbc.queryForMap("""
                        SELECT delivery_state, attempt_count, lease_token, last_error_code,
                            next_attempt_at > clock_timestamp() AS waiting
                        FROM media_outbox_events WHERE asset_id = ?
                        """, lease.assetId());
                if (!reports.contains("OUTBOX_BROKER_UNAVAILABLE") || !Boolean.TRUE.equals(row.get("waiting"))) {
                    return false;
                }
                // Waiting out the back-off (one second after the first failure): nothing may lease it early.
                assertThat(events.claim(Duration.ofSeconds(5))).isEmpty();
                waiting.set(row);
                return true;
            });
        }
        assertThat(waiting.get()).containsEntry("delivery_state", "PENDING").containsEntry("lease_token", null)
                .containsEntry("last_error_code", "BROKER_UNAVAILABLE");
        assertThat((Integer) waiting.get().get("attempt_count")).isPositive();
        assertThat(reports).containsOnly("OUTBOX_BROKER_UNAVAILABLE");

        jdbc.update("UPDATE media_outbox_events SET next_attempt_at = clock_timestamp() WHERE asset_id = ?",
                lease.assetId());
        reports.clear();
        try (var publisher = publisher(Duration.ofSeconds(5)); var relay = relay(publisher, Duration.ofSeconds(5))) {
            relay.start();
            await(() -> unsent() == 0);
        }
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM media_outbox_events WHERE asset_id = ?",
                String.class, lease.assetId())).isNull();
        assertThat(consume(Set.of(key(lease)), 1)).singleElement()
                .satisfies(record -> assertThat(json(record).get("aggregateVersion").asLong()).isEqualTo(1));
        assertThat(reports).containsOnly(OutboxRelay.ACKNOWLEDGED);
    }

    @Test
    void aSuccessorWaitsForItsPredecessorAndAnExpiredLeaseIsTakenOverWithFencing() {
        var lease = claim();
        select(lease, 7);

        var first = events.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(version(first)).isEqualTo(1);
        // READY (version 2) is due, but PROCESSING is leased and not yet sent.
        assertThat(events.claim(Duration.ofSeconds(30))).isEmpty();

        jdbc.update("UPDATE media_outbox_events SET lease_until = clock_timestamp() - INTERVAL '1 second' "
                + "WHERE event_id = ?", first.eventId());
        var takeover = events.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(takeover.eventId()).isEqualTo(first.eventId());
        assertThat(takeover.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(takeover.attempt()).isEqualTo(2);
        assertThat(takeover.value()).isEqualTo(first.value());
        assertThat(events.sent(first)).isFalse();
        assertThat(events.retry(first, DeliveryFailure.BROKER_UNAVAILABLE, Duration.ofSeconds(1))).isFalse();
        assertThat(events.release(first)).isFalse();
        assertThat(state(first)).isEqualTo("LEASED");

        assertThat(events.sent(takeover)).isTrue();
        assertThat(events.sent(takeover)).isFalse();
        var second = events.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(version(second)).isEqualTo(2);
        assertThat(events.release(second)).isTrue();
        var again = events.claim(Duration.ofSeconds(30)).orElseThrow();
        assertThat(again.eventId()).isEqualTo(second.eventId());
        assertThat(events.sent(again)).isTrue();
        assertThat(events.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(unsent()).isZero();
    }

    @Test
    void anAcknowledgementLostAfterTheBrokerHasTheRecordRepublishesTheSameEvent() throws Exception {
        var lease = claim();
        try (var publisher = publisher(Duration.ofSeconds(5))) {
            // The relay published and died before recording SENT.
            var lost = events.claim(Duration.ofSeconds(30)).orElseThrow();
            publisher.publish(lost, Duration.ofSeconds(5));
            jdbc.update("UPDATE media_outbox_events SET lease_until = clock_timestamp() - INTERVAL '1 second' "
                    + "WHERE event_id = ?", lost.eventId());
            try (var relay = relay(publisher, Duration.ofSeconds(5))) {
                relay.start();
                await(() -> unsent() == 0);
            }
        }
        var records = consume(Set.of(key(lease)), 2);
        assertThat(records).extracting(ConsumerRecord::value).hasSize(2).containsOnly(records.getFirst().value());
        assertThat(jdbc.queryForMap("SELECT delivery_state, attempt_count FROM media_outbox_events"))
                .containsEntry("delivery_state", "SENT").containsEntry("attempt_count", 2);
    }

    @Test
    void concurrentRelaysDeliverEveryEventOnceAndEachAssetInVersionOrder() throws Exception {
        var assets = new ArrayList<JobLease>();
        for (int i = 0; i < 6; i++) {
            var lease = claim();
            assertThat(leases.fail(lease, ProcessingFailure.CORRUPT_INPUT)).isTrue();
            assets.add(lease);
        }
        var publishers = List.of(publisher(Duration.ofSeconds(5)), publisher(Duration.ofSeconds(5)),
                publisher(Duration.ofSeconds(5)));
        var relays = publishers.stream().map(publisher -> relay(publisher, Duration.ofSeconds(5))).toList();
        try {
            relays.forEach(OutboxRelay::start);
            await(() -> unsent() == 0);
        } finally {
            relays.forEach(OutboxRelay::close);
            publishers.forEach(KafkaEventPublisher::close);
        }
        var keys = new java.util.HashSet<String>();
        assets.forEach(lease -> keys.add(key(lease)));
        var records = consume(keys, 12);
        assertThat(records).extracting(record -> json(record).get("eventId").asString()).doesNotHaveDuplicates();
        for (var lease : assets) { assertThat(versions(records, lease)).containsExactly(1L, 2L); }
        assertThat(jdbc.queryForObject("SELECT max(attempt_count) FROM media_outbox_events", Integer.class))
                .isEqualTo(1);
        assertThat(reports).containsOnly(OutboxRelay.ACKNOWLEDGED);
    }

    @Test
    void theDatabaseRefusesToRewriteDeleteReviveOrReorderEvents() {
        var lease = claim();
        assertThat(leases.fail(lease, ProcessingFailure.CORRUPT_INPUT)).isTrue();
        UUID asset = lease.assetId();
        String first = "asset_id = '" + asset + "' AND aggregate_version = 1";
        String second = "asset_id = '" + asset + "' AND aggregate_version = 2";
        var before = jdbc.queryForList("SELECT * FROM media_outbox_events ORDER BY aggregate_version");

        refused("UPDATE media_outbox_events SET payload = payload || '{\"state\":\"READY\"}' WHERE " + second,
                "media outbox event is immutable");
        refused("UPDATE media_outbox_events SET correlation_id = gen_random_uuid() WHERE " + first,
                "media outbox event is immutable");
        refused("UPDATE media_outbox_events SET aggregate_version = 3 WHERE " + second,
                "media outbox event is immutable");
        refused("DELETE FROM media_outbox_events WHERE " + second, "media outbox events are retained");
        refused("UPDATE media_outbox_events SET delivery_state = 'SENT', sent_at = clock_timestamp() WHERE " + second,
                "media outbox events are sent in version order");
        jdbc.update("UPDATE media_outbox_events SET attempt_count = 3 WHERE " + second);
        refused("UPDATE media_outbox_events SET attempt_count = 2 WHERE " + second, "media outbox attempts only grow");
        assertThat(jdbc.update("UPDATE media_outbox_events SET delivery_state = 'SENT', sent_at = clock_timestamp() "
                + "WHERE " + first)).isEqualTo(1);
        refused("UPDATE media_outbox_events SET delivery_state = 'PENDING', sent_at = NULL WHERE " + first,
                "media outbox SENT is terminal");
        refused("UPDATE media_outbox_events SET next_attempt_at = clock_timestamp() WHERE " + first,
                "media outbox SENT is terminal");

        var after = jdbc.queryForList("SELECT * FROM media_outbox_events ORDER BY aggregate_version");
        for (var column : List.of("event_id", "asset_id", "asset_version", "aggregate_version", "topic", "event_type",
                "schema_version", "correlation_id", "created_at")) {
            assertThat(after).extracting(row -> row.get(column))
                    .containsExactlyElementsOf(before.stream().map(row -> row.get(column)).toList());
        }
        assertThat(after).extracting(row -> row.get("payload").toString())
                .containsExactlyElementsOf(before.stream().map(row -> row.get("payload").toString()).toList());
    }

    private void refused(String sql, String reason) {
        assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining(reason);
    }

    private OutboxRelay relay(KafkaEventPublisher publisher, Duration sendTimeout) {
        return new OutboxRelay(events, publisher, new OutboxRelay.Settings(Duration.ofMillis(50),
                sendTimeout.multipliedBy(4), sendTimeout, Duration.ofSeconds(1), Duration.ofSeconds(10)), reports::add);
    }

    private KafkaEventPublisher publisher(Duration sendTimeout) {
        return new KafkaEventPublisher(KAFKA.getBootstrapServers(), sendTimeout);
    }

    /** One queued upload, claimed by the production adapter: asset PROCESSING with its version-1 event. */
    private JobLease claim() {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
        uploads.ensure(upload);
        uploads.queue(upload.uploadId(), upload.assetId(), 1);
        var lease = leases.claim(Duration.ofSeconds(60)).orElseThrow();
        assertThat(lease.assetId()).isEqualTo(upload.assetId());
        return lease;
    }

    /** READY through the production commit; the source and stage it requires are set directly. */
    private void select(JobLease lease, int seconds) {
        String source = "sources/" + lease.uploadId();
        String prefix = "hls/" + lease.uploadId() + "/attempt-" + lease.attempt();
        jdbc.update("UPDATE media_assets SET selected_source_key = ? WHERE asset_id = ?", source, lease.assetId());
        jdbc.update("UPDATE media_jobs SET stage = 'FINALIZING' WHERE id = ?", lease.jobId());
        assertThat(selections.select(lease, new OutputSelections.Selection(source, prefix, prefix + "/master.m3u8",
                seconds))).isTrue();
    }

    private void assertWire(List<ConsumerRecord<String, String>> records, JobLease lease, long version, String name)
            throws Exception {
        var row = jdbc.queryForMap("""
                SELECT e.event_id, e.created_at, u.request_id, a.content_id, a.binding_id
                FROM media_outbox_events e JOIN media_assets a USING (asset_id, asset_version)
                    JOIN media_uploads u USING (asset_id, asset_version)
                WHERE e.asset_id = ? AND e.aggregate_version = ?
                """, lease.assetId(), version);
        var expected = Files.readString(examples().resolve("media-asset-state.v1." + name + ".json"))
                .replace("{{eventId}}", row.get("event_id").toString())
                .replace("{{assetId}}", lease.assetId().toString())
                .replace("{{contentId}}", row.get("content_id").toString())
                .replace("{{bindingId}}", row.get("binding_id").toString())
                .replace("{{correlationId}}", row.get("request_id").toString())
                .replace("{{occurredAt}}", ((Timestamp) row.get("created_at")).toInstant().toString());
        var record = records.stream().filter(candidate -> candidate.key().equals(key(lease))
                && json(candidate).get("aggregateVersion").asLong() == version).findFirst().orElseThrow();
        assertThat(json(record)).as(name).isEqualTo(MAPPER.readTree(expected));
    }

    /** The shared wire examples Core's suite publishes too. */
    private static Path examples() {
        for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            var candidate = directory.resolve("contracts/events/examples");
            if (Files.isDirectory(candidate)) { return candidate; }
        }
        throw new AssertionError("contracts/events/examples not found");
    }

    /** Reads every record for the keys; keeps polling briefly after {@code count} so duplicates show up. */
    private List<ConsumerRecord<String, String>> consume(Set<String> keys, int count) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "media-outbox-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            long settle = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, settle)) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (keys.contains(record.key())) { records.add(record); }
                }
                if (records.size() >= count && settle == Long.MAX_VALUE) {
                    settle = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                }
            }
        }
        assertThat(records).hasSize(count);
        return records;
    }

    private List<Long> versions(List<ConsumerRecord<String, String>> records, JobLease lease) {
        // Records of one key share a partition; their offsets give the broker's order.
        Map<Long, Long> byOffset = new HashMap<>();
        records.stream().filter(record -> record.key().equals(key(lease)))
                .forEach(record -> byOffset.put(record.offset(), json(record).get("aggregateVersion").asLong()));
        assertThat(records.stream().filter(record -> record.key().equals(key(lease)))
                .map(ConsumerRecord::partition).distinct()).hasSize(1);
        return byOffset.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private static JsonNode json(ConsumerRecord<String, String> record) { return MAPPER.readTree(record.value()); }

    private static String key(JobLease lease) { return lease.assetId().toString(); }

    private long version(OutboxDelivery delivery) { return MAPPER.readTree(delivery.value()).get("aggregateVersion").asLong(); }

    private String state(OutboxDelivery delivery) {
        return jdbc.queryForObject("SELECT delivery_state FROM media_outbox_events WHERE event_id = ?", String.class,
                delivery.eventId());
    }

    private int unsent() {
        return jdbc.queryForObject("SELECT count(*) FROM media_outbox_events WHERE delivery_state <> 'SENT'",
                Integer.class);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) { throw new AssertionError("Condition not reached in 30 s"); }
            Thread.sleep(50);
        }
    }
}
