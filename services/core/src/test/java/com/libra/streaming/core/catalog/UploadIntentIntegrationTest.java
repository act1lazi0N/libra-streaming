package com.libra.streaming.core.catalog;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest
class UploadIntentIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("core_upload_test").withUsername("core_upload_test").withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired UploadIntentService uploads;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clean() { jdbc.execute("TRUNCATE identity_accounts, catalog_contents, outbox_events CASCADE"); }

    @Test
    void reservationAndIdempotentReadUseOneCoreBinding() {
        var admin = actor("ADMIN");
        var otherAdmin = actor("ADMIN");
        var viewer = actor("USER");
        var movie = movie(admin);
        UUID requestId = UUID.randomUUID();
        var first = uploads.reserve(admin, movie.id(), requestId, movie.version(), 1024, "a".repeat(64));
        var retry = uploads.reserve(admin, movie.id(), requestId, movie.version(), 1024, "a".repeat(64));
        assertThat(retry).isEqualTo(first);
        assertThat(uploads.read(admin, first.uploadId())).isEqualTo(first);
        assertThat(first.expiresAt()).isEqualTo(first.createdAt().plus(Duration.ofHours(1)));
        assertThat(catalog.get(admin, movie.id()).candidate().id()).isEqualTo(first.bindingId());
        assertThat(catalog.get(admin, movie.id()).version()).isEqualTo(movie.version() + 1);
        assertThat(count("catalog_upload_intents")).isEqualTo(1);
        assertThat(count("catalog_media_bindings")).isEqualTo(1);
        assertThatThrownBy(() -> uploads.reserve(admin, movie.id(), requestId, movie.version(), 1025, "a".repeat(64)))
                .isInstanceOf(DomainException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> uploads.reserve(admin, movie.id(), UUID.randomUUID(), movie.version(), 1024, "a".repeat(64)))
                .isInstanceOf(DomainException.class).hasMessage("VERSION_CONFLICT");
        assertThatThrownBy(() -> uploads.read(otherAdmin, first.uploadId()))
                .isInstanceOf(DomainException.class).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> uploads.reserve(viewer, movie.id(), UUID.randomUUID(), movie.version(), 1024, "a".repeat(64)))
                .hasMessage("ACCESS_DENIED");
    }

    @Test
    void rollbackAndDatabaseConstraintsProtectReservation() {
        var admin = actor("ADMIN");
        var movie = movie(admin);
        var template = new TransactionTemplate(transactions);
        template.executeWithoutResult(status -> {
            uploads.reserve(admin, movie.id(), UUID.randomUUID(), movie.version(), 1024, "b".repeat(64));
            status.setRollbackOnly();
        });
        assertThat(count("catalog_upload_intents")).isZero();
        assertThat(count("catalog_media_bindings")).isZero();
        assertThat(catalog.get(admin, movie.id()).version()).isEqualTo(movie.version());
        var saved = uploads.reserve(admin, movie.id(), UUID.randomUUID(), movie.version(), 1024, "b".repeat(64));
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog_upload_intents SET byte_length = 268435457 WHERE id = ?", saved.uploadId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog_upload_intents SET binding_id = ? WHERE id = ?",
                UUID.randomUUID(), saved.uploadId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(uploads.read(admin, saved.uploadId()).byteLength()).isEqualTo(1024);
    }

    @Test
    void coreVersionSevenUpgradesToEightWithoutLosingExistingRows() {
        Flyway before = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_m03").defaultSchema("upgrade_m03").target("7").load();
        before.migrate();
        UUID existing = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID playback = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO upgrade_m03.identity_accounts(id, email, display_name, password_hash, role)
                VALUES (?, ?, 'Existing', 'inert-fixture', 'USER')
                """, existing, existing + "@example.test");
        jdbc.update("INSERT INTO upgrade_m03.profiles(id, account_id, name) VALUES (?, ?, 'Existing')", profile, existing);
        jdbc.update("""
                INSERT INTO upgrade_m03.identity_sessions(id, account_id, created_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, session, existing);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO upgrade_m03.catalog_contents(id, kind) VALUES (?, 'MOVIE')", content);
            jdbc.update("""
                    INSERT INTO upgrade_m03.catalog_revisions(content_id, revision, metadata)
                    VALUES (?, 1, '{}'::jsonb)
                    """, content);
            jdbc.update("""
                    INSERT INTO upgrade_m03.catalog_media_bindings(id, content_id, asset_id, asset_version, state, duration_seconds)
                    VALUES (?, ?, ?, 1, 'READY', 60)
                    """, binding, content, asset);
            jdbc.update("""
                    UPDATE upgrade_m03.catalog_contents
                    SET active_binding = ?, published_revision = 1, published_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, binding, content);
            jdbc.update("""
                    INSERT INTO upgrade_m03.playback_sessions(id, account_id, profile_id, auth_session_id,
                        content_id, binding_id, asset_id, asset_version, published_revision,
                        duration_ms, opened_at, expires_at, last_progress_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 60000,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '5 minutes', CURRENT_TIMESTAMP)
                    """, playback, existing, profile, session, content, binding, asset);
            jdbc.update("""
                    INSERT INTO upgrade_m03.watch_history(profile_id, content_id, latest_session_id,
                        position_ms, duration_ms, updated_at)
                    VALUES (?, ?, ?, 1000, 60000, CURRENT_TIMESTAMP)
                    """, profile, content, playback);
        });
        Flyway after = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_m03").defaultSchema("upgrade_m03").load();
        after.migrate();
        after.migrate();
        after.validate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_m03.identity_accounts WHERE id = ?", Integer.class, existing))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_m03.catalog_upload_intents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT active_binding FROM upgrade_m03.catalog_contents WHERE id = ?",
                UUID.class, content)).isEqualTo(binding);
        assertThat(jdbc.queryForObject("SELECT latest_session_id FROM upgrade_m03.watch_history WHERE profile_id = ?",
                UUID.class, profile)).isEqualTo(playback);
    }

    @Test
    void simultaneousCreatorRetriesReserveOneIntentWithoutJvmLock() throws Exception {
        var admin = actor("ADMIN");
        var movie = movie(admin);
        UUID requestId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<UploadIntentService.Reservation> attempt = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
            return uploads.reserve(admin, movie.id(), requestId, movie.version(), 1024, "c".repeat(64));
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("catalog_upload_intents")).isEqualTo(1);
        assertThat(count("catalog_media_bindings")).isEqualTo(1);
        assertThat(catalog.get(admin, movie.id()).version()).isEqualTo(movie.version() + 1);
    }

    @Test
    void reservationHoldsAccountBeforeWaitingForCatalog() throws Exception {
        var admin = actor("ADMIN");
        var movie = movie(admin);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reservation = new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM catalog_contents WHERE id = ? FOR UPDATE", UUID.class, movie.id());
                var future = pool.submit(() -> uploads.reserve(admin, movie.id(), UUID.randomUUID(),
                        movie.version(), 1024, "d".repeat(64)));
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean accountLocked = false;
                    while (!accountLocked && System.nanoTime() < deadline) {
                        try (var connection = jdbc.getDataSource().getConnection();
                                var statement = connection.prepareStatement("""
                                        SELECT id FROM identity_accounts WHERE id = ? FOR UPDATE NOWAIT
                                        """)) {
                            statement.setObject(1, admin.accountId());
                            try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
                        } catch (SQLException exception) {
                            if (!"55P03".equals(exception.getSQLState())) { throw exception; }
                            accountLocked = true;
                        }
                        if (!accountLocked) { Thread.sleep(25); }
                    }
                    assertThat(accountLocked).isTrue();
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not verify lock order", exception);
                }
                return future;
            });
            assertThat(reservation.get(20, TimeUnit.SECONDS).bindingId()).isNotNull();
        }
    }

    private IdentityPrincipal actor(String role) {
        UUID id = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Fixture', 'inert-fixture', ?)",
                id, id + "@example.test", role);
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                session, id);
        return new IdentityPrincipal(id, session, role, false);
    }

    private AdminView movie(IdentityPrincipal admin) {
        var metadata = new Metadata("Upload fixture", "Description", List.of("drama"), 2026,
                "en", List.of("Cast"), List.of("posters/sample"), Tier.FREE);
        return catalog.create(admin, new Create(Kind.MOVIE, null, null, metadata));
    }

    private int count(String table) {
        return switch (table) {
            case "catalog_upload_intents", "catalog_media_bindings" ->
                    jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            default -> throw new IllegalArgumentException();
        };
    }
}
