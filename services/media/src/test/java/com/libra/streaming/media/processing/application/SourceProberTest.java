package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.processing.domain.SourceMetadata;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

/** Stage orchestration with in-memory ports; the real tool, storage and PostgreSQL are separate suites. */
class SourceProberTest {
    private static final byte[] MP4 = "frozen-source-bytes".getBytes();
    private static final String KEY = "sources/x/attempt-1/source.mp4";
    @TempDir Path scratch;
    private Sources sources;
    private Reader reader;
    private Prober prober;
    private Metadata metadata;
    private FrozenStorage frozen;
    private SourceProber stage;
    private final JobLease lease = new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
            UUID.randomUUID(), Instant.now().plusSeconds(30));

    @BeforeEach
    void setUp() {
        sources = new Sources(new JobSources.Target("staging/x/source.mp4", MP4.length, sha(MP4), KEY));
        frozen = new FrozenStorage();
        reader = new Reader(scratch);
        reader.object = MP4;
        prober = new Prober();
        metadata = new Metadata();
        stage = new SourceProber(new SourceFreezer(sources, frozen), sources, reader, prober, metadata);
    }

    @Test
    void probesTheFrozenCopyAndRecordsOnlyValidatedMetadata() throws Exception {
        var result = stage.probe(lease, () -> false);
        assertThat(result).isInstanceOf(SourceProber.Result.Probed.class);
        assertThat(metadata.recorded).hasSize(1);
        assertThat(metadata.keys).containsExactly(KEY);
        assertThat(prober.calls).isEqualTo(1);
        assertThat(reader.opened).containsExactly(KEY);
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void aRecordedProbeIsReusedWithoutReadingOrProbingAgain() throws Exception {
        stage.probe(lease, () -> false);
        var again = stage.probe(lease, () -> false);
        assertThat(again).isEqualTo(new SourceProber.Result.Probed(metadata.recorded.getFirst()));
        assertThat(reader.opened).hasSize(1);
        assertThat(prober.calls).isEqualTo(1);
        assertThat(metadata.recorded).hasSize(1);
    }

    @Test
    void anUnacceptableSourceIsRejectedPermanentlyAndNothingIsRecorded() throws Exception {
        prober.report = new ProbeReport(new ProbeReport.Format("mov,mp4", "isom", BigDecimal.TEN), List.of());
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.UNSUPPORTED_MEDIA, true);
        prober.report = new ProbeReport(new ProbeReport.Format("mov,mp4", "isom", null), List.of());
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.CORRUPT_INPUT, true);
        assertThat(metadata.recorded).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void anUnreadableProbeIsAPermanentCorruptInputFailure() throws Exception {
        prober.failure = new MediaProber.Unreadable();
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.CORRUPT_INPUT, true);
        assertThat(metadata.recorded).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void anUnavailableToolOrStorageIsRetryableAndSaysNothingAboutTheInput() throws Exception {
        prober.failure = new MediaProber.Unavailable();
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        prober.failure = null;
        reader.failure = true;
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.PROCESSING_FAILED, false);
        assertThat(metadata.recorded).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void aFrozenObjectThatChangedAfterVerificationNeverReachesTheProbe() throws Exception {
        // The freezer proves the bytes first; this guards the second read, which could race a storage fault.
        sources.target = new JobSources.Target("staging/x/source.mp4", MP4.length, sha(MP4), KEY);
        reader.object = "frozen-source-bytez".getBytes();
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.CHECKSUM_MISMATCH, true);
        reader.object = "short".getBytes();
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.SIZE_MISMATCH, true);
        reader.object = null;
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.SOURCE_MISSING, true);
        assertThat(prober.calls).isZero();
        assertThat(metadata.recorded).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void aFreezingRejectionEndsTheStageBeforeAnyRead() throws Exception {
        frozen.missing = true;
        assertRejected(stage.probe(lease, () -> false), ProcessingFailure.SOURCE_MISSING, true);
        assertThat(reader.opened).isEmpty();
        assertThat(prober.calls).isZero();
    }

    @Test
    void aLostLeaseRecordsNothingAtAnyPoint() throws Exception {
        metadata.refuse = true;
        assertThat(stage.probe(lease, () -> false)).isInstanceOf(SourceProber.Result.LeaseLost.class);
        sources.target = null;
        assertThat(stage.probe(lease, () -> false)).isInstanceOf(SourceProber.Result.LeaseLost.class);
        assertThat(metadata.recorded).isEmpty();
        assertThat(prober.calls).isEqualTo(1);
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void cancellationStopsBeforeTheProbeAndLeavesNoScratchFile() {
        assertThatThrownBy(() -> stage.probe(lease, () -> true)).isInstanceOf(InterruptedException.class);
        assertThat(prober.calls).isZero();
        assertThat(metadata.recorded).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    private static void assertRejected(SourceProber.Result result, ProcessingFailure failure, boolean permanent) {
        assertThat(result).isInstanceOf(SourceProber.Result.Rejected.class);
        var outcome = ((SourceProber.Result.Rejected) result).outcome();
        assertThat(outcome.failure()).isEqualTo(failure);
        assertThat(outcome.permanent()).isEqualTo(permanent);
    }

    private long scratchFiles() {
        try (Stream<Path> files = Files.list(scratch)) { return files.count(); }
        catch (IOException exception) { throw new AssertionError(exception); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static ProbeReport acceptable() {
        return new ProbeReport(new ProbeReport.Format("mov,mp4,m4a,3gp,3g2,mj2", "isom", new BigDecimal("2.0")),
                List.of(new ProbeReport.Track("video", "h264", "High", 320, 180, "yuv420p", "1:1", "30/1", "bt709",
                        "bt709", null, false, null, null)));
    }

    private static final class Sources implements JobSources {
        Target target;
        Sources(Target target) { this.target = target; }
        @Override public Optional<Target> target(JobLease lease) { return Optional.ofNullable(target); }
        @Override public boolean select(JobLease lease, String sourceKey) { return true; }
    }

    /** Reuse path of the real freezer: the selected object is only digested, never fetched from staging. */
    private static final class FrozenStorage implements SourceStorage {
        boolean missing;

        @Override
        public Optional<StagedCopy> stage(String key, long bytes, BooleanSupplier cancelled) {
            throw new AssertionError("A selected source is never re-read from staging");
        }

        @Override public String frozenKey(JobLease lease) { return KEY; }
        @Override public void store(String key, StagedCopy copy) { throw new AssertionError(); }

        @Override
        public Optional<Digest> digest(String key, long bytes, BooleanSupplier cancelled) {
            return missing ? Optional.empty() : Optional.of(new Digest(MP4.length, sha(MP4)));
        }
    }

    private static final class Reader implements SourceReader {
        final Path scratch;
        byte[] object;
        boolean failure;
        final List<String> opened = new ArrayList<>();

        Reader(Path scratch) { this.scratch = scratch; }

        @Override
        public Optional<SourceStorage.StagedCopy> open(String key, long expectedBytes, BooleanSupplier cancelled) {
            opened.add(key);
            if (failure) { throw new SourceStorage.Unavailable(); }
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
            assertThat(file).exists();
            if (failure != null) { throw failure; }
            return report;
        }
    }

    private static final class Metadata implements SourceMetadataStore {
        final List<SourceMetadata> recorded = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        boolean refuse;

        @Override public Optional<SourceMetadata> find(JobLease lease) { return recorded.stream().findFirst(); }

        @Override
        public boolean record(JobLease lease, String sourceKey, SourceMetadata metadata) {
            if (refuse) { return false; }
            recorded.add(metadata);
            keys.add(sourceKey);
            return true;
        }
    }
}
