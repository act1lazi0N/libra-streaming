package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.application.OutputFinalizer;
import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.application.OutputStorage;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * Milestone 24: finalization under interruption, overtaking and partial output, against real SeaweedFS, PostgreSQL,
 * ffprobe and ffmpeg. A crash is simulated in this JVM: the stage throws an {@link Error} nothing maps to an outcome,
 * and the lease is left to run out exactly as a killed process leaves it ({@link #expire}). Each scenario ends with
 * exact counts of the asset's events and aggregate version, and exact key-and-digest snapshots of the generations.
 */
class FinalizationConsistencyIntegrationTest extends FinalizationFixture {
    @Autowired PlatformTransactionManager transactions;

    /** What a process death looks like from inside the stage: nothing after this point runs. */
    private static final class Died extends Error {
        Died() { super("simulated process death", null, false, false); }
    }

    private OutputStorage storing(StoreHook hook) {
        return new OutputStorage() {
            @Override public String prefix(JobLease claim) { return outputs.prefix(claim); }
            @Override public void store(String key, Path file, String contentType) {
                hook.store(key, () -> outputs.store(key, file, contentType));
            }
            @Override public Optional<Readback> read(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException {
                return outputs.read(key, bytes, cancelled);
            }
        };
    }

    @FunctionalInterface
    private interface StoreHook { void store(String key, Runnable write); }

    private OutputSelections selecting(SelectHook hook) {
        return new OutputSelections() {
            @Override public boolean beginFinalizing(JobLease claim) { return selections.beginFinalizing(claim); }
            @Override public boolean select(JobLease claim, Selection selection) {
                return hook.select(claim, selection);
            }
        };
    }

    @FunctionalInterface
    private interface SelectHook { boolean select(JobLease claim, OutputSelections.Selection selection); }

    private OutputSelections.Selection selectionOf(int attempt, int seconds) {
        return new OutputSelections.Selection(attemptKey(upload, 1), prefix(upload, attempt),
                prefix(upload, attempt) + "/master.m3u8", seconds);
    }

    @Test
    void aWorkerThatDiesAfterVerifyingItsOutputBeforeTheCommitIsRecoveredByTheNextAttempt() throws Exception {
        var dead = stagedClaim("valid-160x96-silent.mp4");
        var dies = new OutputFinalizer(transcoder, outputs, selecting((claim, selection) -> { throw new Died(); }));

        assertThatThrownBy(() -> dies.complete(dead, () -> false)).isInstanceOf(Died.class);

        // Everything was stored and read back; nothing was committed.
        assertNotReady();
        assertThat(job().get("stage")).isEqualTo("FINALIZING");
        assertThat(job().get("lease_token")).isEqualTo(dead.token());
        var orphan = generation(prefix(upload, 1));
        assertThat(orphan).containsKey(prefix(upload, 1) + "/master.m3u8");

        expire(dead);
        var next = claim();
        assertThat(next.attempt()).isEqualTo(2);
        assertThat(job().get("stage")).isEqualTo("CLAIMED");
        assertThat(events()).containsExactly("PROCESSING"); // recovery is not a new transition

        assertThat(finalizer.complete(next, () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);

        assertSingleReady(2);
        // The dead attempt's complete generation is untouched and unreferenced, and it can never be selected.
        assertThat(generation(prefix(upload, 1))).isEqualTo(orphan);
        var selected = asset();
        assertThat(selections.beginFinalizing(dead)).isFalse();
        assertThat(selections.select(dead, selectionOf(1, (Integer) selected.get("duration_seconds")))).isFalse();
        assertThat(asset()).isEqualTo(selected);
        assertThat(events()).containsExactly("PROCESSING", "READY");
    }

    @Test
    void aWorkerThatDiesMidUploadLeavesAnIncompleteOrphanThatIsNeverSelected() throws Exception {
        var dead = stagedClaim("valid-160x96-silent.mp4");
        var dies = new OutputFinalizer(transcoder, storing((key, write) -> {
            // Every segment is written; the process dies before the first playlist.
            if (key.endsWith("/rendition.m3u8")) { throw new Died(); }
            write.run();
        }), selections);

        assertThatThrownBy(() -> dies.complete(dead, () -> false)).isInstanceOf(Died.class);

        assertNotReady();
        var orphan = generation(prefix(upload, 1));
        assertThat(orphan).isNotEmpty().allSatisfy((key, digest) -> assertThat(key).matches(".*/segment-\\d{5}\\.ts"));

        expire(dead);
        assertThat(finalizer.complete(claim(), () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);

        assertSingleReady(2);
        assertThat(generation(prefix(upload, 1))).isEqualTo(orphan);
    }

    @Test
    void aStoredSegmentThatChangesBeforeReadbackIsNeverSelected() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        var changed = new AtomicReference<String>();
        // Storage acknowledges the segment, then holds different bytes of the same length.
        var tampered = new OutputFinalizer(transcoder, storing((key, write) -> {
            write.run();
            if (key.endsWith("/segment-00000.ts")) {
                byte[] bytes = hlsBytes(key);
                bytes[bytes.length / 2] ^= 0x5a;
                client.putObject(PutObjectRequest.builder().bucket(HLS).key(key).contentType("video/mp2t").build(),
                        RequestBody.fromBytes(bytes));
                changed.set(key);
            }
        }), selections);

        var result = tampered.complete(lease, () -> false);

        assertThat(result).isEqualTo(new OutputFinalizer.Result.Rejected(
                MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED)));
        assertThat(changed.get()).isNotNull();
        assertNotReady();
        assertThat(job().get("stage")).isEqualTo("FINALIZING");
        assertThat(workspaceDirectories()).isEmpty();

        assertThat(leases.retry(lease, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isTrue();
        assertThat(finalizer.complete(nextClaim(), () -> false)).isInstanceOf(OutputFinalizer.Result.Ready.class);

        assertSingleReady(2);
        assertThat(hlsKeys(prefix(upload, 1) + "/")).contains(changed.get());
    }

    @Test
    void anExpiredWorkerFinishingAfterItsSuccessorCannotTouchTheSelectedGeneration() throws Exception {
        var stale = stagedClaim("valid-160x96-silent.mp4");
        var overtaken = new AtomicBoolean();
        var selectedAsset = new AtomicReference<Map<String, Object>>();
        var selectedOutbox = new AtomicReference<List<Map<String, Object>>>();
        var selectedJob = new AtomicReference<Map<String, Object>>();
        var selectedGeneration = new AtomicReference<Map<String, String>>();
        // The stale claim passed FINALIZING, then stalls past its expiry on its first upload; meanwhile another
        // instance claims the job and finalizes it completely. Only then does the stale one carry on.
        var late = new OutputFinalizer(transcoder, storing((key, write) -> {
            if (overtaken.compareAndSet(false, true)) {
                expire(stale);
                try {
                    var successor = claim();
                    assertThat(successor.attempt()).isEqualTo(2);
                    assertThat(finalizer.complete(successor, () -> false))
                            .isInstanceOf(OutputFinalizer.Result.Ready.class);
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
                selectedAsset.set(asset());
                selectedOutbox.set(outbox());
                selectedJob.set(job());
                selectedGeneration.set(generation(prefix(upload, 2)));
            }
            write.run();
        }), selections);

        var result = late.complete(stale, () -> false);

        // It stored and verified its whole generation, but its fenced commit changed nothing.
        assertThat(result).isInstanceOf(OutputFinalizer.Result.LeaseLost.class);
        assertThat(overtaken).isTrue();
        assertSingleReady(2);
        assertThat(asset()).isEqualTo(selectedAsset.get());
        assertThat(outbox()).isEqualTo(selectedOutbox.get());
        assertThat(job()).isEqualTo(selectedJob.get());
        assertThat(generation(prefix(upload, 2))).isEqualTo(selectedGeneration.get());
        assertThat(generation(prefix(upload, 1))).containsKey(prefix(upload, 1) + "/master.m3u8");
        // The worker records nothing for a lost lease, and every lease operation of the stale claim is refused.
        assertThat(handler.process(stale, () -> false))
                .isEqualTo(MediaJobHandler.Outcome.settled());
        assertThat(leases.retry(stale, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isFalse();
        assertThat(leases.fail(stale, ProcessingFailure.CORRUPT_INPUT)).isFalse();
        assertThat(asset()).isEqualTo(selectedAsset.get());
        assertThat(outbox()).isEqualTo(selectedOutbox.get());
    }

    @Test
    void aCommitWhoseAcknowledgementIsLostIsNotRecordedAgainAndTheFinishedJobIsReplaySafe() throws Exception {
        byte[] content = MediaFixtures.bytes("valid-160x96-silent.mp4");
        upload = queued(content);
        stage(upload, content);
        var committed = new AtomicReference<JobLease>();
        // The commit reaches PostgreSQL, but its acknowledgement never reaches the worker.
        var ackLost = new OutputFinalizer(transcoder, outputs, selecting((claim, selection) -> {
            assertThat(selections.select(claim, selection)).isTrue();
            committed.set(claim);
            throw new IllegalStateException("commit acknowledgement lost");
        }));
        var reports = new CopyOnWriteArrayList<String>();
        var settings = new BoundedMediaWorker.Settings(Duration.ofMillis(50), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofSeconds(10));
        try (var worker = new BoundedMediaWorker(leases, ackLost, settings, reports::add)) {
            worker.start();
            await90s(() -> "SUCCEEDED".equals(job().get("stage")));
            Thread.sleep(500); // the worker maps the exception to a retry, which the spent lease must refuse
        }
        assertThat(reports).isEmpty();
        assertSingleReady(1);
        assertThat(job().get("failure_code")).isNull();
        var lease = committed.get();
        var asset = asset();
        var outbox = outbox();
        var job = job();
        var generation = generation(prefix(upload, 1));
        assertThat(hlsKeys("hls/" + upload.uploadId() + "/")).containsExactlyElementsOf(generation.keySet());

        // Replaying the finished job by any route changes nothing: the spent claim, a restarted worker, or another
        // instance looking for work.
        assertThat(handler.process(lease, () -> false)).isEqualTo(MediaJobHandler.Outcome.settled());
        assertThat(selections.beginFinalizing(lease)).isFalse();
        assertThat(selections.select(lease, selectionOf(1, (Integer) asset.get("duration_seconds")))).isFalse();
        assertThat(leases.renew(lease, Duration.ofSeconds(10))).isFalse();
        assertThat(leases.retry(lease, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isFalse();
        assertThat(leases.fail(lease, ProcessingFailure.CORRUPT_INPUT)).isFalse();
        assertThat(leases.release(lease)).isFalse();
        try (var restarted = new BoundedMediaWorker(leases, handler, settings, reports::add)) {
            restarted.start();
            Thread.sleep(500);
        }
        assertThat(leases.claim(Duration.ofSeconds(10))).isEmpty();
        assertThat(reports).isEmpty();
        assertThat(asset()).isEqualTo(asset);
        assertThat(outbox()).isEqualTo(outbox);
        assertThat(job()).isEqualTo(job);
        assertThat(generation(prefix(upload, 1))).isEqualTo(generation);
        assertThat(hlsKeys("hls/" + upload.uploadId() + "/")).containsExactlyElementsOf(generation.keySet());
        assertThat(workspaceDirectories()).isEmpty();
    }

    /** READY through the production adapter, without an encode: the database guard does not care about objects. */
    private JobLease readyWithoutEncoding() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        assertThat(prober.probe(lease, () -> false)).isInstanceOf(SourceProber.Result.Probed.class);
        assertThat(stages.beginTranscoding(lease)).isTrue();
        assertThat(selections.beginFinalizing(lease)).isTrue();
        return lease;
    }

    @Test
    void theDatabaseRefusesToChangeASelectedGenerationOrLeaveReady() throws Exception {
        var lease = readyWithoutEncoding();
        assertThat(selections.select(lease, selectionOf(1, 7))).isTrue();
        var ready = asset();
        var outbox = outbox();
        String prefix = prefix(upload, 1);

        var rewrites = Map.of(
                "output_prefix = '" + prefix(upload, 2) + "', master_manifest_key = '" + prefix(upload, 2)
                        + "/master.m3u8'", "media selected output is immutable",
                "master_manifest_key = '" + prefix + "/other.m3u8'", "media selected output is immutable",
                "duration_seconds = 8", "media selected output is immutable",
                "selected_source_key = 'sources/" + UUID.randomUUID() + "/attempt-1/source.mp4'",
                "media selected source is immutable",
                "state = 'FAILED'", "media READY is terminal",
                "state = 'PROCESSING'", "media READY is terminal",
                // A version bump with no event of that version would leave READY without its announcement.
                "aggregate_version = aggregate_version + 1", "media READY requires its event");
        rewrites.forEach((change, reason) -> {
            assertThatThrownBy(() -> jdbc.update("UPDATE media_assets SET " + change + " WHERE asset_id = ?",
                    upload.assetId())).as(change).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining(reason);
            assertThat(asset()).as(change).isEqualTo(ready);
        });
        // A change that leaves the selection alone is still allowed.
        jdbc.update("UPDATE media_assets SET updated_at = updated_at WHERE asset_id = ?", upload.assetId());
        assertThat(asset()).isEqualTo(ready);
        assertThat(outbox()).isEqualTo(outbox);
    }

    @Test
    void theDatabaseRefusesReadyWithoutTheEventOfItsVersionAndDuration() throws Exception {
        var lease = readyWithoutEncoding();
        String prefix = prefix(upload, 1);
        var template = new TransactionTemplate(transactions);
        Runnable ready = () -> jdbc.update("""
                UPDATE media_assets SET state = 'READY', output_prefix = ?, master_manifest_key = ?,
                    duration_seconds = 5, aggregate_version = aggregate_version + 1
                WHERE asset_id = ?
                """, prefix, prefix + "/master.m3u8", upload.assetId());
        // version 2 is the READY version; each event below is wrong in exactly one way.
        var events = List.of(
                "",
                "{\"state\":\"READY\",\"durationSeconds\":5}@1",
                "{\"state\":\"PROCESSING\"}@2",
                "{\"state\":\"READY\",\"durationSeconds\":6}@2",
                "{\"state\":\"READY\"}@2");
        for (var event : events) {
            assertThatThrownBy(() -> template.executeWithoutResult(status -> {
                ready.run();
                if (!event.isEmpty()) {
                    var parts = event.split("@");
                    jdbc.update("DELETE FROM media_outbox_events WHERE asset_id = ? AND aggregate_version = ?",
                            upload.assetId(), Long.parseLong(parts[1]));
                    jdbc.update("""
                            INSERT INTO media_outbox_events(event_id, asset_id, asset_version, aggregate_version,
                                correlation_id, payload, next_attempt_at, created_at)
                            VALUES (?, ?, 1, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """, UUID.randomUUID(), upload.assetId(), Long.parseLong(parts[1]), upload.requestId(),
                            parts[0]);
                }
            })).as(event).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("media READY requires its event");
            assertNotReady();
            assertThat(job().get("stage")).isEqualTo("FINALIZING");
        }

        // The production commit writes the transition and its event together and passes the same check.
        assertThat(selections.select(lease, selectionOf(1, 5))).isTrue();
        assertThat(asset().get("state")).isEqualTo("READY");
        assertThat(events()).containsExactly("PROCESSING", "READY");
    }

    private void await90s(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 90 s: %s", job()).isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
