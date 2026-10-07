package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.BoundedMediaWorker;
import com.libra.streaming.media.processing.application.JobStages;
import com.libra.streaming.media.processing.application.MediaJobHandler;
import com.libra.streaming.media.processing.application.MediaTranscoder;
import com.libra.streaming.media.processing.application.SourceProber;
import com.libra.streaming.media.processing.application.SourceReader;
import com.libra.streaming.media.processing.application.SourceTranscoder;
import com.libra.streaming.media.processing.application.TranscodeWorkspaces;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * The whole stage with nothing mocked: real SeaweedFS holds staging and the frozen copy, real PostgreSQL holds the
 * lease, stage and metadata, and the real ffprobe and ffmpeg judge and encode a generated fixture. The output stays
 * in a local workspace: nothing is uploaded, selected, ready or published.
 */
class SourceTranscodeIntegrationTest extends SourceStorageFixture {
    @DynamicPropertySource
    static void tools(DynamicPropertyRegistry registry) {
        registry.add("libra.media.probe.executable", () -> FfprobeLocator.locate().toString());
        registry.add("libra.media.probe.timeout", () -> "30s");
        registry.add("libra.media.transcode.executable", () -> MediaFixtures.ffmpeg().toString());
        registry.add("libra.media.transcode.timeout", () -> "5m");
        // The 20 s clip that keeps the encoder busy long enough to lose its lease mid-output is 3.9 MB.
        registry.add("libra.media.storage.max-object-bytes", () -> "8388608");
    }

    @Autowired SourceTranscoder transcoder;
    @Autowired SourceProber prober;
    @Autowired SourceReader reader;
    @Autowired MediaTranscoder encoder;
    @Autowired TranscodeWorkspaces workspaces;
    @Autowired JobStages stages;

    private MediaPersistenceService.Ensure upload;

    private JobLease stagedClaim(String name) throws Exception {
        byte[] content = MediaFixtures.bytes(name);
        upload = queued(content);
        stage(upload, content);
        return claim();
    }

    private String stageOf(JobLease lease) {
        return jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, lease.jobId());
    }

    private List<Path> workspaceDirectories() throws IOException {
        try (var tree = Files.walk(configured.scratchDirectory())) {
            return tree.filter(path -> Files.isDirectory(path) && path.getFileName().toString().startsWith("hls-attempt-"))
                    .toList();
        }
    }

    private int hlsObjects() {
        return client.listObjectsV2(request -> request.bucket("libra-hls")).contents().size();
    }

    @Test
    void anAcceptableSourceIsFrozenProbedAndEncodedIntoAWorkspaceUnderTheLease() throws Exception {
        var lease = stagedClaim("valid-1080p-aac.mp4");
        long parts = scratchFiles();

        var result = transcoder.transcode(lease, () -> false);

        assertThat(result).isInstanceOf(SourceTranscoder.Result.Transcoded.class);
        try (var done = (SourceTranscoder.Result.Transcoded) result) {
            var directory = done.output().directory();
            assertThat(workspaceDirectories()).containsExactly(directory.toRealPath());
            assertThat(directory.getFileName().toString()).startsWith("hls-attempt-1-");
            assertThat(done.output().width()).isEqualTo(1280);
            assertThat(done.output().height()).isEqualTo(720);
            assertThat(done.output().segments()).isNotEmpty();
            try (var files = Files.list(directory)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                        .contains("master.m3u8", "rendition.m3u8", "segment-00000.ts")
                        .allMatch(name -> name.matches("master\\.m3u8|rendition\\.m3u8|segment-\\d{5}\\.ts"));
            }
            // The encoder read the frozen copy; the job is visibly TRANSCODING but nothing is selected or ready.
            assertThat(selectedKey(upload)).isEqualTo(attemptKey(upload, 1));
            assertThat(stageOf(lease)).isEqualTo("TRANSCODING");
            assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                    upload.assetId())).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT output_prefix FROM media_assets WHERE asset_id = ?", String.class,
                    upload.assetId())).isNull();
            assertThat(jdbc.queryForObject("SELECT master_manifest_key FROM media_assets WHERE asset_id = ?",
                    String.class, upload.assetId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
            assertThat(hlsObjects()).isZero();
        }
        assertThat(workspaceDirectories()).isEmpty();
        assertThat(scratchFiles()).isEqualTo(parts);
    }

    @Test
    void aRejectedSourceNeverStartsTheEncoderOrCreatesAWorkspace() throws Exception {
        record Case(String fixture, ProcessingFailure expected) {}
        for (var rejected : List.of(new Case("invalid-hevc.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-4k.mp4", ProcessingFailure.UNSUPPORTED_MEDIA),
                new Case("invalid-lying-headers.mp4", ProcessingFailure.CORRUPT_INPUT),
                new Case("corrupt-truncated.mp4", ProcessingFailure.CORRUPT_INPUT),
                new Case("corrupt-garbage.bin", ProcessingFailure.CORRUPT_INPUT))) {
            var lease = stagedClaim(rejected.fixture());

            var result = transcoder.transcode(lease, () -> false);

            assertThat(result).as(rejected.fixture()).isInstanceOf(SourceTranscoder.Result.Rejected.class);
            var outcome = ((SourceTranscoder.Result.Rejected) result).outcome();
            assertThat(outcome.failure()).as(rejected.fixture()).isEqualTo(rejected.expected());
            assertThat(outcome.permanent()).as(rejected.fixture()).isTrue();
            assertThat(workspaceDirectories()).as(rejected.fixture()).isEmpty();
            assertThat(stageOf(lease)).as(rejected.fixture()).isIn("CLAIMED", "SOURCE_SELECTED");
            assertThat(leases.fail(lease, outcome.failure())).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        assertThat(hlsObjects()).isZero();
    }

    @Test
    void aRetryEncodesAgainFromTheFrozenCopyEvenWithStagingGone() throws Exception {
        var first = stagedClaim("valid-160x96-silent.mp4");
        try (var done = (SourceTranscoder.Result.Transcoded) transcoder.transcode(first, () -> false)) {
            assertThat(done.output().hasAudio()).isFalse();
        }
        assertThat(leases.release(first)).isTrue();
        client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(stagingKey(upload)).build());

        var second = claim();
        try (var done = (SourceTranscoder.Result.Transcoded) transcoder.transcode(second, () -> false)) {
            assertThat(second.attempt()).isEqualTo(2);
            assertThat(done.output().directory().getFileName().toString()).startsWith("hls-attempt-2-");
            assertThat(done.output().width()).isEqualTo(160);
            assertThat(stageOf(second)).isEqualTo("TRANSCODING");
        }
        assertThat(selectedKey(upload)).isEqualTo(attemptKey(upload, 1));
        assertThat(frozenObjects(upload)).containsExactly(attemptKey(upload, 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isOne();
        assertThat(workspaceDirectories()).isEmpty();
    }

    @Test
    void aLeaseLostBeforeTheStageGivesNothingAndLeavesNoWorkspace() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        expire(lease);

        assertThat(transcoder.transcode(lease, () -> false)).isInstanceOf(SourceTranscoder.Result.LeaseLost.class);

        assertThat(workspaceDirectories()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_source_metadata", Integer.class)).isZero();
    }

    @Test
    void aLeaseLostWhileEncodingNeverHandsOverItsOutput() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        MediaTranscoder expiring = (source, plan, workspace, cancelled) -> {
            encoder.transcode(source, plan, workspace, cancelled);
            expire(lease); // the encoder finished, but the claim ran out before the result could be used
        };
        var stale = new SourceTranscoder(prober, sources, reader, expiring, workspaces, stages, 300L * 1024 * 1024);

        assertThat(stale.transcode(lease, () -> false)).isInstanceOf(SourceTranscoder.Result.LeaseLost.class);

        assertThat(workspaceDirectories()).isEmpty();
        assertThat(hlsObjects()).isZero();
    }

    @Test
    void aLeaseTakenOverMidEncodeStopsTheRealEncoderThroughTheWorkerHeartbeat() throws Exception {
        byte[] content = MediaFixtures.bytes("valid-720p-20s-aac.mp4");
        upload = queued(content);
        stage(upload, content);
        var claimed = new java.util.concurrent.atomic.AtomicReference<JobLease>();
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var returned = new java.util.concurrent.atomic.AtomicReference<SourceTranscoder.Result>();
        var finished = new java.util.concurrent.CountDownLatch(1);
        var ended = new java.util.concurrent.atomic.AtomicLong();
        MediaJobHandler handler = (lease, cancelled) -> {
            claimed.set(lease);
            try {
                var result = transcoder.transcode(lease, cancelled);
                returned.set(result);
                if (result instanceof SourceTranscoder.Result.Transcoded done) { done.close(); }
                return MediaJobHandler.Outcome.retry(ProcessingFailure.PROCESSING_FAILED);
            } catch (InterruptedException | RuntimeException failure) {
                thrown.set(failure);
                throw failure;
            } finally {
                ended.set(System.nanoTime());
                finished.countDown();
            }
        };
        var settings = new BoundedMediaWorker.Settings(Duration.ofMillis(50), Duration.ofSeconds(3),
                Duration.ofMillis(200), Duration.ofSeconds(30), Duration.ofSeconds(10));
        JobLease successor;
        long stolen;
        var reports = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var worker = new BoundedMediaWorker(leases, handler, settings, reports::add)) {
            worker.start();
            // Let the worker freeze, probe and start encoding, and wait for the encoder's second segment to open.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (workspaceDirectories().stream().noneMatch(directory -> Files.exists(directory.resolve("segment-00001.ts")))) {
                assertThat(finished.getCount()).as("handler ended early: %s %s %s", returned.get(), thrown.get(), reports)
                        .isOne();
                assertThat(System.nanoTime()).as("encoder output appeared: %s", reports).isLessThan(deadline);
                Thread.sleep(20);
            }
            // Another instance takes the job over: the old claim runs out and a successor claims attempt 2.
            expire(claimed.get());
            successor = claim();
            stolen = System.nanoTime();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // The heartbeat's failed renewal cancelled the stage and the encoder was killed well before it could finish.
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(returned.get()).isNull();
        assertThat(Duration.ofNanos(ended.get() - stolen)).isLessThan(Duration.ofSeconds(3));
        assertThat(successor.attempt()).isEqualTo(2);
        assertThat(stageOf(successor)).isEqualTo("CLAIMED");
        // Nothing of the stale attempt survives: no workspace, no encoder, no output, no recorded outcome.
        assertThat(workspaceDirectories()).isEmpty();
        assertThat(ProcessHandle.current().descendants().filter(handle -> handle.info().command()
                .map(command -> Path.of(command).equals(MediaFixtures.ffmpeg())).orElse(false)).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM media_jobs WHERE id = ?", String.class,
                successor.jobId())).isNull();
        assertThat(hlsObjects()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isZero();
        // The successor still encodes from the frozen copy the first attempt committed.
        try (var done = (SourceTranscoder.Result.Transcoded) transcoder.transcode(successor, () -> false)) {
            assertThat(done.output().segments()).hasSize(5);
        }
        assertThat(selectedKey(upload)).isEqualTo(attemptKey(upload, 1));
    }

    @Test
    void aStageAdvanceIsFencedByTheCurrentLeaseAndTheSelectedSource() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        // A claim with no committed source cannot encode.
        assertThat(stages.beginTranscoding(lease)).isFalse();
        assertThat(stageOf(lease)).isEqualTo("CLAIMED");

        assertThat(prober.probe(lease, () -> false)).isInstanceOf(SourceProber.Result.Probed.class);
        var impostor = new JobLease(lease.jobId(), lease.uploadId(), lease.assetId(), lease.assetVersion(),
                lease.attempt(), UUID.randomUUID(), lease.expiresAt());
        assertThat(stages.beginTranscoding(impostor)).isFalse();
        assertThat(stageOf(lease)).isEqualTo("SOURCE_SELECTED");

        assertThat(stages.beginTranscoding(lease)).isTrue();
        assertThat(stages.beginTranscoding(lease)).isTrue();
        assertThat(stageOf(lease)).isEqualTo("TRANSCODING");

        expire(lease);
        assertThat(stages.beginTranscoding(lease)).isFalse();
    }

    @Test
    void aWorkerKilledWhileTranscodingIsReclaimedAndTheStaleOwnerCannotAdvance() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        assertThat(prober.probe(lease, () -> false)).isInstanceOf(SourceProber.Result.Probed.class);
        assertThat(stages.beginTranscoding(lease)).isTrue();
        expire(lease); // what a killed process leaves: an active stage whose lease simply runs out

        var successor = claim();

        assertThat(successor.attempt()).isEqualTo(2);
        assertThat(stageOf(successor)).isEqualTo("CLAIMED");
        assertThat(stages.beginTranscoding(lease)).isFalse();
        assertThat(transcoder.transcode(lease, () -> false)).isInstanceOf(SourceTranscoder.Result.LeaseLost.class);
        try (var done = (SourceTranscoder.Result.Transcoded) transcoder.transcode(successor, () -> false)) {
            assertThat(done.output().segments()).isNotEmpty();
        }
        assertThat(selectedKey(upload)).isEqualTo(attemptKey(upload, 1));
    }

    @Test
    void theRenditionPlanUsedMatchesTheRecordedMetadata() throws Exception {
        var lease = stagedClaim("valid-rotated-90.mp4");
        try (var done = (SourceTranscoder.Result.Transcoded) transcoder.transcode(lease, () -> false)) {
            assertThat(RenditionPlan.of(done.source())).extracting(RenditionPlan::width, RenditionPlan::height)
                    .containsExactly(done.output().width(), done.output().height());
            assertThat(done.output().width()).isEqualTo(720);
            assertThat(done.output().height()).isEqualTo(1280);
        }
    }

    @Test
    void cancellationReleasesTheWorkspaceAndScratch() throws Exception {
        var lease = stagedClaim("valid-160x96-silent.mp4");
        long parts = scratchFiles();
        BooleanSupplier cancelled = () -> true;

        assertThatThrownBy(() -> transcoder.transcode(lease, cancelled)).isInstanceOf(InterruptedException.class);

        assertThat(workspaceDirectories()).isEmpty();
        assertThat(scratchFiles()).isEqualTo(parts);
    }
}
