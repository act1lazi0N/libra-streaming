package com.libra.streaming.core.catalog;

import com.libra.streaming.core.TestIdentityProperties;
import com.libra.streaming.core.api.DomainException;
import com.libra.streaming.core.identity.IdentityPrincipal;
import com.libra.streaming.core.integration.media.MediaControlClient;
import com.libra.streaming.core.integration.media.MediaControlModels;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static com.libra.streaming.core.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest
class UploadProvisioningIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("upload_provisioning").withUsername("upload_provisioning")
            .withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired UploadProvisioningService uploads;
    @MockitoBean MediaControlClient media;
    IdentityPrincipal admin;
    AdminView movie;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE identity_accounts, catalog_contents, outbox_events CASCADE");
        UUID account = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Fixture', 'inert-fixture', 'ADMIN')",
                account, account + "@example.test");
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                session, account);
        admin = new IdentityPrincipal(account, session, "ADMIN", false);
        movie = catalog.create(admin, new Create(Kind.MOVIE, null, null,
                new Metadata("M09 fixture", "Description", List.of("drama"), 2026, "en",
                        List.of("Cast"), List.of("posters/sample"), Tier.FREE)));
    }

    @Test void uncertainEnsureCommitsOneIdentityAndRetryConverges() {
        UUID requestId = UUID.randomUUID(), correlation = UUID.randomUUID();
        var command = new UploadProvisioningService.Create(requestId, movie.version(), 1024, "a".repeat(64));
        var calls = new AtomicInteger();
        when(media.ensure(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return new MediaControlModels.Result(null, MediaControlModels.Failure.UNAVAILABLE);
            }
            UUID uploadId = invocation.getArgument(0);
            MediaControlModels.EnsureUpload ensured = invocation.getArgument(1);
            return new MediaControlModels.Result(new MediaControlModels.Status(uploadId, ensured.contentId(),
                    ensured.bindingId(), ensured.assetId(), ensured.assetVersion(),
                    MediaControlModels.UploadState.OPEN, MediaControlModels.AssetState.UPLOADING,
                    null, 0, null, ensured.expiresAt()), null);
        });
        assertThatThrownBy(() -> uploads.create(admin, movie.id(), command, correlation))
                .isInstanceOf(DomainException.class).hasMessage("MEDIA_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        UUID uploadId = jdbc.queryForObject("SELECT id FROM catalog_upload_intents", UUID.class);
        var recovered = uploads.create(admin, movie.id(), command, correlation);
        assertThat(recovered.created()).isFalse();
        assertThat(recovered.status().uploadId()).isEqualTo(uploadId);
        assertThat(recovered.status().uploadState()).isEqualTo(MediaControlModels.UploadState.OPEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        verify(media, times(2)).ensure(eq(uploadId), any(), eq(correlation));
    }

    @Test void statusReadCanResumeUnprovisionedIntentWithoutNewCandidate() {
        UUID requestId = UUID.randomUUID(), correlation = UUID.randomUUID();
        var command = new UploadProvisioningService.Create(requestId, movie.version(), 1024, "b".repeat(64));
        when(media.ensure(any(), any(), any())).thenAnswer(invocation -> {
            UUID uploadId = invocation.getArgument(0);
            MediaControlModels.EnsureUpload ensured = invocation.getArgument(1);
            return new MediaControlModels.Result(new MediaControlModels.Status(uploadId, ensured.contentId(),
                    ensured.bindingId(), ensured.assetId(), ensured.assetVersion(),
                    MediaControlModels.UploadState.OPEN, MediaControlModels.AssetState.UPLOADING,
                    null, 0, null, ensured.expiresAt()), null);
        });
        var created = uploads.create(admin, movie.id(), command, correlation);
        assertThat(created.created()).isTrue();
        when(media.read(eq(created.status().uploadId()), eq(correlation)))
                .thenReturn(new MediaControlModels.Result(null, MediaControlModels.Failure.NOT_FOUND));
        assertThat(uploads.read(admin, created.status().uploadId(), correlation)).isEqualTo(created.status());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> uploads.create(admin, movie.id(), new UploadProvisioningService.Create(
                requestId, movie.version(), 2048, "b".repeat(64)), correlation))
                .isInstanceOf(DomainException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verify(media, times(2)).ensure(eq(created.status().uploadId()), any(), eq(correlation));
    }

    @Test void twoIdenticalCreatesConvergeOutsideTheCoreTransaction() throws Exception {
        UUID requestId = UUID.randomUUID(), correlation = UUID.randomUUID();
        var command = new UploadProvisioningService.Create(requestId, movie.version(), 1024, "c".repeat(64));
        when(media.ensure(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
            return successful(invocation);
        });
        var results = race(() -> uploads.create(admin, movie.id(), command, correlation),
                () -> uploads.create(admin, movie.id(), command, correlation));
        var first = (UploadProvisioningService.Created) results.get(0);
        var second = (UploadProvisioningService.Created) results.get(1);
        assertThat(second.status()).isEqualTo(first.status());
        assertThat((first.created() ? 1 : 0) + (second.created() ? 1 : 0)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        verify(media, times(2)).ensure(eq(first.status().uploadId()), any(), eq(correlation));
    }

    @Test void conflictingConcurrentCreatesLeaveOneCandidate() throws Exception {
        UUID correlation = UUID.randomUUID();
        when(media.ensure(any(), any(), any())).thenAnswer(UploadProvisioningIntegrationTest::successful);
        var first = new UploadProvisioningService.Create(UUID.randomUUID(), movie.version(), 1024, "d".repeat(64));
        var second = new UploadProvisioningService.Create(UUID.randomUUID(), movie.version(), 2048, "e".repeat(64));
        var outcomes = race(() -> outcome(first, correlation), () -> outcome(second, correlation));
        assertThat(outcomes.stream().filter(value -> value instanceof UploadProvisioningService.Created).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(value -> value instanceof DomainException error
                && error.code().equals("VERSION_CONFLICT")).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        verify(media, times(1)).ensure(any(), any(), eq(correlation));
    }

    @Test void sameRequestIdWithDifferentConcurrentFingerprintConflicts() throws Exception {
        UUID correlation = UUID.randomUUID(), requestId = UUID.randomUUID();
        when(media.ensure(any(), any(), any())).thenAnswer(UploadProvisioningIntegrationTest::successful);
        var first = new UploadProvisioningService.Create(requestId, movie.version(), 1024, "f".repeat(64));
        var second = new UploadProvisioningService.Create(requestId, movie.version(), 2048, "f".repeat(64));
        var outcomes = race(() -> outcome(first, correlation), () -> outcome(second, correlation));
        assertThat(outcomes.stream().filter(value -> value instanceof UploadProvisioningService.Created).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(value -> value instanceof DomainException error
                && error.code().equals("IDEMPOTENCY_CONFLICT")).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
    }

    @Test void interruptAfterCoreCommitLeavesOneRecoverableIntent() throws Exception {
        UUID correlation = UUID.randomUUID(), requestId = UUID.randomUUID();
        var command = new UploadProvisioningService.Create(requestId, movie.version(), 1024, "1".repeat(64));
        var reachedMedia = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<String>();
        var interrupted = new AtomicReference<Boolean>();
        when(media.ensure(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reachedMedia.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.set(Thread.currentThread().isInterrupted());
                return new MediaControlModels.Result(null, MediaControlModels.Failure.UNAVAILABLE);
            }
            throw new AssertionError("Expected interruption");
        });
        Thread caller = new Thread(() -> {
            try { uploads.create(admin, movie.id(), command, correlation); }
            catch (DomainException exception) { failure.set(exception.code()); }
            finally { finished.countDown(); }
        });
        caller.start();
        assertThat(reachedMedia.await(10, TimeUnit.SECONDS)).isTrue();
        UUID committedId = jdbc.queryForObject("SELECT id FROM catalog_upload_intents", UUID.class);
        assertThat(committedId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_media_bindings", Integer.class)).isEqualTo(1);
        caller.interrupt();
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isEqualTo("MEDIA_UNAVAILABLE");
        assertThat(interrupted.get()).isTrue();
        reset(media);
        when(media.ensure(any(), any(), any())).thenAnswer(UploadProvisioningIntegrationTest::successful);
        assertThat(uploads.create(admin, movie.id(), command, correlation).status().uploadId()).isEqualTo(committedId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_upload_intents", Integer.class)).isEqualTo(1);
    }

    @Test void retiredOrReplacedCandidateCannotBeReprovisioned() {
        UUID correlation = UUID.randomUUID(), requestId = UUID.randomUUID();
        var command = new UploadProvisioningService.Create(requestId, movie.version(), 1024, "2".repeat(64));
        when(media.ensure(any(), any(), any())).thenAnswer(UploadProvisioningIntegrationTest::successful);
        var created = uploads.create(admin, movie.id(), command, correlation).status();
        jdbc.update("UPDATE catalog_contents SET candidate_binding = NULL WHERE id = ?", movie.id());
        when(media.read(eq(created.uploadId()), eq(correlation)))
                .thenReturn(new MediaControlModels.Result(null, MediaControlModels.Failure.NOT_FOUND));
        assertThatThrownBy(() -> uploads.read(admin, created.uploadId(), correlation))
                .isInstanceOf(DomainException.class).hasMessage("UPLOAD_STATE_CONFLICT");
        assertThatThrownBy(() -> uploads.create(admin, movie.id(), command, correlation))
                .isInstanceOf(DomainException.class).hasMessage("UPLOAD_STATE_CONFLICT");
        UUID replacement = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_media_bindings(id, content_id, asset_id, asset_version) VALUES (?, ?, ?, 1)",
                replacement, movie.id(), UUID.randomUUID());
        jdbc.update("UPDATE catalog_contents SET candidate_binding = ?, version = version + 1 WHERE id = ?",
                replacement, movie.id());
        assertThatThrownBy(() -> uploads.create(admin, movie.id(), command, correlation))
                .isInstanceOf(DomainException.class).hasMessage("UPLOAD_STATE_CONFLICT");
        verify(media, times(1)).ensure(eq(created.uploadId()), any(), eq(correlation));
    }

    @Test void nonPlayableActiveRevokedAndForeignOperationsCannotProvision() {
        UUID correlation = UUID.randomUUID();
        var metadata = new Metadata("Series", "Description", List.of("drama"), 2026,
                "en", List.of("Cast"), List.of("posters/sample"), Tier.FREE);
        var series = catalog.create(admin, new Create(Kind.SERIES, null, null, metadata));
        assertThatThrownBy(() -> uploads.create(admin, series.id(), new UploadProvisioningService.Create(
                UUID.randomUUID(), series.version(), 1024, "3".repeat(64)), correlation))
                .isInstanceOf(DomainException.class).hasMessage("UPLOAD_NOT_ALLOWED");
        UUID active = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_media_bindings(id, content_id, asset_id, asset_version) VALUES (?, ?, ?, 1)",
                active, movie.id(), UUID.randomUUID());
        jdbc.update("UPDATE catalog_contents SET active_binding = ? WHERE id = ?", active, movie.id());
        assertThatThrownBy(() -> uploads.create(admin, movie.id(), new UploadProvisioningService.Create(
                UUID.randomUUID(), movie.version(), 1024, "4".repeat(64)), correlation))
                .isInstanceOf(DomainException.class).hasMessage("UPLOAD_NOT_ALLOWED");
        jdbc.update("UPDATE catalog_contents SET active_binding = NULL WHERE id = ?", movie.id());
        when(media.ensure(any(), any(), any())).thenAnswer(UploadProvisioningIntegrationTest::successful);
        var created = uploads.create(admin, movie.id(), new UploadProvisioningService.Create(
                UUID.randomUUID(), movie.version(), 1024, "5".repeat(64)), correlation).status();
        UUID foreignAccount = UUID.randomUUID(), foreignSession = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_accounts(id, email, display_name, password_hash, role) VALUES (?, ?, 'Foreign', 'inert-fixture', 'ADMIN')",
                foreignAccount, foreignAccount + "@example.test");
        jdbc.update("INSERT INTO identity_sessions(id, account_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                foreignSession, foreignAccount);
        var other = new IdentityPrincipal(foreignAccount, foreignSession, "ADMIN", false);
        assertThatThrownBy(() -> uploads.read(other, created.uploadId(), correlation))
                .isInstanceOf(DomainException.class).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> uploads.issueUrl(other, created.uploadId(), correlation))
                .isInstanceOf(DomainException.class).hasMessage("NOT_FOUND");
        jdbc.update("UPDATE identity_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", admin.sessionId());
        assertThatThrownBy(() -> uploads.read(admin, created.uploadId(), correlation))
                .hasMessage("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> uploads.issueUrl(admin, created.uploadId(), correlation))
                .hasMessage("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> uploads.create(admin, movie.id(), new UploadProvisioningService.Create(
                UUID.randomUUID(), movie.version(), 1024, "6".repeat(64)), correlation))
                .hasMessage("INVALID_CREDENTIALS");
        verify(media, times(1)).ensure(any(), any(), eq(correlation));
        verify(media, never()).read(any(), any());
        verify(media, never()).issueUrl(any(), any());
    }

    private Object outcome(UploadProvisioningService.Create command, UUID correlation) {
        try { return uploads.create(admin, movie.id(), command, correlation); }
        catch (DomainException exception) { return exception; }
    }

    private static MediaControlModels.Result successful(InvocationOnMock invocation) {
        UUID uploadId = invocation.getArgument(0);
        MediaControlModels.EnsureUpload ensured = invocation.getArgument(1);
        return new MediaControlModels.Result(new MediaControlModels.Status(uploadId, ensured.contentId(),
                ensured.bindingId(), ensured.assetId(), ensured.assetVersion(),
                MediaControlModels.UploadState.OPEN, MediaControlModels.AssetState.UPLOADING,
                null, 0, null, ensured.expiresAt()), null);
    }

    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Object> left = () -> { ready.countDown(); start.await(); return first.call(); };
        Callable<Object> right = () -> { ready.countDown(); start.await(); return second.call(); };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstResult = pool.submit(left);
            var secondResult = pool.submit(right);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        }
    }
}
