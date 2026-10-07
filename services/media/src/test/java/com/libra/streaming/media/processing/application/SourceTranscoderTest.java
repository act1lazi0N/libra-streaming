package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.RenditionPlan;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Stage orchestration with in-memory ports; the real encoder, storage and PostgreSQL are separate suites. */
class SourceTranscoderTest {
    private static final byte[] MP4 = "frozen-source-bytes".getBytes();
    private static final String KEY = "sources/x/attempt-1/source.mp4";
    @TempDir Path scratch;
    private Sources sources;
    private Reader reader;
    private Prober prober;
    private Metadata metadata;
    private Transcoder transcoder;
    private Workspaces workspaces;
    private Stages stages;
    private SourceTranscoder stage;
    private final JobLease lease = new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
            UUID.randomUUID(), Instant.now().plusSeconds(30));

    @BeforeEach
    void setUp() {
        sources = new Sources(new JobSources.Target("staging/x/source.mp4", MP4.length, sha(MP4), KEY));
        reader = new Reader(scratch);
        prober = new Prober();
        metadata = new Metadata();
        transcoder = new Transcoder();
        workspaces = new Workspaces(scratch);
        stages = new Stages();
        var probe = new SourceProber(new SourceFreezer(sources, new FrozenStorage()), sources, reader, prober, metadata);
        stage = new SourceTranscoder(probe, sources, reader, transcoder, workspaces, stages, 300L * 1024 * 1024);
    }

    @Test
    void encodesTheFrozenCopyIntoAnAttemptWorkspaceAndHandsOverAVerifiedInventory() throws Exception {
        var result = stage.transcode(lease, () -> false);

        assertThat(result).isInstanceOf(SourceTranscoder.Result.Transcoded.class);
        try (var done = (SourceTranscoder.Result.Transcoded) result) {
            assertThat(done.output().segments()).hasSize(2);
            assertThat(done.output().width()).isEqualTo(1280);
            assertThat(done.output().height()).isEqualTo(720);
            assertThat(done.source()).isEqualTo(metadata.recorded.getFirst());
            assertThat(done.output().directory()).isEqualTo(done.workspace().directory());
            assertThat(done.output().directory().resolve("master.m3u8")).exists();
            // The encoder got the plan derived from the validated metadata and the frozen bytes, not staging.
            assertThat(transcoder.plans).containsExactly(new RenditionPlan(1280, 720, 30, 1, 0));
            assertThat(transcoder.sourceBytes).containsExactly(MP4);
            assertThat(reader.opened).containsExactly(KEY, KEY);
            assertThat(stages.began).isEqualTo(1);
            // The caller owns the workspace until it closes the result.
            assertThat(workspaces.open).hasSize(1);
        }
        assertThat(workspaces.open).isEmpty();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void theStageNeverAdvancesWhenTheProbeRejectsTheSource() throws Exception {
        prober.report = new ProbeReport(new ProbeReport.Format("mov,mp4", "isom", BigDecimal.TEN), List.of());
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.UNSUPPORTED_MEDIA, true);
        prober.failure = new MediaProber.Unavailable();
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        assertThat(transcoder.calls).isZero();
        assertThat(workspaces.opened).isZero();
        assertThat(stages.began).isZero();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void aSourceThatChangedBetweenTheProbeAndTheEncodeNeverReachesTheEncoder() throws Exception {
        reader.queue(MP4, "frozen-source-bytez".getBytes());
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.CHECKSUM_MISMATCH, true);
        // The probe's read is recorded now, so only the encode's own read is left to disturb.
        reader.queue("short".getBytes());
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.SIZE_MISMATCH, true);
        reader.queue(new byte[][] {null});
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.SOURCE_MISSING, true);
        assertThat(transcoder.calls).isZero();
        assertThat(workspaces.opened).isZero();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void anEncoderFaultIsClassifiedWithoutBlamingTheInputUnlessItCouldNotDecodeIt() throws Exception {
        transcoder.failure = new MediaTranscoder.Unavailable();
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        transcoder.failure = new MediaTranscoder.Undecodable();
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.CORRUPT_INPUT, true);
        transcoder.failure = new MediaTranscoder.OutputTooLarge();
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.UNSUPPORTED_MEDIA, true);
        assertThat(transcoder.calls).isEqualTo(3);
        assertThat(workspaces.open).isEmpty();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void anEncoderThatExitedCleanlyButLeftAnIncompleteRenditionIsRetried() throws Exception {
        transcoder.writeNothing = true;
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        transcoder.writeNothing = false;
        transcoder.stray = true;
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        assertThat(workspaces.open).isEmpty();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void anUnavailableScratchDiskIsRetryableAndNothingIsEncoded() throws Exception {
        workspaces.failure = true;
        assertRejected(stage.transcode(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        assertThat(transcoder.calls).isZero();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void aLeaseLostBeforeOrDuringTheEncodeReturnsNoOutput() throws Exception {
        stages.refuse = true;
        assertThat(stage.transcode(lease, () -> false)).isInstanceOf(SourceTranscoder.Result.LeaseLost.class);
        assertThat(transcoder.calls).isZero();
        stages.refuse = false;
        transcoder.afterEncode = () -> sources.target = null;
        assertThat(stage.transcode(lease, () -> false)).isInstanceOf(SourceTranscoder.Result.LeaseLost.class);
        assertThat(transcoder.calls).isEqualTo(1);
        assertThat(workspaces.open).isEmpty();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void cancellationBeforeOrDuringTheEncodeLeavesNoWorkspaceOrScratchFile() throws Exception {
        assertThatThrownBy(() -> stage.transcode(lease, () -> true)).isInstanceOf(InterruptedException.class);
        assertThat(transcoder.calls).isZero();
        transcoder.failure = new InterruptedException();
        assertThatThrownBy(() -> stage.transcode(lease, () -> false)).isInstanceOf(InterruptedException.class);
        assertThat(workspaces.open).isEmpty();
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void aRetryAfterACommittedProbeEncodesAgainFromTheFrozenCopyAlone() throws Exception {
        try (var first = (SourceTranscoder.Result.Transcoded) stage.transcode(lease, () -> false)) {
            assertThat(first.output().segments()).hasSize(2);
        }
        try (var second = (SourceTranscoder.Result.Transcoded) stage.transcode(lease, () -> false)) {
            assertThat(second.output().segments()).hasSize(2);
        }
        assertThat(prober.calls).isEqualTo(1);
        assertThat(workspaces.opened).isEqualTo(2);
        assertThat(scratchEntries()).isZero();
    }

    @Test
    void constructionRejectsAnEmptyBudget() {
        var probe = new SourceProber(new SourceFreezer(sources, new FrozenStorage()), sources, reader, prober, metadata);
        assertThatThrownBy(() -> new SourceTranscoder(probe, sources, reader, transcoder, workspaces, stages, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRejected(SourceTranscoder.Result result, ProcessingFailure failure, boolean permanent) {
        assertThat(result).isInstanceOf(SourceTranscoder.Result.Rejected.class);
        var outcome = ((SourceTranscoder.Result.Rejected) result).outcome();
        assertThat(outcome.failure()).isEqualTo(failure);
        assertThat(outcome.permanent()).isEqualTo(permanent);
    }

    private long scratchEntries() {
        try (Stream<Path> files = Files.list(scratch)) { return files.count(); }
        catch (IOException exception) { throw new AssertionError(exception); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static ProbeReport acceptable() {
        return new ProbeReport(new ProbeReport.Format("mov,mp4,m4a,3gp,3g2,mj2", "isom", new BigDecimal("8.0")),
                List.of(new ProbeReport.Track("video", "h264", "High", 1920, 1080, "yuv420p", "1:1", "30/1", "bt709",
                        "bt709", null, false, null, null)));
    }

    private static final class Sources implements JobSources {
        Target target;
        Sources(Target target) { this.target = target; }
        @Override public Optional<Target> target(JobLease lease) { return Optional.ofNullable(target); }
        @Override public boolean select(JobLease lease, String sourceKey) { return true; }
    }

    private static final class FrozenStorage implements SourceStorage {
        @Override
        public Optional<StagedCopy> stage(String key, long bytes, BooleanSupplier cancelled) {
            throw new AssertionError("A selected source is never re-read from staging");
        }
        @Override public String frozenKey(JobLease lease) { return KEY; }
        @Override public void store(String key, StagedCopy copy) { throw new AssertionError(); }
        @Override
        public Optional<Digest> digest(String key, long bytes, BooleanSupplier cancelled) {
            return Optional.of(new Digest(MP4.length, sha(MP4)));
        }
    }

    /** Serves {@code MP4} unless a different object was queued for the next opens, in order. */
    private static final class Reader implements SourceReader {
        final Path scratch;
        final List<byte[]> queued = new ArrayList<>();
        final List<String> opened = new ArrayList<>();

        Reader(Path scratch) { this.scratch = scratch; }

        void queue(byte[]... objects) {
            queued.clear();
            queued.addAll(java.util.Arrays.asList(objects));
        }

        @Override
        public Optional<SourceStorage.StagedCopy> open(String key, long expectedBytes, BooleanSupplier cancelled) {
            opened.add(key);
            byte[] object = queued.isEmpty() ? MP4 : queued.removeFirst();
            if (object == null) { return Optional.empty(); }
            try {
                Path file = Files.createTempFile(scratch, "media-", ".part");
                Files.write(file, object);
                return Optional.of(new SourceStorage.StagedCopy(file, object.length, sha(object)));
            } catch (IOException exception) { throw new SourceStorage.Unavailable(); }
        }
    }

    private static final class Prober implements MediaProber {
        ProbeReport report = acceptable();
        RuntimeException failure;
        int calls;

        @Override
        public ProbeReport probe(Path file, BooleanSupplier cancelled) {
            calls++;
            if (failure != null) { throw failure; }
            return report;
        }
    }

    private static final class Metadata implements SourceMetadataStore {
        final List<SourceMetadata> recorded = new ArrayList<>();
        @Override public Optional<SourceMetadata> find(JobLease lease) { return recorded.stream().findFirst(); }
        @Override
        public boolean record(JobLease lease, String sourceKey, SourceMetadata metadata) {
            recorded.add(metadata);
            return true;
        }
    }

    private static final class Stages implements JobStages {
        boolean refuse;
        int began;
        @Override
        public boolean beginTranscoding(JobLease lease) {
            if (refuse) { return false; }
            began++;
            return true;
        }
    }

    private static final class Workspaces implements TranscodeWorkspaces {
        final Path scratch;
        final List<Path> open = new ArrayList<>();
        int opened;
        boolean failure;

        Workspaces(Path scratch) { this.scratch = scratch; }

        @Override
        public Workspace open(JobLease lease, long budgetBytes) {
            if (failure) { throw new Unavailable(); }
            assertThat(budgetBytes).isPositive();
            try {
                var directory = Files.createTempDirectory(scratch, "hls-attempt-");
                open.add(directory);
                opened++;
                return new Workspace() {
                    @Override public Path directory() { return directory; }
                    @Override
                    public void close() {
                        try (Stream<Path> tree = Files.walk(directory)) {
                            for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) { Files.delete(entry); }
                        } catch (IOException exception) { throw new AssertionError(exception); }
                        open.remove(directory);
                    }
                };
            } catch (IOException exception) { throw new Unavailable(); }
        }
    }

    /** Writes a small valid rendition the way the real encoder would, or a defective one on request. */
    private static final class Transcoder implements MediaTranscoder {
        final List<RenditionPlan> plans = new ArrayList<>();
        final List<byte[]> sourceBytes = new ArrayList<>();
        Exception failure;
        boolean writeNothing;
        boolean stray;
        Runnable afterEncode = () -> { };
        int calls;

        @Override
        public void transcode(Path source, RenditionPlan plan, Path workspace, BooleanSupplier cancelled)
                throws InterruptedException {
            calls++;
            plans.add(plan);
            try {
                sourceBytes.add(Files.readAllBytes(source));
                if (failure instanceof InterruptedException interrupted) { throw interrupted; }
                if (failure instanceof RuntimeException runtime) { throw runtime; }
                if (writeNothing) { return; }
                Files.writeString(workspace.resolve("rendition.m3u8"), "#EXTM3U\n#EXT-X-VERSION:6\n"
                        + "#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                        + "#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:4.000000,\nsegment-00000.ts\n"
                        + "#EXTINF:3.500000,\nsegment-00001.ts\n#EXT-X-ENDLIST\n");
                Files.write(workspace.resolve("segment-00000.ts"), new byte[2000]);
                Files.write(workspace.resolve("segment-00001.ts"), new byte[1500]);
                if (stray) { Files.writeString(workspace.resolve("stray.txt"), "x"); }
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            afterEncode.run();
        }
    }
}
