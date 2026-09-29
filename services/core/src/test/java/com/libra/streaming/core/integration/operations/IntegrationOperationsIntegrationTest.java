package com.libra.streaming.core.integration.operations;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.catalog.*;
import com.libra.streaming.core.history.infrastructure.HistoryService;
import com.libra.streaming.core.identity.*;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import com.libra.streaming.core.events.domain.CoreEventTopic;
import com.libra.streaming.core.playback.PlaybackService;
import com.libra.streaming.core.profiles.infrastructure.ProfileService;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import com.libra.streaming.core.integration.delivery.*;
import com.libra.streaming.core.integration.media.*;
import com.libra.streaming.core.events.infrastructure.OutboxWriter;
import org.testcontainers.kafka.KafkaContainer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import static org.awaitility.Awaitility.await;
import static com.libra.streaming.core.integration.operations.OperationsService.*;
import static com.libra.streaming.core.playback.PlaybackModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationOperationsIntegrationTest.TimeConfiguration.class)
class IntegrationOperationsIntegrationTest {
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("community_test").withUsername("community_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock communityClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        synchronized void reset() { now = Instant.now().truncatedTo(ChronoUnit.SECONDS); }
        synchronized void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public synchronized Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DeliveryStore deliveries;
    @Autowired DeliveryWorker worker;
    @Autowired OperationsService operations;
    @Autowired OutboxWriter outbox;
    @Autowired MediaDeadLetterStore deadLetters;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired PlaybackService playback;
    @Autowired HistoryService history;
    @Autowired CatalogService catalog;
    @Autowired MediaProjectionService media;
    @Autowired ProfileService profiles;
    @Autowired IdentityAccountService accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired MutableClock clock;
    @LocalServerPort int port;
    IdentityPrincipal viewer;
    IdentityPrincipal admin;
    UUID profile;
    HttpClient http;
    @BeforeEach void setup() {
        listeners.getListenerContainer("core-media-assets").stop();
        listeners.getListenerContainer("core-media-dlt").stop();
        jdbc.execute("TRUNCATE identity_accounts, catalog_contents, outbox_events, media_dead_letters CASCADE");
        clock.reset();
        viewer = actor(true, "USER"); admin = actor(true, "ADMIN");
        profile = profiles.list(viewer).getFirst().id();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        jdbc.update("DELETE FROM outbox_events");
    }
    @AfterEach void close() {
        listeners.getListenerContainer("core-media-assets").stop();
        listeners.getListenerContainer("core-media-dlt").stop();
        http.close();
    }

    @BeforeAll static void topics() throws Exception {
        try (var client = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            client.createTopics(List.of("core.catalog.v1", "core.playback.v1", "core.profiles.v1", "media.assets.v1", "media.assets.v1.DLT")
                    .stream().map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
        }
    }
    private EventEnvelope append(UUID aggregate, long version) {
        var event = new EventEnvelope(UUID.randomUUID(), "ProfileUpdated", 1, aggregate, version, clock.instant(), UUID.randomUUID(),
                mapper.valueToTree(Map.of("accountId", viewer.accountId(), "profileId", aggregate)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> outbox.append(CoreEventTopic.PROFILES, event));
        jdbc.update("UPDATE outbox_events SET next_attempt_at = ? WHERE event_id = ?", Timestamp.from(clock.instant()), event.eventId());
        return event;
    }
    private KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "operations-proof-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        consumer.assign(List.of(new TopicPartition(topic, 0))); consumer.seekToEnd(List.of());
        consumer.position(new TopicPartition(topic, 0)); return consumer;
    }
    private String state(UUID id) { return jdbc.queryForObject("SELECT delivery_state FROM outbox_events WHERE event_id = ?", String.class, id); }
    private void awaitConsumed(String group, String topic, long offset) {
        try (var client = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var committed = client.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                        .get(new TopicPartition(topic, 0));
                assertThat(committed).isNotNull(); assertThat(committed.offset()).isGreaterThan(offset);
            });
        }
    }
    private long lastOffset(String topic) throws Exception {
        try (var client = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return client.listOffsets(Map.of(new TopicPartition(topic, 0), OffsetSpec.latest())).all()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(topic, 0)).offset() - 1;
        }
    }

    @Test void realKafkaAckPrecedesPublishedMarkAndPreservesEnvelopeAndAggregateKey() throws Exception {
        UUID aggregate = UUID.randomUUID(); var first = append(aggregate, 1); var second = append(aggregate, 2);
        try (var consumer = consumer("core.profiles.v1")) {
            assertThat(worker.publishNext(DeliveryStore.Kind.OUTBOX)).isTrue();
            assertThat(state(first.eventId())).isEqualTo("SENT");
            assertThat(worker.publishNext(DeliveryStore.Kind.OUTBOX)).isTrue();
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add); assertThat(received).hasSize(2);
            });
            assertThat(received).extracting(ConsumerRecord::key).containsOnly(aggregate.toString());
            assertThat(mapper.readValue(received.get(0).value(), EventEnvelope.class)).isEqualTo(first);
            assertThat(mapper.readValue(received.get(1).value(), EventEnvelope.class)).isEqualTo(second);
            assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?", Boolean.class, second.eventId())).isTrue();
        }
    }

    @Test void concurrentClaimsHaveOneWinnerAndExpiredLeaseFencesTheOldWorker() throws Exception {
        var event = append(UUID.randomUUID(), 1); var barrier = new CyclicBarrier(2);
        List<Optional<DeliveryStore.Job>> claims;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Optional<DeliveryStore.Job>> claim = () -> { barrier.await(10, TimeUnit.SECONDS); return deliveries.claim(DeliveryStore.Kind.OUTBOX); };
            var a = pool.submit(claim); var b = pool.submit(claim); claims = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        var old = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        clock.advance(61);
        var recovered = deliveries.claim(DeliveryStore.Kind.OUTBOX).orElseThrow();
        assertThat(recovered.id()).isEqualTo(event.eventId()); assertThat(recovered.leaseId()).isNotEqualTo(old.leaseId());
        assertThat(deliveries.acknowledged(old)).isFalse(); assertThat(deliveries.failed(old)).isFalse();
        assertThat(deliveries.acknowledged(recovered)).isTrue();
    }

    @Test void uncertainAcknowledgementReplaysSameEventAfterWorkerRestart() throws Exception {
        var event = append(UUID.randomUUID(), 1);
        try (var consumer = consumer("core.profiles.v1")) {
            var crashed = deliveries.claim(DeliveryStore.Kind.OUTBOX).orElseThrow();
            kafka.send(crashed.topic(), crashed.key(), crashed.body()).get(10, TimeUnit.SECONDS);
            // Simulate a process dying after broker ACK and before the durable mark.
            clock.advance(61);
            assertThat(worker.publishNext(DeliveryStore.Kind.OUTBOX)).isTrue();
            List<String> bodies = new ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> bodies.add(record.value())); assertThat(bodies).hasSize(2);
            });
            assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
            assertThat(mapper.readTree(bodies.getFirst()).path("eventId").asString()).isEqualTo(event.eventId().toString());
            assertThat(deliveries.acknowledged(crashed)).isFalse();
        }
    }

    @Test void realBrokerOutageDoesNotMarkSentAndRecoveryDeliversRetainedEvent() throws Exception {
        // Populate producer metadata before temporarily pausing this isolated test broker.
        kafka.send("core.profiles.v1", "warmup", "{}").get(10, TimeUnit.SECONDS);
        var event = append(UUID.randomUUID(), 1);
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertThat(worker.publishNext(DeliveryStore.Kind.OUTBOX)).isTrue();
            assertThat(state(event.eventId())).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events WHERE event_id = ?", Boolean.class, event.eventId())).isTrue();
            assertThat(deliveries.claim(DeliveryStore.Kind.OUTBOX)).isEmpty();
        } finally { docker.unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        clock.advance(301);
        assertThat(worker.publishNext(DeliveryStore.Kind.OUTBOX)).isTrue();
        assertThat(state(event.eventId())).isEqualTo("SENT");
    }

    @Test void boundedRetryParksAndBlocksLaterAggregateEventsUntilAuditedIdempotentRetry() {
        UUID aggregate = UUID.randomUUID(); var first = append(aggregate, 1); append(aggregate, 2);
        for (int attempt = 0; attempt < 10; attempt++) {
            var job = deliveries.claim(DeliveryStore.Kind.OUTBOX).orElseThrow();
            assertThat(job.id()).isEqualTo(first.eventId()); deliveries.failed(job); clock.advance(301);
        }
        assertThat(state(first.eventId())).isEqualTo("PARKED");
        assertThat(deliveries.claim(DeliveryStore.Kind.OUTBOX)).isEmpty();
        long version = jdbc.queryForObject("SELECT delivery_version FROM outbox_events WHERE event_id = ?", Long.class, first.eventId());
        var command = new Command(UUID.randomUUID(), version, "Broker recovered");
        var receipt = operations.command(admin, Action.OUTBOX_RETRY, first.eventId(), command, UUID.randomUUID());
        assertThat(operations.command(admin, Action.OUTBOX_RETRY, first.eventId(), command, UUID.randomUUID()).id()).isEqualTo(receipt.id());
        assertThat(operations.audit(admin, 20, 0)).hasSize(1);
        assertThatThrownBy(() -> operations.command(admin, Action.OUTBOX_RETRY, first.eventId(),
                new Command(command.requestId(), version, "Changed reason"), UUID.randomUUID())).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(deliveries.claim(DeliveryStore.Kind.OUTBOX).orElseThrow().id()).isEqualTo(first.eventId());
    }

    @Test void realDltCaptureRestartAndRedrivePreserveIdentityWithoutDuplicateProjectionEffects() throws Exception {
        var content = create(Kind.MOVIE, null, null); UUID binding = UUID.randomUUID(); UUID asset = UUID.randomUUID();
        var event = new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1, asset, 1, clock.instant(), UUID.randomUUID(),
                mapper.valueToTree(new MediaProjectionService.Change(content.id(), binding, asset, 1, MediaState.READY, 120)));
        var mediaListener = listeners.getListenerContainer("core-media-assets");
        var dltListener = listeners.getListenerContainer("core-media-dlt");
        mediaListener.start(); dltListener.start();
        kafka.send("media.assets.v1", asset.toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM media_dead_letters WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1));
        UUID id = jdbc.queryForObject("SELECT id FROM media_dead_letters WHERE event_id = ?", UUID.class, event.eventId());
        assertThatThrownBy(() -> operations.command(admin, Action.MEDIA_DLT_REDRIVE, id,
                new Command(UUID.randomUUID(), 1L, "Retry before repair"), UUID.randomUUID())).hasMessage("MEDIA_BINDING_UNAVAILABLE");
        jdbc.update("INSERT INTO catalog_media_bindings(id, content_id, asset_id, asset_version) VALUES (?, ?, ?, 1)", binding, content.id(), asset);
        jdbc.update("UPDATE catalog_contents SET candidate_binding = ? WHERE id = ?", binding, content.id());
        dltListener.stop(); dltListener.start();
        var command = new Command(UUID.randomUUID(), 1L, "Binding restored");
        var audit = operations.command(admin, Action.MEDIA_DLT_REDRIVE, id, command, UUID.randomUUID());
        assertThat(operations.command(admin, Action.MEDIA_DLT_REDRIVE, id, command, UUID.randomUUID()).id()).isEqualTo(audit.id());
        assertThat(worker.publishNext(DeliveryStore.Kind.MEDIA_REDRIVE)).isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT state FROM catalog_media_bindings WHERE id = ?", String.class, binding)).isEqualTo("READY"));
        long version = jdbc.queryForObject("SELECT delivery_version FROM media_dead_letters WHERE id = ?", Long.class, id);
        operations.command(admin, Action.MEDIA_DLT_REDRIVE, id, new Command(UUID.randomUUID(), version, "Verify replay"), UUID.randomUUID());
        worker.publishNext(DeliveryStore.Kind.MEDIA_REDRIVE);
        mediaListener.stop(); mediaListener.start();
        awaitConsumed("core-media-projection-v1", "media.assets.v1", lastOffset("media.assets.v1"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM media_projection_receipts WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT projection_version FROM catalog_media_bindings WHERE id = ?", Long.class, binding)).isEqualTo(1);
        assertThat(operations.audit(admin, 20, 0)).hasSize(2);
    }

    @Test void dltDatabaseFailureDoesNotCommitOffsetAndRestartCapturesTheRetainedRecord() throws Exception {
        jdbc.execute("CREATE SEQUENCE capture_attempts");
        jdbc.execute("""
                CREATE FUNCTION reject_capture() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM nextval('capture_attempts'); RAISE EXCEPTION 'injected capture failure'; END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_capture BEFORE INSERT ON media_dead_letters FOR EACH ROW EXECUTE FUNCTION reject_capture()");
        var listener = listeners.getListenerContainer("core-media-dlt");
        var sent = kafka.send("media.assets.v1.DLT", "invalid", "{private-database-failure}").get(10, TimeUnit.SECONDS).getRecordMetadata();
        listener.start();
        try (var client = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                    jdbc.queryForObject("SELECT last_value FROM capture_attempts", Long.class)).isGreaterThanOrEqualTo(2));
            var committed = client.listConsumerGroupOffsets("core-media-dlt-v1").partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(sent.topic(), sent.partition()));
            assertThat(committed == null || committed.offset() <= sent.offset()).isTrue();
        } finally {
            listener.stop(); jdbc.execute("DROP TRIGGER reject_capture ON media_dead_letters");
            jdbc.execute("DROP FUNCTION reject_capture()"); jdbc.execute("DROP SEQUENCE capture_attempts");
        }
        listener.start();
        awaitConsumed("core-media-dlt-v1", sent.topic(), sent.offset());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_dead_letters WHERE dlt_offset = ? AND dlt_partition = ?",
                Integer.class, sent.offset(), sent.partition())).isEqualTo(1);
    }

    @Test void failedDltPublicationRetainsOriginalOffsetUntilBrokerRepair() throws Exception {
        var resource = new org.apache.kafka.common.config.ConfigResource(org.apache.kafka.common.config.ConfigResource.Type.TOPIC, "media.assets.v1.DLT");
        try (var client = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            client.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(new ConfigEntry("max.message.bytes", "1024"),
                    AlterConfigOp.OpType.SET)))).all().get(10, TimeUnit.SECONDS);
            double failures = metrics.counter("libra.integration.kafka.errors").count();
            var sent = kafka.send("media.assets.v1", "invalid", "private-poison-".repeat(200)).get(10, TimeUnit.SECONDS).getRecordMetadata();
            var listener = listeners.getListenerContainer("core-media-assets"); listener.start();
            try {
                await().atMost(Duration.ofSeconds(25)).until(() -> metrics.counter("libra.integration.kafka.errors").count() > failures);
                var committed = client.listConsumerGroupOffsets("core-media-projection-v1").partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(new TopicPartition(sent.topic(), sent.partition()));
                assertThat(committed == null || committed.offset() <= sent.offset()).isTrue();
            } finally {
                client.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(new ConfigEntry("max.message.bytes", null),
                        AlterConfigOp.OpType.DELETE)))).all().get(10, TimeUnit.SECONDS);
            }
            listeners.getListenerContainer("core-media-dlt").start();
            awaitConsumed("core-media-projection-v1", sent.topic(), sent.offset());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM media_dead_letters WHERE source_offset = ? AND source_partition = ?",
                    Integer.class, sent.offset(), sent.partition())).isEqualTo(1));
        }
    }

    @Test void malformedDeadLettersAreDurableDeduplicatedPrivateAndCannotBeEditedIntoRedrive() throws Exception {
        String poison = "{private-poison-with-sensitive-content}";
        var record = new ConsumerRecord<String, String>("media.assets.v1.DLT", 0, 123, "bad", poison);
        deadLetters.capture(record); deadLetters.capture(record);
        UUID id = jdbc.queryForObject("SELECT id FROM media_dead_letters", UUID.class);
        assertThat(operations.work(admin, true, 20, 0)).hasSize(1);
        assertThatThrownBy(() -> operations.command(admin, Action.MEDIA_DLT_REDRIVE, id,
                new Command(UUID.randomUUID(), 1L, "Retry invalid payload"), UUID.randomUUID())).hasMessage("EVENT_NOT_REDRIVABLE");
        var response = request(admin, "GET", "/v1/admin/operations/media-dead-letters", null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(poison, "event_body", "event_key");
        deadLetters.capture(new ConsumerRecord<>("media.assets.v1.DLT", 0, 124, "bad", "\0"));
        assertThat(jdbc.queryForObject("SELECT payload_available FROM media_dead_letters WHERE dlt_offset = 124", Boolean.class)).isFalse();
    }

    @Test void auditInsertFailureRollsBackRetryAndAdminHttpRequiresCsrfRoleAndCurrentVersion() throws Exception {
        var event = append(UUID.randomUUID(), 1);
        jdbc.update("UPDATE outbox_events SET delivery_state = 'PARKED' WHERE event_id = ?", event.eventId());
        var command = new Command(UUID.randomUUID(), 1L, "Repaired broker");
        jdbc.execute("ALTER TABLE integration_operation_audit ADD CONSTRAINT reject_audit CHECK (FALSE) NOT VALID");
        try {
            assertThatThrownBy(() -> operations.command(admin, Action.OUTBOX_RETRY, event.eventId(), command, UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(state(event.eventId())).isEqualTo("PARKED");
        } finally { jdbc.execute("ALTER TABLE integration_operation_audit DROP CONSTRAINT reject_audit"); }
        String path = "/v1/admin/operations/outbox/" + event.eventId() + "/retry";
        String body = mapper.writeValueAsString(command);
        assertThat(request(viewer, "POST", path, body, true).statusCode()).isEqualTo(403);
        assertThat(request(admin, "POST", path, body, false).statusCode()).isEqualTo(403);
        assertThat(request(admin, "POST", path, body, true).statusCode()).isEqualTo(200);
        assertThat(request(admin, "POST", path, body, true).statusCode()).isEqualTo(200);
        assertThat(operations.audit(admin, 20, 0)).hasSize(1);
        assertThat(request(admin, "POST", path, mapper.writeValueAsString(new Command(UUID.randomUUID(), 1L, "Stale")), true).statusCode()).isEqualTo(409);
        assertThat(request(null, "GET", "/actuator/metrics", null, false).statusCode()).isEqualTo(401);
        assertThat(request(viewer, "GET", "/actuator/metrics", null, false).statusCode()).isEqualTo(403);
        assertThat(request(admin, "GET", "/actuator/metrics/libra.integration.backlog", null, false).statusCode()).isEqualTo(200);
        assertThat(operations.summary(admin)).anySatisfy(queue -> assertThat(queue.count()).isEqualTo(1));
    }

    @Test void v6UpgradePreservesPendingAndAlreadyPublishedEvents() {
        var baseline = org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("integration_upgrade").defaultSchema("integration_upgrade").target("6").load(); baseline.migrate();
        UUID pending = UUID.randomUUID(); UUID sent = UUID.randomUUID();
        for (UUID id : List.of(pending, sent)) {
            jdbc.update("""
                    INSERT INTO integration_upgrade.outbox_events(event_id, topic, event_type, schema_version, aggregate_id,
                        aggregate_version, occurred_at, correlation_id, payload, published_at)
                    VALUES (?, 'core.profiles.v1', 'ProfileCreated', 1, ?, 1, CURRENT_TIMESTAMP, ?, '{}'::jsonb, ?)
                    """, id, UUID.randomUUID(), UUID.randomUUID(), id.equals(sent) ? Timestamp.from(clock.instant()) : null);
        }
        var upgrade = org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("integration_upgrade").defaultSchema("integration_upgrade").target("7").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1); assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM integration_upgrade.outbox_events WHERE event_id = ?", String.class, pending)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM integration_upgrade.outbox_events WHERE event_id = ?", String.class, sent)).isEqualTo("SENT");
    }
    private IdentityPrincipal actor(boolean verified, String role) {
        UUID id = UUID.randomUUID(); UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role, email_verified) VALUES (?, ?, 'Viewer', 'inert-fixture', ?, ?)",
                id, id + "@example.test", role, verified);
        new TransactionTemplate(transactions).executeWithoutResult(status -> profiles.initialize(id, "Viewer"));
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)", sid,
                id, Timestamp.from(clock.instant().minusSeconds(60)), Timestamp.from(clock.instant().plusSeconds(3600)));
        return new IdentityPrincipal(id, sid, role, verified);
    }
    private AdminView create(Kind kind, UUID parent, Integer ordinal) {
        return catalog.create(admin, new Create(kind, parent, ordinal,
                new Metadata("Title", "Description", List.of("drama"), 2026, "en", List.of(), List.of(), Tier.FREE)));
    }
    private AdminView movie() { return publish(ready(create(Kind.MOVIE, null, null))); }
    private AdminView publish(AdminView content) { return catalog.publish(admin, content.id(), new Version(content.version()), UUID.randomUUID()); }
    private AdminView ready(AdminView content) {
        content = catalog.bind(admin, content.id(), new Bind(content.version(), UUID.randomUUID(), 1));
        var binding = content.candidate();
        media.accept(binding.assetId().toString(), new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1,
                binding.assetId(), 1, clock.instant(), UUID.randomUUID(), mapper.valueToTree(new MediaProjectionService.Change(
                        content.id(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.READY, 120))));
        return catalog.get(admin, content.id());
    }
    private HttpResponse<String> request(IdentityPrincipal actor, String method, String path, String body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        List<String> cookies = new ArrayList<>();
        if (actor != null) {
            var claims = JwtClaimsSet.builder().issuer("libra-core").audience(List.of("libra-web"))
                    .subject(actor.accountId().toString()).claim("sid", actor.sessionId().toString()).claim("purpose", "access")
                    .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(300)).build();
            cookies.add("LIBRA_ACCESS=" + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        }
        if (csrf) {
            var token = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            builder.header("X-CSRF-TOKEN", mapper.readTree(token.body()).path("token").asString());
            token.headers().allValues("Set-Cookie").forEach(value -> cookies.add(value.split(";", 2)[0]));
        }
        if (!cookies.isEmpty()) { builder.header("Cookie", String.join("; ", cookies)); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
