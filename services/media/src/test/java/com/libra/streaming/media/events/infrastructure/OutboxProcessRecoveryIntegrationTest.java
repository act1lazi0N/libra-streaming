package com.libra.streaming.media.events.infrastructure;

import com.libra.streaming.media.processing.application.JobLeases;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * The packaged Media service publishing the outbox, killed (SIGKILL-equivalent) twice while it drains a backlog
 * and then restarted, against real PostgreSQL and a real Kafka broker. Nothing in the test JVM touches delivery
 * state: recovery relies on the persisted leases expiring.
 */
@Testcontainers
@SpringBootTest(properties = "libra.media.storage.enabled=false")
// Its containers stop with the class; a cached context would keep pools to them open in the shared failsafe JVM.
@DirtiesContext
class OutboxProcessRecoveryIntegrationTest {
    private static final String TOPIC = "media.assets.v1";
    private static final int ASSETS = 150;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_relay_process").withUsername("media_relay_process")
            .withPassword(UUID.randomUUID().toString());
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JobLeases leases;
    @Autowired MediaPersistenceService uploads;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aKilledPackagedRelayLosesNothingAndKeepsEachAssetInVersionOrder() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        var keys = new HashSet<String>();
        for (int i = 0; i < ASSETS; i++) {
            var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), 1, 1024, "a".repeat(64), Instant.now().plusSeconds(1800));
            uploads.ensure(upload);
            uploads.queue(upload.uploadId(), upload.assetId(), 1);
            var lease = leases.claim(Duration.ofSeconds(60)).orElseThrow();
            assertThat(leases.fail(lease, ProcessingFailure.CORRUPT_INPUT)).isTrue();
            keys.add(lease.assetId().toString());
        }
        int total = ASSETS * 2;
        assertThat(sent()).isZero();

        var evidence = Path.of("target", "m26-process").toAbsolutePath();
        Files.createDirectories(evidence);
        var kills = new ArrayList<String>();
        Process process = null;
        try {
            for (int run = 1; run <= 2; run++) {
                int before = sent();
                process = launch(evidence.resolve("relay-" + run + ".log"));
                var running = process;
                // Well into the backlog, so the kill lands between deliveries rather than at start-up.
                await(() -> sent() >= before + 50, running);
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                int atKill = sent();
                // Killed mid-backlog: some events sent, some not, possibly one leased.
                assertThat(atKill).isBetween(before + 50, total - 1);
                kills.add("run " + run + ": killed with " + atKill + "/" + total + " sent, "
                        + count("delivery_state = 'LEASED'") + " leased");
            }
            process = launch(evidence.resolve("relay-final.log"));
            var running = process;
            await(() -> sent() == total, running);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(count("last_error_code IS NOT NULL OR lease_token IS NOT NULL")).isZero();
        var records = consume(keys, total);
        var byEvent = new HashMap<String, Integer>();
        var versionsByKey = new HashMap<String, List<Long>>();
        records.stream().sorted(java.util.Comparator.comparingLong(ConsumerRecord::offset)).forEach(record -> {
            var event = MAPPER.readTree(record.value());
            byEvent.merge(event.get("eventId").asString(), 1, Integer::sum);
            versionsByKey.computeIfAbsent(record.key(), key -> new ArrayList<>())
                    .add(event.get("aggregateVersion").asLong());
        });
        var stored = Set.copyOf(jdbc.queryForList("SELECT event_id::text FROM media_outbox_events", String.class));
        assertThat(byEvent.keySet()).isEqualTo(stored);
        assertThat(versionsByKey).hasSize(ASSETS).allSatisfy((key, versions) -> {
            assertThat(versions).contains(1L, 2L);
            // No killed process outlives its kill, so no older version can follow a newer one.
            assertThat(versions).isSorted();
        });
        long duplicates = byEvent.values().stream().filter(copies -> copies > 1).count();
        Files.writeString(evidence.resolve("result.txt"), String.join("\n", kills) + "\nPASS: " + total
                + " events SENT after two kills; " + records.size() + " records, " + duplicates
                + " event IDs delivered more than once; per-asset broker order sorted; max attempts "
                + jdbc.queryForObject("SELECT max(attempt_count) FROM media_outbox_events", Integer.class) + ".\n");
    }

    private Process launch(Path output) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path packaged = Path.of("target", "media-service-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        assertThat(packaged).isRegularFile();
        // As in M16: the JDK's selector pipe needs a short Unix-domain socket directory on Windows.
        var builder = new ProcessBuilder(java.toString(),
                "-Djdk.net.unixdomain.tmpdir=" + output.getParent().getParent(), "-jar", packaged.toString(),
                "--server.address=127.0.0.1", "--server.port=0", "--libra.media.storage.enabled=false",
                "--libra.media.outbox.enabled=true", "--libra.media.outbox.poll-interval=10ms",
                "--libra.media.outbox.lease-duration=2s", "--libra.media.outbox.send-timeout=1s",
                "--libra.media.outbox.max-retry-delay=1s", "--libra.media.outbox.shutdown-timeout=200ms",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(), "--logging.level.root=ERROR");
        builder.environment().put("MEDIA_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("MEDIA_DB_USERNAME", POSTGRES.getUsername());
        builder.environment().put("MEDIA_DB_PASSWORD", POSTGRES.getPassword());
        return builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private int sent() { return count("delivery_state = 'SENT'"); }

    private int count(String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM media_outbox_events WHERE " + condition, Integer.class);
    }

    private List<ConsumerRecord<String, String>> consume(Set<String> keys, int atLeast) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "media-process-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            long quiet = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(end, quiet)) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (keys.contains(record.key())) { records.add(record); }
                }
                if (records.size() >= atLeast && quiet == Long.MAX_VALUE) {
                    quiet = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                }
            }
        }
        assertThat(records.size()).isGreaterThanOrEqualTo(atLeast);
        return records;
    }

    private static void await(BooleanSupplier condition, Process process) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!condition.getAsBoolean()) {
            assertThat(process.isAlive()).as("Packaged relay must stay alive; see target/m26-process").isTrue();
            assertThat(System.nanoTime()).as("Packaged relay progress deadline").isLessThan(end);
            Thread.sleep(20);
        }
    }
}
