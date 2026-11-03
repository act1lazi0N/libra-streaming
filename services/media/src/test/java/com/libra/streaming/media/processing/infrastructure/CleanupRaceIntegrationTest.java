package com.libra.streaming.media.processing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.libra.streaming.media.MediaApplication;
import com.libra.streaming.media.lifecycle.application.CleanupPlans;
import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import com.libra.streaming.media.lifecycle.application.LifecycleEvents;
import com.libra.streaming.media.lifecycle.application.LifecycleSweeper;
import com.libra.streaming.media.lifecycle.application.ObjectCleanup;
import com.libra.streaming.media.lifecycle.domain.CleanupPlan;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.storage.StorageLayoutFixture;
import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * M32 against real PostgreSQL and SeaweedFS: cleanup raced with the writers it must outlast (an old worker still
 * writing its abandoned attempt, a successor finalizing, a browser PUT across the deadline, other sweepers),
 * interrupted at every step and resumed, and observed while a dependency cannot answer.
 */
@ExtendWith(OutputCaptureExtension.class)
class CleanupRaceIntegrationTest extends LifecycleFixture {
    static final Duration GRACE = Duration.ofHours(1);
    static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired DataSource dataSource;

    @Test
    void aChangedStorageLayoutIsNeverRecordedAsCleaned() throws Exception {
        // Found by the M32 audit: keys were derived from today's prefixes only, so after a source or HLS prefix
        // change cleanup looked in the wrong place, found nothing and recorded the upload as cleaned for good.
        var ready = readyWithLeftovers(21);
        settle(ready.upload().uploadId());
        var moved = StorageLayoutFixture.withPrefixes(configured, "frozen/", "media/",
                Path.of("target", "layout-fixture").toAbsolutePath());
        var movedStorage = StorageLayoutFixture.cleanupStorage(client, moved);
        var elsewhere = new ObjectCleanup(movedStorage.storage(), plans, metrics, 1000);
        var sweeper = sweeper(plans, elsewhere, 20);
        double refused = failures("CLEANUP_LAYOUT_CHANGED");

        for (int pass = 0; pass < 3; pass++) {
            jdbc.update("UPDATE media_uploads SET cleanup_attempted_at = NULL");
            sweeper.pass();
        }

        assertThat(cleanedAt(ready.upload().uploadId())).as("objects under the recorded layout remain").isNull();
        assertThat(gone(BUCKET, attemptKey(ready.upload(), 1))).isFalse();
        assertThat(objects(HLS, ready.oldOutput() + "/")).hasSize(2);
        assertThat(failures("CLEANUP_LAYOUT_CHANGED") - refused).isEqualTo(3);
        sweeper.close();
        movedStorage.close();

        // The layout every running instance derives keys from is recorded at startup, and an instance configured
        // with another one refuses to start, without naming the values it compared.
        assertThat(jdbc.queryForList("SELECT source_prefix || ' ' || hls_prefix FROM media_storage_layout",
                String.class)).containsExactly("sources/ hls/");
        StorageLayoutFixture.verify(jdbc, configured);
        assertThatThrownBy(() -> StorageLayoutFixture.verify(jdbc, moved))
                .isInstanceOf(IllegalStateException.class).hasMessageEndingWith(": source_prefix, hls_prefix")
                .hasMessageNotContainingAny("frozen", "media/", "sources/", "hls/", "libra-");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM media_storage_layout"))
                .as("fixed once an upload exists").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE media_storage_layout SET hls_prefix = 'media/'"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForList("SELECT source_prefix || ' ' || hls_prefix FROM media_storage_layout",
                String.class)).as("a refused layout changes nothing").containsExactly("sources/ hls/");
    }

    @Test
    void anInstanceConfiguredWithAnotherLayoutNeverStartsItsWorkerOrSweeper() {
        // The whole application, worker and sweeper enabled, with one queued job waiting: startup must end before
        // the worker claims it or the sweeper derives a key.
        var waiting = queued(bytes(64, 60));
        stage(waiting, bytes(64, 60));
        // Command-line arguments, so they win over application.yml as a deployment's environment would.
        var settings = Map.ofEntries(
                        Map.entry("server.port", "0"),
                        Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                        Map.entry("spring.datasource.username", POSTGRES.getUsername()),
                        Map.entry("spring.datasource.password", POSTGRES.getPassword()),
                        Map.entry("libra.media.storage.internal-endpoint", configured.internalEndpoint()),
                        Map.entry("libra.media.storage.browser-endpoint", configured.browserEndpoint()),
                        Map.entry("libra.media.storage.browser-origin", configured.browserOrigin()),
                        Map.entry("libra.media.storage.region", configured.region()),
                        Map.entry("libra.media.storage.access-key", configured.accessKey()),
                        Map.entry("libra.media.storage.secret-key", configured.secretKey()),
                        Map.entry("libra.media.storage.source-bucket", configured.sourceBucket()),
                        Map.entry("libra.media.storage.hls-bucket", configured.hlsBucket()),
                        Map.entry("libra.media.storage.staging-prefix", configured.stagingPrefix()),
                        Map.entry("libra.media.storage.source-prefix", configured.sourcePrefix()),
                        Map.entry("libra.media.storage.hls-prefix", "media/"),
                        Map.entry("libra.media.storage.connect-timeout", "3s"),
                        Map.entry("libra.media.storage.request-timeout", "15s"),
                        Map.entry("libra.media.storage.max-object-bytes", "1048576"),
                        Map.entry("libra.media.storage.allow-http", "true"),
                        Map.entry("libra.media.storage.scratch-directory", Path.of("target", "layout-startup").toAbsolutePath().toString()),
                        Map.entry("libra.media.probe.executable", FfprobeLocator.locate().toString()),
                        Map.entry("libra.media.transcode.executable", MediaFixtures.ffmpeg().toString()),
                        Map.entry("libra.media.worker.enabled", "true"),
                        Map.entry("libra.media.worker.poll-interval", "10ms"),
                        Map.entry("libra.media.lifecycle.enabled", "true"),
                        Map.entry("libra.media.lifecycle.poll-interval", "1s"));
        var args = settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        assertThatThrownBy(() -> new SpringApplicationBuilder(MediaApplication.class).run(args)).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageEndingWith(": hls_prefix");
        var status = uploads.read(waiting.uploadId());
        assertThat(status.processing().stage()).isEqualTo("QUEUED");
        assertThat(status.attemptCount()).isZero();
        assertThat(gone(BUCKET, stagingKey(waiting))).isFalse();
    }

    @Test
    void gaugesReportUnknownWithinTheirBoundWhileTheDatabaseCannotAnswer() throws Exception {
        // Found by the M32 audit (an M31 limit): gauge reads had no statement timeout, so a table lock or a stalled
        // database held the sampling thread for as long as it lasted instead of reporting the value as unknown.
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.createStatement()) {
                lock.execute("LOCK TABLE media_jobs, media_uploads, media_outbox_events IN ACCESS EXCLUSIVE MODE");
            }
            try {
                for (var gauge : List.of(registry.get("libra.media.jobs").tag("stage", "queued").gauge(),
                        registry.get("libra.media.cleanup.pending").gauge(),
                        registry.get("libra.media.outbox.backlog").tag("state", "PENDING").gauge())) {
                    double value = assertTimeoutPreemptively(Duration.ofSeconds(8), gauge::value,
                            "a gauge must not wait on the database beyond its bound");
                    assertThat(value).as(gauge.getId().getName()).isNaN();
                }
            } finally {
                holder.rollback();
            }
        }
        assertThat(registry.get("libra.media.jobs").tag("stage", "queued").gauge().value()).isZero();
    }

    @Test
    void cleanupRacesAnOldWorkerStillWritingAndASuccessorFinalizing() throws Exception {
        // Attempt 1 lost its lease but keeps writing (the worst case: a worker without M32's own deadline), while
        // attempt 2 selects its source and finalizes, and a sweeper with no grace at all polls throughout.
        var upload = queued(bytes(64, 22));
        stage(upload, bytes(64, 22));
        // The upload deadline is long past, so only the job decides when the upload settles.
        backdate(upload.uploadId(), Duration.ofHours(2));
        put(BUCKET, attemptKey(upload, 1));
        var old = claimFor(upload);
        expire(old);
        var successor = claimFor(upload);
        assertThat(successor.attempt()).isEqualTo(2);
        String oldOutput = "hls/" + upload.uploadId() + "/attempt-1";
        String source = attemptKey(upload, 2);
        String output = "hls/" + upload.uploadId() + "/attempt-2";
        put(HLS, "hls/" + upload.uploadId() + "/notes.txt");

        var deletions = new ConcurrentLinkedQueue<Long>();
        var events = recording(deletions);
        var cleanup = new ObjectCleanup(cleanupStorage, plans, events, 1000);
        var stop = new AtomicBoolean();
        var writerStop = new AtomicBoolean();
        var written = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        long beforeReady;
        try {
            Future<?> writer = pool.submit(() -> {
                while (!writerStop.get()) {
                    put(HLS, oldOutput + "/segment-" + written.getAndIncrement() + ".ts");
                    sleep(5);
                }
                return null;
            });
            Future<?> sweeping = pool.submit(() -> {
                while (!stop.get()) {
                    for (var plan : plans.due(Duration.ZERO, 20)) { cleanup.clean(plan); }
                    sleep(5);
                }
                return null;
            });
            sleep(200);
            put(BUCKET, source);
            assertThat(sources.select(successor, source)).isTrue();
            assertThat(stages.beginTranscoding(successor)).isTrue();
            sleep(100);
            assertThat(outputs.beginFinalizing(successor)).isTrue();
            put(HLS, output + "/segment-00000.ts");
            put(HLS, output + "/master.m3u8");
            sleep(100);
            assertThat(deletions).as("nothing is deleted before the job is terminal").isEmpty();
            beforeReady = System.nanoTime();
            assertThat(outputs.select(successor, new com.libra.streaming.media.processing.application
                    .OutputSelections.Selection(source, output, output + "/master.m3u8", 10))).isTrue();
            sleep(300);
            writerStop.set(true);
            writer.get(10, TimeUnit.SECONDS);
            await(() -> cleanedAt(upload.uploadId()) != null || sweeping.isDone());
            stop.set(true);
            sweeping.get(10, TimeUnit.SECONDS);
            assertThat(cleanedAt(upload.uploadId())).isNotNull();
        } finally {
            stop.set(true);
            writerStop.set(true);
            pool.shutdownNow();
        }

        assertThat(deletions).isNotEmpty().allMatch(at -> at - beforeReady > 0);
        assertThat(written.get()).isGreaterThan(10);
        assertThat(objects(HLS, oldOutput + "/")).as("every late object of the old attempt").isEmpty();
        assertThat(gone(BUCKET, attemptKey(upload, 1))).isTrue();
        assertThat(gone(BUCKET, stagingKey(upload))).isTrue();
        assertThat(objects(HLS, "hls/" + upload.uploadId() + "/")).containsExactlyInAnyOrder(
                output + "/segment-00000.ts", output + "/master.m3u8", "hls/" + upload.uploadId() + "/notes.txt");
        assertThat(frozen(source)).hasSize(16);
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                upload.assetId())).isEqualTo("READY");
    }

    @Test
    void concurrentSweepersCleanEachUploadOnceAndNeverASelectedObject() throws Exception {
        var all = new ArrayList<Ready>();
        for (int i = 0; i < 6; i++) {
            var ready = readyWithLeftovers(30 + i);
            settle(ready.upload().uploadId());
            all.add(ready);
        }
        double cleanedBefore = registry.get("libra.media.cleanup.cleaned").counter().count();
        var sweepers = List.of(sweeper(plans, new ObjectCleanup(cleanupStorage, plans, metrics, 1000), 2),
                sweeper(plans, new ObjectCleanup(cleanupStorage, plans, metrics, 1000), 2),
                sweeper(plans, new ObjectCleanup(cleanupStorage, plans, metrics, 1000), 2));
        var pool = Executors.newFixedThreadPool(sweepers.size());
        var start = new CountDownLatch(1);
        try {
            var runs = new ArrayList<Future<?>>();
            for (var sweeper : sweepers) {
                runs.add(pool.submit(() -> {
                    start.await();
                    for (int pass = 0; pass < 12; pass++) { sweeper.pass(); }
                    return null;
                }));
            }
            start.countDown();
            for (var run : runs) { run.get(60, TimeUnit.SECONDS); }
        } finally {
            pool.shutdownNow();
            sweepers.forEach(LifecycleSweeper::close);
        }

        for (var ready : all) {
            var id = ready.upload().uploadId();
            assertThat(cleanedAt(id)).as("upload %s", id).isNotNull();
            assertThat(gone(BUCKET, attemptKey(ready.upload(), 1))).isTrue();
            assertThat(gone(BUCKET, attemptKey(ready.upload(), 2))).isFalse();
            assertThat(objects(HLS, "hls/" + id + "/")).containsExactlyInAnyOrder(ready.output() + "/segment-00000.ts",
                    ready.output() + "/master.m3u8", "hls/" + id + "/notes.txt");
        }
        assertThat(registry.get("libra.media.cleanup.cleaned").counter().count() - cleanedBefore)
                .as("each upload is recorded exactly once, whichever sweeper got there").isEqualTo(6);
    }

    @Test
    void anInterruptedPassResumesWithoutTouchingSelectedOrUnrelatedObjects(CapturedOutput output) throws Exception {
        var ready = readyWithLeftovers(40);
        var id = ready.upload().uploadId();
        settle(id);
        int logStart = output.getAll().length();

        // 1. A process takes the upload (recording the attempt) and dies before deleting anything.
        assertThat(plans.due(GRACE, 20)).extracting(CleanupPlan::uploadId).containsExactly(id);
        assertThat(gone(BUCKET, stagingKey(ready.upload()))).isFalse();
        assertThat(objects(HLS, ready.oldOutput() + "/")).hasSize(2);

        // 2. Storage fails after the first deletion: the pass ends, nothing is recorded.
        var deletes = new AtomicInteger();
        var flaky = new FailingStorage(cleanupStorage, () -> deletes.incrementAndGet() > 1);
        sweeper(plans, new ObjectCleanup(flaky, plans, metrics, 1000), 20).pass();
        assertThat(gone(BUCKET, stagingKey(ready.upload()))).isTrue();
        assertThat(gone(BUCKET, attemptKey(ready.upload(), 1))).as("refused after the failure").isFalse();
        assertThat(cleanedAt(id)).isNull();

        // 3. Every leftover is deleted, then the database refuses the record: the next pass must record it.
        var normal = sweeper(plans, new ObjectCleanup(cleanupStorage, plans, metrics, 1000), 20);
        jdbc.update("UPDATE media_uploads SET cleanup_attempted_at = NULL");
        normal.pass();
        assertThat(gone(BUCKET, attemptKey(ready.upload(), 1))).isTrue();
        assertThat(objects(HLS, ready.oldOutput() + "/")).isEmpty();
        var refusing = new RefusingPlans(plans);
        sweeper(refusing, new ObjectCleanup(cleanupStorage, refusing, metrics, 1000), 20).pass();
        assertThat(cleanedAt(id)).isNull();

        // 4. Resumed by an ordinary pass: recorded once, with the selection and the unrelated key intact.
        normal.pass();
        assertThat(cleanedAt(id)).isNotNull();
        assertThat(gone(BUCKET, attemptKey(ready.upload(), 2))).isFalse();
        assertThat(objects(HLS, "hls/" + id + "/")).containsExactlyInAnyOrder(ready.output() + "/segment-00000.ts",
                ready.output() + "/master.m3u8", "hls/" + id + "/notes.txt");
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                ready.upload().assetId())).isEqualTo("READY");

        // What the failures left for an operator: fixed codes only, no identifier, key, bucket or remote text.
        String log = output.getAll().substring(logStart);
        assertThat(log).contains("CLEANUP_STORAGE_UNAVAILABLE", "CLEANUP_FAILED");
        assertThat(UUID_TEXT.matcher(log).find()).as("no identifier in the log").isFalse();
        assertThat(log).doesNotContain("libra-source", "libra-hls", "attempt-", "X-Amz", "staging/");
        assertPrivateMeters();
    }

    @Test
    void aStagingPutAcrossTheDeadlineIsFoundUnlessItOutlivesTheRecord() throws Exception {
        // A browser PUT admitted before the deadline keeps sending after the upload expired.
        byte[] content = bytes(64, 50);
        var crossing = signedUpload(content);
        try (var put = SlowPut.start(crossing.grant(), content)) {
            // The deadline passes while the body is still being sent (moved in the database, not waited for).
            backdate(crossing.upload().uploadId(), Duration.ofMinutes(1));
            assertThat(expiry.expire(10)).isEqualTo(1);
            put.finish();
        }
        assertThat(gone(BUCKET, stagingKey(crossing.upload()))).as("the admitted PUT landed after expiry").isFalse();
        assertThatThrownBy(() -> uploads.queue(crossing.upload().uploadId(), crossing.upload().assetId(), 1))
                .isInstanceOfSatisfying(UploadFailure.class, f -> assertThat(f.code()).isEqualTo("UPLOAD_EXPIRED"));
        var cleanup = new ObjectCleanup(cleanupStorage, plans, metrics, 1000);
        passes(cleanup, 2);
        assertThat(gone(BUCKET, stagingKey(crossing.upload()))).isTrue();
        assertThat(cleanedAt(crossing.upload().uploadId())).isNotNull();

        // The residual the grace exists for: a PUT still in flight when cleanup records the upload lands after it
        // and stays. Here the grace is zero; the configured grace (at least five minutes) must outlast every PUT.
        var outliving = signedUpload(bytes(64, 51));
        try (var put = SlowPut.start(outliving.grant(), bytes(64, 51))) {
            backdate(outliving.upload().uploadId(), Duration.ofMinutes(1));
            assertThat(expiry.expire(10)).isEqualTo(1);
            passes(cleanup, 2);
            assertThat(cleanedAt(outliving.upload().uploadId())).isNotNull();
            put.finish();
        }
        assertThat(gone(BUCKET, stagingKey(outliving.upload()))).isFalse();
        assertThat(plans.due(Duration.ZERO, 20)).isEmpty();
    }

    // Fixtures

    record Ready(MediaPersistenceService.Ensure upload, String output, String oldOutput) {}

    /** READY on attempt 2 after attempt 1 left a source and part of an output, plus a key no attempt reserved. */
    Ready readyWithLeftovers(long seed) {
        var upload = queued(bytes(64, seed));
        stage(upload, bytes(64, seed));
        var first = claimFor(upload);
        put(BUCKET, attemptKey(upload, 1));
        String oldOutput = "hls/" + upload.uploadId() + "/attempt-1";
        put(HLS, oldOutput + "/segment-00000.ts");
        put(HLS, oldOutput + "/media.m3u8");
        assertThat(leases.retry(first, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isTrue();
        jdbc.update("UPDATE media_jobs SET next_attempt_at = clock_timestamp() WHERE id = ?", first.jobId());
        var second = claimFor(upload);
        String source = attemptKey(upload, 2);
        String output = "hls/" + upload.uploadId() + "/attempt-2";
        put(BUCKET, source);
        put(HLS, output + "/segment-00000.ts");
        put(HLS, output + "/master.m3u8");
        commitReady(second, source, output);
        put(HLS, "hls/" + upload.uploadId() + "/notes.txt");
        return new Ready(upload, output, oldOutput);
    }

    LifecycleSweeper sweeper(CleanupPlans using, ObjectCleanup cleanup, int batch) {
        return new LifecycleSweeper(expiry, using, cleanup, metrics,
                new LifecycleSweeper.Settings(Duration.ofSeconds(1), batch, GRACE, Duration.ofSeconds(1)));
    }

    void passes(ObjectCleanup cleanup, int count) {
        for (int pass = 0; pass < count; pass++) {
            for (var plan : plans.due(Duration.ZERO, 20)) { cleanup.clean(plan); }
        }
    }

    double failures(String code) {
        return registry.get("libra.media.lifecycle.failures").tag("failure", code).counter().count();
    }

    /** Every Media meter is labelled from a fixed vocabulary: no identifier, key, bucket, URL or error text. */
    void assertPrivateMeters() {
        var labels = Pattern.compile("[a-z0-9_]+|[A-Z][A-Z_]+");
        var offending = Collections.synchronizedList(new ArrayList<String>());
        registry.getMeters().forEach(meter -> {
            var id = meter.getId();
            if (UUID_TEXT.matcher(id.toString()).find() || id.toString().contains("libra-source")
                    || id.toString().contains("libra-hls") || id.toString().contains("://")) {
                offending.add(id.toString());
            }
            if (id.getName().startsWith("libra.media.")) {
                id.getTags().forEach(tag -> {
                    if (!labels.matcher(tag.getValue()).matches()) { offending.add(id.toString()); }
                });
            }
        });
        assertThat(offending).isEmpty();
    }

    record Signed(MediaPersistenceService.Ensure upload,
            com.libra.streaming.media.storage.StagingUploadSigner.Grant grant) {}

    Signed signedUpload(byte[] content) {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, content.length, sha(content), Instant.now().plusSeconds(30));
        uploads.ensure(upload);
        return new Signed(upload, signer.sign(upload.uploadId(), stagingKey(upload), content.length, sha(content),
                upload.expiresAt(), Instant.now()));
    }

    /** A browser PUT through the signed URL that sends half its body, then waits to be finished. */
    static final class SlowPut implements AutoCloseable {
        private final HttpURLConnection connection;
        private final byte[] content;
        private boolean finished;

        private SlowPut(HttpURLConnection connection, byte[] content) {
            this.connection = connection;
            this.content = content;
        }

        static SlowPut start(com.libra.streaming.media.storage.StagingUploadSigner.Grant grant, byte[] content)
                throws Exception {
            var connection = (HttpURLConnection) URI.create(grant.url()).toURL().openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(content.length);
            grant.requiredHeaders().forEach(connection::setRequestProperty);
            connection.getOutputStream().write(content, 0, content.length / 2);
            connection.getOutputStream().flush();
            return new SlowPut(connection, content);
        }

        void finish() throws Exception {
            connection.getOutputStream().write(content, content.length / 2, content.length - content.length / 2);
            connection.getOutputStream().close();
            assertThat(connection.getResponseCode()).as("the PUT was admitted before the deadline").isEqualTo(200);
            finished = true;
        }

        @Override
        public void close() {
            if (!finished) { connection.disconnect(); }
        }
    }

    static LifecycleEvents recording(ConcurrentLinkedQueue<Long> deletions) {
        return new LifecycleEvents() {
            @Override public void expired(int uploads) {}
            @Override public void deleted(int objects) { deletions.add(System.nanoTime()); }
            @Override public void cleaned() {}
            @Override public void failed(String code) {}
        };
    }

    /** Delegates to the real adapter until told to fail, then fails every call as an outage would. */
    record FailingStorage(CleanupStorage real, java.util.function.BooleanSupplier failNow) implements CleanupStorage {
        @Override public String sourceKey(UUID uploadId, int attempt) { return real.sourceKey(uploadId, attempt); }
        @Override public String outputPrefix(UUID uploadId, int attempt) { return real.outputPrefix(uploadId, attempt); }
        @Override public boolean deleteStaging(String key) { check(); return real.deleteStaging(key); }
        @Override public boolean deleteSource(String key) { check(); return real.deleteSource(key); }
        @Override public Batch deleteOutput(String prefix, int limit) { check(); return real.deleteOutput(prefix, limit); }

        private void check() {
            if (failNow.getAsBoolean()) { throw new Unavailable(); }
        }
    }

    /** The real plans, except that the database refuses the cleanup record. */
    record RefusingPlans(CleanupPlans real) implements CleanupPlans {
        @Override public List<CleanupPlan> due(Duration writeGrace, int limit) { return real.due(writeGrace, limit); }
        @Override public boolean markCleaned(UUID uploadId) {
            throw new DataAccessResourceFailureException("connection refused");
        }
    }

    static void sleep(long millis) {
        try { Thread.sleep(Math.max(0, millis)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
