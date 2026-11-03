package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.catalog.CatalogService;
import com.libra.streaming.core.catalog.MediaProjectionService;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MediaOutageRetryIntegrationTest.OutageConfiguration.class)
class MediaOutageRetryIntegrationTest {
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("media_outage_test").withUsername("media_outage_test").withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static final class Outage {
        final AtomicBoolean enabled = new AtomicBoolean();
        final AtomicInteger failures = new AtomicInteger();
    }

    @TestConfiguration static class OutageConfiguration {
        @Bean static Outage outage() { return new Outage(); }
        @Bean static BeanPostProcessor outageDataSources(Outage outage) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource source ? new OutageDataSource(source, outage) : bean;
                }
            };
        }
    }

    static final class OutageDataSource extends DelegatingDataSource {
        private final Outage outage;
        OutageDataSource(DataSource source, Outage outage) { super(source); this.outage = outage; }
        private void check() throws SQLException {
            if (outage.enabled.get()) {
                outage.failures.incrementAndGet();
                throw new SQLTransientConnectionException("injected outage");
            }
        }
        @Override public Connection getConnection() throws SQLException { check(); return super.getConnection(); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            check(); return super.getConnection(username, password);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired MediaProjectionService projections;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired Outage outage;
    IdentityPrincipal admin;

    @BeforeAll static void topics() throws Exception {
        try (var client = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            client.createTopics(List.of("media.assets.v1", "media.assets.v1.DLT").stream()
                    .map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
        }
    }

    @BeforeEach void prepare() {
        listeners.getListenerContainer("core-media-assets").stop();
        listeners.getListenerContainer("core-media-dlt").stop();
        outage.enabled.set(false); outage.failures.set(0);
        jdbc.execute("TRUNCATE identity_accounts, catalog_contents, outbox_events, media_dead_letters CASCADE");
        UUID id = UUID.randomUUID(); UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Admin', 'inert-fixture', 'ADMIN')",
                id, id + "@example.test");
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                session, id);
        admin = new IdentityPrincipal(id, session, "ADMIN", false);
    }

    @AfterEach void close() {
        outage.enabled.set(false);
        listeners.getListenerContainer("core-media-assets").stop();
        listeners.getListenerContainer("core-media-dlt").stop();
    }

    @Test void postgresOutageBeyondBoundedRetriesEventuallyProjectsWithoutDeadLetter() throws Exception {
        var movie = catalog.create(admin, new Create(Kind.MOVIE, null, null,
                new Metadata("Outage", "Description", List.of("drama"), 2026, "en", List.of("Cast"), List.of("posters/sample"), Tier.FREE)));
        var binding = catalog.bind(admin, movie.id(), new Bind(movie.version(), UUID.randomUUID(), 1)).candidate();
        var event = event(binding);
        outage.enabled.set(true);
        try {
            // Observe the real transaction boundary before measuring only Kafka's attempts.
            assertThatThrownBy(() -> projections.accept(binding.assetId().toString(), event))
                    .isInstanceOf(CannotCreateTransactionException.class).hasRootCauseInstanceOf(SQLTransientConnectionException.class);
            outage.failures.set(0);
            kafka.send("media.assets.v1", binding.assetId().toString(), mapper.writeValueAsString(event)).get(15, TimeUnit.SECONDS);
            listeners.getListenerContainer("core-media-assets").start();
            await().atMost(Duration.ofSeconds(60)).until(() -> outage.failures.get() >= 6);
        } finally { outage.enabled.set(false); }
        listeners.getListenerContainer("core-media-dlt").start();
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(catalog.get(admin, movie.id()).candidate().state()).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_projection_receipts WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_dead_letters WHERE event_id = ?", Integer.class, event.eventId())).isZero();
        });
        listeners.getListenerContainer("core-media-assets").stop();
        assertNoDeadLetter(event.eventId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_dead_letters WHERE event_id = ?", Integer.class, event.eventId())).isZero();
    }

    private void assertNoDeadLetter(UUID eventId) {
        var partition = new TopicPartition("media.assets.v1.DLT", 0);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outage-dlt-proof-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            var records = new ArrayList<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                assertThat(consumer.position(partition)).isGreaterThanOrEqualTo(end);
            });
            assertThat(records).noneMatch(record -> eventId.toString().equals(mapper.readTree(record.value()).path("eventId").asString()));
        }
    }

    private EventEnvelope event(Binding binding) {
        return new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1, binding.assetId(), 2, Instant.now(), UUID.randomUUID(),
                mapper.valueToTree(new MediaProjectionService.Change(binding.contentId(), binding.id(), binding.assetId(), binding.assetVersion(), MediaState.READY, 120)));
    }
}
