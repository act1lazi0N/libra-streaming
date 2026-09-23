package com.libra.streaming.core.catalog;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.*;
import com.libra.streaming.core.integration.outbox.EventEnvelope;
import com.libra.streaming.core.profiles.ProfileService;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class CatalogProfilesIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("catalog_test").withUsername("catalog_test").withPassword(UUID.randomUUID().toString());
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired CatalogQueries queries;
    @Autowired ProfileService profiles;
    @Autowired MediaProjectionService media;
    @Autowired IdentityAccountService accounts;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    IdentityPrincipal admin;
    IdentityPrincipal viewer;
    HttpClient http;

    @BeforeEach
    void prepare() {
        jdbc.execute("TRUNCATE identity_accounts, catalog_contents, outbox_events CASCADE");
        admin = actor("ADMIN");
        viewer = actor("USER");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach void close() { http.close(); }

    @Test
    void migrationBackfillsExistingAccountsOnceWithLifecycleEvents() {
        var baseline = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_test").defaultSchema("upgrade_test").target("2").load();
        baseline.migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO upgrade_test.identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Existing Viewer', 'inert-fixture', 'USER')",
                id, id + "@example.test");
        var upgrade = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_test").defaultSchema("upgrade_test").target("3").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM upgrade_test.profiles WHERE account_id = ? AND is_default", String.class, id)).isEqualTo("Existing Viewer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_test.outbox_events WHERE event_type = 'ProfileCreated'", Integer.class)).isEqualTo(1);
    }

    @Test
    void registrationCreatesExactlyOneDefaultProfileAndPrivateLifecycleEvent() {
        String email = UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        accounts.register(email, "Viewer", password);
        accounts.register(email, "Second", password);
        UUID accountId = jdbc.queryForObject("SELECT id FROM identity_accounts WHERE email = ?", UUID.class, email);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles WHERE account_id = ? AND is_default", Integer.class, accountId)).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE payload->>'accountId' = ?", String.class, accountId.toString());
        assertThat(payload).doesNotContain(email, password, "Viewer");
    }

    @Test
    void profileLimitAndLastProfileHoldUnderRealConcurrentTransactions() throws Exception {
        for (int i = 0; i < 3; i++) { profiles.create(viewer, "Extra", UUID.randomUUID()); }
        assertThat(race(() -> profiles.create(viewer, "Fifth", UUID.randomUUID()), 2))
                .containsExactlyInAnyOrder("OK", "PROFILE_LIMIT_REACHED");
        assertThat(profiles.list(viewer)).hasSize(5);
        for (var profile : profiles.list(viewer).subList(0, 3)) {
            var fresh = profiles.get(viewer, profile.id());
            profiles.delete(viewer, profile.id(), fresh.version(), UUID.randomUUID());
        }
        var remaining = profiles.list(viewer);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = new ArrayList<>();
            for (var profile : remaining) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        // Read after the account lock so default succession cannot cause a spurious version conflict.
                        return new TransactionTemplate(transactions).execute(status -> {
                            var current = profiles.get(viewer, profile.id());
                            profiles.delete(viewer, profile.id(), current.version(), UUID.randomUUID());
                            return "OK";
                        });
                    } catch (DomainException exception) { return exception.code(); }
                }));
            }
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "LAST_PROFILE");
        }
        assertThat(profiles.list(viewer)).singleElement().satisfies(profile -> assertThat(profile.isDefault()).isTrue());
    }

    @Test
    void profileOwnershipVersionAndCsrfAreEnforcedThroughHttp() throws Exception {
        var own = profiles.list(viewer).getFirst();
        var foreign = profiles.list(admin).getFirst();
        Browser browser = new Browser(viewer);
        assertThat(browser.send("GET", "/profiles/" + foreign.id(), null, false).statusCode()).isEqualTo(404);
        assertThat(browser.send("PUT", "/profiles/" + foreign.id(), Map.of("name", "Hijack", "expectedVersion", 1), true).statusCode()).isEqualTo(404);
        assertThat(browser.send("DELETE", "/profiles/" + foreign.id() + "?expectedVersion=1", null, true).statusCode()).isEqualTo(404);
        assertThat(browser.send("POST", "/profiles", Map.of("name", "CSRF"), false).statusCode()).isEqualTo(403);
        assertThat(browser.send("POST", "/profiles", Map.of("name", "New"), true).statusCode()).isEqualTo(201);
        assertThat(browser.send("PUT", "/profiles/" + own.id(), Map.of("name", "Renamed", "expectedVersion", 1), true).statusCode()).isEqualTo(200);
        assertThat(browser.send("PUT", "/profiles/" + own.id(), Map.of("name", "Stale", "expectedVersion", 1), true).body()).contains("VERSION_CONFLICT");
        assertThat(profiles.get(admin, foreign.id()).name()).isEqualTo("Viewer");
        assertThat(browser.send("POST", "/profiles", Map.of("name", "Injected", "accountId", admin.accountId()), true).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE identity_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", viewer.sessionId());
        assertThat(browser.send("GET", "/profiles", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    void defaultDeletionEmitsCleanupAndPromotesOneSuccessorAtomically() {
        var first = profiles.list(viewer).getFirst();
        var second = profiles.create(viewer, "Second", UUID.randomUUID());
        UUID correlation = UUID.randomUUID();
        profiles.delete(viewer, first.id(), first.version(), correlation);
        assertThat(profiles.list(viewer)).singleElement().satisfies(profile -> {
            assertThat(profile.id()).isEqualTo(second.id());
            assertThat(profile.isDefault()).isTrue();
            assertThat(profile.version()).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE correlation_id = ?", Integer.class, correlation)).isEqualTo(2);
        assertThatThrownBy(() -> profiles.get(viewer, first.id())).isInstanceOf(DomainException.class);
    }

    @Test
    void publicationRequiresExactReadyBindingAndPreservesSnapshotDuringReplacement() {
        var movie = create(Kind.MOVIE, null, null, "Original");
        UUID id = movie.id();
        assertThatThrownBy(() -> catalog.publish(admin, id, new Version(1), UUID.randomUUID())).hasMessage("MEDIA_NOT_READY");
        movie = catalog.bind(admin, id, new Bind(1, UUID.randomUUID(), 1));
        var original = movie.candidate();
        media.accept(original.assetId().toString(), event(original, 1, MediaState.PROCESSING));
        assertThatThrownBy(() -> catalog.publish(admin, id, new Version(2), UUID.randomUUID())).hasMessage("MEDIA_NOT_READY");
        media.accept(original.assetId().toString(), event(original, 2, MediaState.READY));
        assertThatThrownBy(() -> queries.get(id)).hasMessage("NOT_FOUND");
        movie = catalog.publish(admin, id, new Version(2), UUID.randomUUID());
        assertThat(queries.get(id).metadata().title()).isEqualTo("Original");
        movie = catalog.edit(admin, id, new Edit(movie.version(), metadata("Replacement")));
        movie = catalog.bind(admin, id, new Bind(movie.version(), original.assetId(), 2));
        var candidate = movie.candidate();
        media.accept(candidate.assetId().toString(), event(candidate, 3, MediaState.FAILED));
        assertThat(queries.get(id).metadata().title()).isEqualTo("Original");
        assertThat(queries.get(id).mediaReady()).isTrue();
        long before = movie.version();
        assertThatThrownBy(() -> catalog.publish(admin, id, new Version(before), UUID.randomUUID())).hasMessage("MEDIA_NOT_READY");
        // Late old READY neither publishes the draft nor chooses the replacement.
        media.accept(original.assetId().toString(), event(original, 1, MediaState.READY));
        assertThat(catalog.get(admin, id).active().id()).isEqualTo(original.id());
        media.accept(candidate.assetId().toString(), event(candidate, 4, MediaState.READY));
        movie = catalog.publish(admin, id, new Version(before), UUID.randomUUID());
        assertThat(queries.get(id).metadata().title()).isEqualTo("Replacement");
        assertThat(movie.active().id()).isEqualTo(candidate.id());
        assertThat(catalog.revisions(admin, id, 20, 0)).hasSize(2);
    }

    @Test
    void hiddenAncestorsBlockSearchChildrenAndDirectAccessWithoutErasingEditorialState() throws Exception {
        var series = publish(create(Kind.SERIES, null, null, "Series"));
        var season = publish(create(Kind.SEASON, series.id(), 1, "Season"));
        var episode = ready(create(Kind.EPISODE, season.id(), 1, "Episode"));
        episode = publish(episode);
        UUID episodeId = episode.id();
        assertThat(queries.children(season.id(), 20, 0)).hasSize(1);
        catalog.unpublish(admin, series.id(), new Version(series.version()), UUID.randomUUID());
        assertThat(search("")).isEmpty();
        assertThatThrownBy(() -> queries.get(episodeId)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> queries.children(season.id(), 20, 0)).hasMessage("NOT_FOUND");
        assertThat(catalog.get(admin, episodeId).published()).isNotNull();
        assertThat(new Browser(null).send("GET", "/catalog/" + episodeId, null, false).statusCode()).isEqualTo(404);
        publish(catalog.get(admin, series.id()));
        assertThat(queries.get(episodeId).mediaReady()).isTrue();
        catalog.unpublish(admin, season.id(), new Version(season.version()), UUID.randomUUID());
        assertThatThrownBy(() -> queries.get(episodeId)).hasMessage("NOT_FOUND");
        assertThat(queries.children(series.id(), 20, 0)).isEmpty();
    }

    @Test
    void revisionsAreOptimisticAndPublishedSearchNeverLeaksDrafts() throws Exception {
        var movie = publish(ready(create(Kind.MOVIE, null, null, "Ocean Journey")));
        long version = movie.version();
        assertThat(race(() -> catalog.edit(admin, movie.id(), new Edit(version, metadata("Secret Draft"))), 2))
                .containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        assertThat(search("Secret")).isEmpty();
        assertThat(search("Ocean")).hasSize(1);
        assertThat(queries.search("Ocean", Kind.MOVIE, "drama", 2026, "en", Tier.FREE, 1, 0)).hasSize(1);
        assertThat(queries.search("Ocean", Kind.SERIES, "drama", null, "", null, 20, 0)).isEmpty();
        assertThat(search("' OR 1=1 --")).isEmpty();
        assertThat(catalog.revisions(admin, movie.id(), 20, 0)).hasSize(2);
        assertThat(new Browser(null).send("GET", "/catalog?limit=101", null, false).statusCode()).isEqualTo(400);
        assertThat(new Browser(null).send("GET", "/catalog?kind=INVALID", null, false).statusCode()).isEqualTo(400);
    }

    @Test
    void hierarchyValidationAndSiblingOrderingAreStable() {
        var movie = create(Kind.MOVIE, null, null, "Movie");
        assertThatThrownBy(() -> create(Kind.SEASON, movie.id(), 1, "Invalid")).hasMessage("INVALID_HIERARCHY");
        assertThatThrownBy(() -> create(Kind.EPISODE, null, null, "Invalid")).hasMessage("INVALID_REQUEST");
        var series = publish(create(Kind.SERIES, null, null, "Series"));
        var second = publish(create(Kind.SEASON, series.id(), 2, "Second"));
        var first = publish(create(Kind.SEASON, series.id(), 1, "First"));
        assertThatThrownBy(() -> create(Kind.SEASON, series.id(), 1, "Duplicate")).hasMessage("ORDINAL_CONFLICT");
        assertThat(queries.children(series.id(), 1, 0)).extracting(PublicView::id).containsExactly(first.id());
        assertThat(queries.children(series.id(), 1, 1)).extracting(PublicView::id).containsExactly(second.id());
        assertThatThrownBy(() -> catalog.bind(admin, series.id(), new Bind(series.version(), UUID.randomUUID(), 1)))
                .hasMessage("NOT_PLAYABLE_CONTENT");
    }

    @Test
    void adminBoundariesAndPublicProjectionAreVerifiedByRealFilterChain() throws Exception {
        Browser user = new Browser(viewer);
        var request = new Create(Kind.MOVIE, null, null, metadata("HTTP Movie"));
        assertThat(user.send("POST", "/admin/catalog", request, true).statusCode()).isEqualTo(403);
        assertThat(new Browser(null).send("GET", "/admin/catalog", null, false).statusCode()).isEqualTo(401);
        Browser administrator = new Browser(admin);
        assertThat(administrator.send("POST", "/admin/catalog", request, false).statusCode()).isEqualTo(403);
        var created = administrator.send("POST", "/admin/catalog", request, true);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(mapper.readTree(created.body()).path("id").asString());
        assertThat(new Browser(null).send("GET", "/catalog/" + id, null, false).statusCode()).isEqualTo(404);
        ready(catalog.get(admin, id));
        var publication = administrator.send("POST", "/admin/catalog/" + id + "/publication", new Version(2), true);
        assertThat(publication.statusCode()).isEqualTo(200);
        var result = new Browser(null).send("GET", "/catalog/" + id, null, false);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).doesNotContain("binding", "assetId", "candidate", "password", "email", "draft");
        assertThat(administrator.send("POST", "/admin/catalog/" + id + "/media-bindings",
                Map.of("expectedVersion", 3, "assetId", UUID.randomUUID(), "assetVersion", 2, "state", "READY"), true).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE identity_accounts SET status = 'SUSPENDED' WHERE id = ?", admin.accountId());
        assertThat(administrator.send("GET", "/admin/catalog", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    void bindingMismatchDuplicatesAndOutOfOrderEventsCannotChangePublication() throws Exception {
        var movie = ready(create(Kind.MOVIE, null, null, "Movie"));
        var binding = movie.candidate();
        var ready = event(binding, 2, MediaState.READY);
        assertThat(race(() -> { media.accept(binding.assetId().toString(), ready); return null; }, 2)).containsOnly("OK");
        media.accept(binding.assetId().toString(), event(binding, 1, MediaState.FAILED));
        assertThat(catalog.get(admin, movie.id()).candidate().state()).isEqualTo("READY");
        var badPayload = new MediaProjectionService.Change(movie.id(), binding.id(), UUID.randomUUID(), 1, MediaState.READY, 120);
        var wrongAsset = new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1, badPayload.assetId(), 9,
                Instant.now(), UUID.randomUUID(), mapper.valueToTree(badPayload));
        int receipts = count("media_projection_receipts");
        assertThatThrownBy(() -> media.accept(badPayload.assetId().toString(), wrongAsset)).hasMessage("Media binding mismatch");
        assertThat(count("media_projection_receipts")).isEqualTo(receipts);
        assertThatThrownBy(() -> queries.get(movie.id())).hasMessage("NOT_FOUND");
    }

    @Test
    void outboxFailureRollsBackPublicationProfileDeletionAndProjectionReceipt() {
        var movie = ready(create(Kind.MOVIE, null, null, "Atomic"));
        var profile = profiles.create(viewer, "Delete", UUID.randomUUID());
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_test_events CHECK (event_type = 'ProfileCreated') NOT VALID");
        try {
            assertThatThrownBy(() -> publish(movie)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(catalog.get(admin, movie.id()).published()).isNull();
            assertThatThrownBy(() -> profiles.delete(viewer, profile.id(), 1, UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(profiles.get(viewer, profile.id())).isNotNull();
        } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_test_events"); }
        var published = publish(movie);
        int receipts = count("media_projection_receipts");
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_test_events CHECK (event_type <> 'CatalogAvailabilityChanged') NOT VALID");
        try {
            assertThatThrownBy(() -> media.accept(published.active().assetId().toString(), event(published.active(), 3, MediaState.FAILED)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("media_projection_receipts")).isEqualTo(receipts);
            assertThat(queries.get(movie.id()).mediaReady()).isTrue();
        } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_test_events"); }
    }

    @Test
    void kafkaDeliveryReplayRestartAndPoisonInputUseDurableProjectionAndDeadLetters() throws Exception {
        try (var adminClient = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            adminClient.createTopics(List.of(new NewTopic("media.assets.v1", 1, (short) 1),
                    new NewTopic("media.assets.v1.DLT", 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        var movie = catalog.bind(admin, create(Kind.MOVIE, null, null, "Kafka").id(), new Bind(1, UUID.randomUUID(), 1));
        var binding = movie.candidate();
        var event = event(binding, 2, MediaState.READY);
        var listener = listeners.getListenerContainer("core-media-assets");
        listener.start();
        try {
            kafka.send("media.assets.v1", binding.assetId().toString(), mapper.writeValueAsString(event)).get(15, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(catalog.get(admin, movie.id()).candidate().state()).isEqualTo("READY"));
            listener.stop();
            kafka.send("media.assets.v1", binding.assetId().toString(), mapper.writeValueAsString(event)).get(15, TimeUnit.SECONDS);
            kafka.send("media.assets.v1", binding.assetId().toString(), mapper.writeValueAsString(event(binding, 1, MediaState.FAILED))).get(15, TimeUnit.SECONDS);
            String poison = "{\"schemaVersion\":999}";
            kafka.send("media.assets.v1", binding.assetId().toString(), poison).get(15, TimeUnit.SECONDS);
            listener.start();
            Properties properties = new Properties();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID());
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (var consumer = new KafkaConsumer<String, String>(properties)) {
                consumer.subscribe(List.of("media.assets.v1.DLT"));
                List<String> dead = new ArrayList<>();
                await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
                    consumer.poll(Duration.ofMillis(500)).forEach(record -> dead.add(record.value()));
                    assertThat(dead).contains(poison);
                });
            }
            assertThat(catalog.get(admin, movie.id()).candidate().projectionVersion()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_projection_receipts WHERE binding_id = ?", Integer.class, binding.id())).isEqualTo(2);
            assertThat(catalog.get(admin, movie.id()).published()).isNull();
        } finally { listener.stop(); }
    }

    private IdentityPrincipal actor(String role) {
        UUID id = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Viewer', 'inert-fixture', ?)",
                id, id + "@example.test", role);
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')", sid, id);
        new TransactionTemplate(transactions).executeWithoutResult(status -> profiles.initialize(id, "Viewer"));
        return new IdentityPrincipal(id, sid, role, false);
    }

    private Metadata metadata(String title) { return new Metadata(title, "Description", List.of("drama"), 2026, "en", List.of("Cast"), List.of("posters/sample"), Tier.FREE); }
    private AdminView create(Kind kind, UUID parent, Integer ordinal, String title) { return catalog.create(admin, new Create(kind, parent, ordinal, metadata(title))); }
    private AdminView publish(AdminView content) { return catalog.publish(admin, content.id(), new Version(content.version()), UUID.randomUUID()); }
    private AdminView ready(AdminView content) {
        content = catalog.bind(admin, content.id(), new Bind(content.version(), UUID.randomUUID(), 1));
        media.accept(content.candidate().assetId().toString(), event(content.candidate(), 2, MediaState.READY));
        return catalog.get(admin, content.id());
    }
    private List<PublicView> search(String q) { return queries.search(q, null, "", null, "", null, 20, 0); }
    private int count(String table) {
        return switch (table) {
            case "media_projection_receipts" -> jdbc.queryForObject("SELECT count(*) FROM media_projection_receipts", Integer.class);
            default -> throw new IllegalArgumentException();
        };
    }
    private EventEnvelope event(Binding binding, long version, MediaState state) {
        return new EventEnvelope(UUID.randomUUID(), "MediaAssetStateChanged", 1, binding.assetId(), version, Instant.now(), UUID.randomUUID(),
                mapper.valueToTree(new MediaProjectionService.Change(binding.contentId(), binding.id(), binding.assetId(), binding.assetVersion(), state,
                        state == MediaState.READY ? 120 : null)));
    }
    private List<String> race(Callable<?> action, int threads) throws Exception {
        var barrier = new CyclicBarrier(threads);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try { action.call(); return "OK"; } catch (DomainException exception) { return exception.code(); }
                }));
            }
            List<String> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        }
    }

    private class Browser {
        final Map<String, String> cookies = new HashMap<>();
        Browser(IdentityPrincipal principal) {
            if (principal != null) {
                var claims = JwtClaimsSet.builder().issuer("libra-core").audience(List.of("libra-web"))
                        .subject(principal.accountId().toString()).claim("sid", principal.sessionId().toString())
                        .claim("purpose", "access")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
                cookies.put("LIBRA_ACCESS", encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
            }
        }
        HttpResponse<String> send(String method, String path, Object body, boolean csrf) throws Exception {
            String token = csrf ? mapper.readTree(send("GET", "/auth/csrf", null, false).body()).path("token").asString() : null;
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json");
            if (!cookies.isEmpty()) { request.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; "))); }
            if (token != null) { request.header("X-CSRF-TOKEN", token); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                for (var cookie : HttpCookie.parse(header)) { cookies.put(cookie.getName(), cookie.getValue()); }
            }
            return response;
        }
    }
}
