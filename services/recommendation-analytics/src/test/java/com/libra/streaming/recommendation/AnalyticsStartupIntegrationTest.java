package com.libra.streaming.recommendation;

import com.libra.streaming.recommendation.internalapi.infrastructure.ServiceTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RA01.4: the packaged application class started the way {@code main} starts it, against real PostgreSQL. Each test
 * uses its own database, so a refused start can be checked for what it left behind.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsStartupIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("analytics_startup").withUsername("analytics_startup")
            .withPassword("canary-db-" + UUID.randomUUID());
    static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void startsOnAnEmptyDatabaseAndRestartsWithoutMigratingAgain() throws Exception {
        String database = database();
        for (int run = 0; run < 2; run++) {
            try (var context = start(database)) { assertThat(context.isRunning()).isTrue(); }
        }
        assertThat(query(database, "SELECT version || ':' || success FROM flyway_schema_history ORDER BY installed_rank")).containsExactly("1:true", "2:true", "3:true", "4:true", "5:true", "6:true", "7:true", "8:true");
        assertThat(query(database, "SELECT to_regclass('public.bootstrap_progress') IS NOT NULL")).containsExactly("t");
        assertThat(query(database, "SELECT count(*) FROM input_checkpoints")).containsExactly("3");
    }

    @Test
    void missingOrWrongDatabaseSettingsStopStartupWithoutLeakingThePassword(CapturedOutput output) throws Exception {
        String database = database();
        // Explicit empty values: RECOMMENDATION_DB_* variables of the shell must not reach these starts.
        assertThatThrownBy(() -> run("--server.port=0", "--spring.datasource.url=", "--spring.datasource.username=",
                "--spring.datasource.password=")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> run("--server.port=0", "--spring.datasource.url=" + url(database),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password="))
                .isInstanceOf(Exception.class);
        String wrong = "canary-wrong-" + UUID.randomUUID();
        assertThatThrownBy(() -> run("--server.port=0", "--spring.datasource.url=" + url(database),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + wrong,
                "--spring.datasource.hikari.connection-timeout=1000")).isInstanceOf(Exception.class);
        assertThat(output.getAll()).doesNotContain(wrong).doesNotContain(POSTGRES.getPassword());
        // Nothing was migrated by the refused starts.
        assertThat(query(database, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"))
                .containsExactly("0");
    }

    @Test
    void enabledServiceAuthenticationWithoutAUsableKeyStopsStartup(CapturedOutput output) throws Exception {
        String database = database();
        String garbage = "canary-key-" + UUID.randomUUID();
        List<String[]> invalid = List.of(
                new String[] {"--libra.analytics.core-service.enabled=true"},
                new String[] {"--libra.analytics.core-service.enabled=true",
                        "--libra.analytics.core-service.public-key=" + ServiceTokens.publicKey(ServiceTokens.CORE)},
                new String[] {"--libra.analytics.core-service.enabled=true", "--libra.analytics.core-service.key-id=core-1"},
                new String[] {"--libra.analytics.core-service.enabled=true", "--libra.analytics.core-service.key-id=core-1",
                        "--libra.analytics.core-service.public-key=" + garbage},
                new String[] {"--libra.analytics.core-service.enabled=true", "--libra.analytics.core-service.key-id=core-1",
                        "--libra.analytics.core-service.public-key=" + ServiceTokens.publicKey(ServiceTokens.pair(1024))});
        for (String[] arguments : invalid) {
            assertThatThrownBy(() -> start(database, arguments)).isInstanceOf(Exception.class);
        }
        assertThat(output.getAll()).doesNotContain(garbage);
        // The same settings with a real key start; disabled mode needs no key at all.
        try (var context = start(database, "--libra.analytics.core-service.enabled=true",
                "--libra.analytics.core-service.key-id=core-1",
                "--libra.analytics.core-service.public-key=" + ServiceTokens.publicKey(ServiceTokens.CORE))) {
            assertThat(context.isRunning()).isTrue();
        }
        try (var context = start(database, "--libra.analytics.core-service.public-key=" + garbage)) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    void aFailingMigrationStopsStartupAndLeavesThePreviousSchemaIntact() throws Exception {
        String database = database();
        try (var context = start(database)) { assertThat(context.isRunning()).isTrue(); }
        assertThatThrownBy(() -> start(database, "--spring.flyway.locations=classpath:db/migration,classpath:db/broken"))
                .isInstanceOf(Exception.class);
        assertThat(query(database, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'half_applied'")).containsExactly("0");
        assertThat(query(database, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "ORDER BY table_name")).containsExactly("bootstrap_progress", "catalog_nodes", "dead_letters", "flyway_schema_history", "input_checkpoints", "playback_facts", "playback_sessions", "processed_events", "profile_watch_days", "profiles", "statistics_account_days");
        assertThat(query(database, "SELECT version || ':' || success FROM flyway_schema_history ORDER BY installed_rank")).containsExactly("1:true", "2:true", "3:true", "4:true", "5:true", "6:true", "7:true", "8:true");
        assertThat(query(database, "SELECT count(*) FROM input_checkpoints")).containsExactly("3");
        try (var context = start(database)) { assertThat(context.isRunning()).isTrue(); }
    }

    @Test
    void aSchemaThisBuildDidNotProduceStopsStartup() throws Exception {
        // An edited applied migration.
        String edited = database();
        try (var context = start(edited)) { assertThat(context.isRunning()).isTrue(); }
        execute(edited, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        assertThatThrownBy(() -> start(edited)).isInstanceOf(Exception.class);

        // A schema migrated by a newer build: an older build must not serve it.
        String newer = database();
        try (var context = start(newer)) { assertThat(context.isRunning()).isTrue(); }
        execute(newer, "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, "
                + "installed_by, execution_time, success) VALUES ((SELECT max(installed_rank) + 1 FROM flyway_schema_history), '99', 'from a newer build', 'SQL', "
                + "'V99__from_a_newer_build.sql', 1, current_user, 1, true)");
        assertThatThrownBy(() -> start(newer)).isInstanceOf(Exception.class);

        // Tables without any migration history: never adopted by baselining.
        String foreign = database();
        execute(foreign, "CREATE TABLE someone_elses (id bigint PRIMARY KEY)");
        assertThatThrownBy(() -> start(foreign)).isInstanceOf(Exception.class);
        assertThat(query(foreign, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"))
                .containsExactly("someone_elses");
    }

    @Test
    void aDatabaseOutageTurnsReadinessAndQueriesIntoTruthful503sAndRecovers() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("analytics_outage")
                .withUsername("analytics_outage").withPassword(UUID.randomUUID().toString())) {
            postgres.start();
            try (var context = run("--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.datasource.hikari.connection-timeout=1500", "--spring.datasource.hikari.validation-timeout=1000",
                    "--spring.datasource.hikari.data-source-properties.socketTimeout=2",
                    "--libra.analytics.core-service.enabled=true", "--libra.analytics.core-service.key-id=" + ServiceTokens.KID,
                    "--libra.analytics.core-service.public-key=" + ServiceTokens.publicKey(ServiceTokens.CORE))) {
                String base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                assertThat(get(http, base + "/actuator/health/readiness").statusCode()).isEqualTo(200);
                var ready = query(http, base);
                assertThat(ready.statusCode()).isEqualTo(503);
                assertThat(ready.body()).contains("\"code\":\"ANALYTICS_NOT_READY\"");

                var docker = DockerClientFactory.instance().client();
                docker.pauseContainerCmd(postgres.getContainerId()).exec();
                try {
                    assertThat(get(http, base + "/actuator/health/readiness").statusCode()).isEqualTo(503);
                    assertThat(get(http, base + "/actuator/health/liveness").statusCode()).isEqualTo(200);
                    var down = query(http, base);
                    assertThat(down.statusCode()).isEqualTo(503);
                    assertThat(down.headers().firstValue("Content-Type")).contains("application/problem+json");
                    assertThat(down.body()).contains("\"code\":\"ANALYTICS_UNAVAILABLE\"")
                            .doesNotContain("jdbc").doesNotContain("analytics_outage").doesNotContain("Exception");
                } finally {
                    docker.unpauseContainerCmd(postgres.getContainerId()).exec();
                }
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (get(http, base + "/actuator/health/readiness").statusCode() != 200) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(500);
                }
                assertThat(query(http, base).body()).contains("\"code\":\"ANALYTICS_NOT_READY\"");
            }
        }
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> query(HttpClient http, String base) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + "/internal/v1/statistics/query"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + ServiceTokens.token("statistics:read"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(window())).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A valid one-day window, so a query reaches the data path (RA04.2 rejects an invalid body first). */
    private static String window() {
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
        return "{\"from\":\"" + day + "\",\"to\":\"" + day + "\"}";
    }

    private static ConfigurableApplicationContext start(String database, String... extra) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.datasource.url=" + url(database),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()));
        arguments.addAll(List.of(extra));
        return run(arguments.toArray(String[]::new));
    }

    private static ConfigurableApplicationContext run(String... arguments) {
        List<String> disabled = new ArrayList<>(List.of(arguments));
        disabled.add("--libra.analytics.consumers.enabled=false");
        return new SpringApplicationBuilder(RecommendationAnalyticsApplication.class).run(disabled.toArray(String[]::new));
    }

    private static String database() throws SQLException {
        String name = "startup_" + DATABASES.incrementAndGet();
        execute(POSTGRES.getDatabaseName(), "CREATE DATABASE " + name);
        return name;
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String database, String sql) throws SQLException {
        try (var connection = connect(database); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static List<String> query(String database, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var connection = connect(database); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) { values.add(rows.getString(1)); }
        }
        return values;
    }
}
