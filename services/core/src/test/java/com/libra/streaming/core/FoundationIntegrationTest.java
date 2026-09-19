package com.libra.streaming.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.libra.streaming.core.integration.outbox.CoreEventTopic;
import com.libra.streaming.core.integration.outbox.EventEnvelope;
import com.libra.streaming.core.integration.outbox.OutboxWriter;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({FoundationIntegrationTest.ProbeController.class, FoundationIntegrationTest.ProbeSecurity.class})
class FoundationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("libra_core_test")
            .withUsername("libra_test")
            .withPassword(UUID.randomUUID().toString());

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.1")
            .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/readyz").forPort(8025));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired OutboxWriter outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JavaMailSender mail;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void closeHttpClient() {
        http.close();
    }

    @BeforeEach
    void prepareBusinessFixture() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS foundation_business_fixture (id UUID PRIMARY KEY)");
        jdbc.update("DELETE FROM foundation_business_fixture");
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void bootRunsProductionMigrationOnEmptyPostgresAndValidatesItOnRerun() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("libra_core_test");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void businessAndOutboxCommitTogether() {
        EventEnvelope event = event(UUID.randomUUID());
        transaction().executeWithoutResult(status -> {
            jdbc.update("INSERT INTO foundation_business_fixture VALUES (?)", event.aggregateId());
            outbox.append(CoreEventTopic.PROFILES, event);
        });
        assertThat(count("foundation_business_fixture")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload ->> 'name' FROM outbox_events", String.class))
                .isEqualTo("Synthetic profile");
    }

    @Test
    void rollbackLeavesNeitherBusinessRowNorOutboxEvent() {
        EventEnvelope event = event(UUID.randomUUID());
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            jdbc.update("INSERT INTO foundation_business_fixture VALUES (?)", event.aggregateId());
            outbox.append(CoreEventTopic.PROFILES, event);
            throw new IllegalStateException("simulated business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("foundation_business_fixture")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void appendWithoutBusinessTransactionIsRejected() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> outbox.append(CoreEventTopic.PROFILES, event(UUID.randomUUID())));
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void duplicateLogicalEventRollsBackBusinessMutation() {
        UUID aggregateId = UUID.randomUUID();
        transaction().executeWithoutResult(status -> outbox.append(CoreEventTopic.PROFILES, event(aggregateId)));
        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() ->
                transaction().executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO foundation_business_fixture VALUES (?)", aggregateId);
                    outbox.append(CoreEventTopic.PROFILES, event(aggregateId));
                }));
        assertThat(count("foundation_business_fixture")).isZero();
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateEventsHaveOnlyOneCommittedWinner() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    transaction().executeWithoutResult(status -> outbox.append(CoreEventTopic.PROFILES, event(aggregateId)));
                    return true;
                } catch (DuplicateKeyException expected) {
                    return false;
                }
            })).toList();
            int winners = 0;
            for (var task : tasks) {
                if (task.get(20, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void oversizedPayloadRollsBackBusinessMutation() {
        var event = new EventEnvelope(UUID.randomUUID(), "ProfileCreated", 1, UUID.randomUUID(), 1,
                Instant.now(), UUID.randomUUID(), mapper.createObjectNode().put("data", "x".repeat(262_145)));
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            jdbc.update("INSERT INTO foundation_business_fixture VALUES (?)", event.aggregateId());
            outbox.append(CoreEventTopic.PROFILES, event);
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("foundation_business_fixture")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void springKafkaProducerReceivesAcknowledgementAndRealConsumerReadsEnvelope() throws Exception {
        String topic = "foundation-" + UUID.randomUUID();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        EventEnvelope event = event(UUID.randomUUID());
        String serialized = mapper.writeValueAsString(event);
        var json = mapper.readTree(serialized);
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(json.path("occurredAt").asString())).isEqualTo(event.occurredAt());
        assertThat(json.path("eventId").asString()).isEqualTo(event.eventId().toString());
        var sent = kafka.send(topic, event.aggregateId().toString(), serialized).get(20, TimeUnit.SECONDS);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "foundation-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isEqualTo(1);
                var received = records.iterator().next();
                assertThat(received.key()).isEqualTo(event.aggregateId().toString());
                assertThat(received.offset()).isEqualTo(sent.getRecordMetadata().offset());
                assertThat(mapper.readTree(received.value())).isEqualTo(mapper.readTree(serialized));
            });
        }
    }

    @Test
    void smtpMessageIsCapturedByMailpit() {
        String subject = "Foundation " + UUID.randomUUID();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("libra@example.test");
        message.setTo("viewer@example.test");
        message.setSubject(subject);
        message.setText("Synthetic local infrastructure check; no account links.");
        mail.send(message);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var response = get("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains(subject);
        });
    }

    @Test
    void healthIsPublicAndUnauthenticatedApiUsesProblemDetails() throws Exception {
        assertThat(get(base() + "/actuator/health").statusCode()).isEqualTo(200);
        var response = get(base() + "/v1/protected?token=must-not-be-echoed");
        assertProblem(response, 401, "AUTHENTICATION_REQUIRED");
        assertThat(response.body()).doesNotContain("must-not-be-echoed", "properties");
    }

    @Test
    void unsafeRequestStillRequiresCsrf() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/protected"))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 403, "ACCESS_DENIED");
    }

    @Test
    void mvcErrorsAreSanitizedAndCorrelationIsPreservedOrRegenerated() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/test/probe?number=secret-input"))
                .header("X-Correlation-ID", id).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 400, "INVALID_REQUEST");
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(id);
        assertThat(response.body()).doesNotContain("secret-input");
        var failed = http.send(HttpRequest.newBuilder(URI.create(base() + "/test/failure"))
                .header("X-Correlation-ID", "untrusted-input").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(failed, 500, "INTERNAL_ERROR");
        assertThat(failed.body()).doesNotContain("synthetic-secret", "untrusted-input", "IllegalStateException");
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = mapper.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        String id = body.path("correlationId").asString();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(id);
    }

    private EventEnvelope event(UUID aggregateId) {
        return new EventEnvelope(UUID.randomUUID(), "ProfileCreated", 1, aggregateId, 1, Instant.now(),
                UUID.randomUUID(), mapper.createObjectNode().put("name", "Synthetic profile"));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private int count(String table) {
        // Only fixed test-owned table names are passed by callers above.
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/test/probe")
        int probe(@RequestParam int number) { return number; }

        @GetMapping("/test/failure")
        void failure() { throw new IllegalStateException("synthetic-secret"); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeSecurity {
        @Bean
        @Order(0)
        SecurityFilterChain probeChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test/**").authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }
}
