package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Port-level behavior with in-memory fakes; real storage and PostgreSQL are covered by integration tests. */
class SourceFreezerTest {
    private static final byte[] MP4 = "frozen-source-bytes".getBytes();
    @TempDir Path scratch;
    private Storage storage;
    private Sources sources;
    private SourceFreezer freezer;
    private final JobLease lease = new JobLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
            UUID.randomUUID(), Instant.now().plusSeconds(30));

    @BeforeEach
    void setUp() {
        storage = new Storage(scratch);
        sources = new Sources(new JobSources.Target("staging/x/source.mp4", MP4.length, sha(MP4), null));
        freezer = new SourceFreezer(sources, storage);
    }

    @Test
    void freezesVerifiedBytesIntoAnAttemptSpecificKeyAndSelectsItOnce() throws Exception {
        storage.staging = MP4;
        var result = freezer.freeze(lease, () -> false);
        assertThat(result).isEqualTo(new SourceFreezer.Result.Frozen("sources/x/attempt-1/source.mp4"));
        assertThat(storage.frozen).containsOnlyKeys("sources/x/attempt-1/source.mp4");
        assertThat(sources.selections).containsExactly("sources/x/attempt-1/source.mp4");
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void missingStagingIsPermanentAndNeitherStoresNorSelects() throws Exception {
        assertRejected(freezer.freeze(lease, () -> false), ProcessingFailure.SOURCE_MISSING, true);
        assertThat(storage.frozen).isEmpty();
        assertThat(sources.selections).isEmpty();
    }

    @Test
    void undersizedAndOversizedSourcesAreRejectedBeforeAnythingIsStored() throws Exception {
        for (byte[] actual : new byte[][] {"short".getBytes(), (new String(MP4) + "+extra-bytes").getBytes()}) {
            storage.staging = actual;
            assertRejected(freezer.freeze(lease, () -> false), ProcessingFailure.SIZE_MISMATCH, true);
        }
        assertThat(storage.frozen).isEmpty();
        assertThat(sources.selections).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void sameLengthWithDifferentBytesFailsTheChecksum() throws Exception {
        storage.staging = "otherz-source-bytes".getBytes();
        assertThat(storage.staging).hasSize(MP4.length);
        assertRejected(freezer.freeze(lease, () -> false), ProcessingFailure.CHECKSUM_MISMATCH, true);
        assertThat(storage.frozen).isEmpty();
        assertThat(sources.selections).isEmpty();
    }

    @Test
    void storageFaultIsRetryableNotAnInputRejection() throws Exception {
        storage.staging = MP4;
        storage.failure = true;
        var result = (SourceFreezer.Result.Rejected) freezer.freeze(lease, () -> false);
        assertThat(result.outcome().permanent()).isFalse();
        assertThat(result.outcome().failure()).isEqualTo(ProcessingFailure.PROCESSING_FAILED);
        assertThat(sources.selections).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void storedBytesThatReadBackDifferentlyAreAFaultAndAreNeverSelected() throws Exception {
        storage.staging = MP4;
        storage.corruptReadback = true;
        var result = (SourceFreezer.Result.Rejected) freezer.freeze(lease, () -> false);
        assertThat(result.outcome().permanent()).isFalse();
        assertThat(sources.selections).isEmpty();
    }

    @Test
    void staleLeaseDoesNotEvenReadStaging() throws Exception {
        sources.target = null;
        storage.staging = MP4;
        assertThat(freezer.freeze(lease, () -> false)).isInstanceOf(SourceFreezer.Result.LeaseLost.class);
        assertThat(storage.stageCalls).isZero();
    }

    @Test
    void leaseLostAtCommitLeavesAnUnselectedObjectAndNoScratchFile() throws Exception {
        storage.staging = MP4;
        sources.refuseSelection = true;
        assertThat(freezer.freeze(lease, () -> false)).isInstanceOf(SourceFreezer.Result.LeaseLost.class);
        assertThat(storage.frozen).hasSize(1);
        assertThat(sources.selections).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void cancellationBeforeTheStoreWritesNothingAndCleansScratch() {
        storage.staging = MP4;
        assertThatThrownBy(() -> freezer.freeze(lease, () -> storage.stageCalls > 0))
                .isInstanceOf(InterruptedException.class);
        assertThat(storage.frozen).isEmpty();
        assertThat(scratchFiles()).isZero();
    }

    @Test
    void committedSelectionIsReusedWithoutReadingStagingAgain() throws Exception {
        storage.frozen.put("sources/x/attempt-1/source.mp4", MP4);
        sources.target = new JobSources.Target("staging/x/source.mp4", MP4.length, sha(MP4),
                "sources/x/attempt-1/source.mp4");
        storage.staging = "changed-after-select".getBytes(); // must not matter
        var retry = new JobLease(lease.jobId(), lease.uploadId(), lease.assetId(), 1, 2, UUID.randomUUID(),
                Instant.now().plusSeconds(30));
        assertThat(freezer.freeze(retry, () -> false))
                .isEqualTo(new SourceFreezer.Result.Frozen("sources/x/attempt-1/source.mp4"));
        assertThat(storage.stageCalls).isZero();
        assertThat(storage.frozen).hasSize(1);
    }

    @Test
    void lostOrChangedFrozenObjectIsNeverRepairedFromChangedStaging() throws Exception {
        sources.target = new JobSources.Target("staging/x/source.mp4", MP4.length, sha(MP4),
                "sources/x/attempt-1/source.mp4");
        storage.staging = MP4;
        assertRejected(freezer.freeze(lease, () -> false), ProcessingFailure.SOURCE_MISSING, true);
        storage.frozen.put("sources/x/attempt-1/source.mp4", "otherz-source-bytes".getBytes());
        assertRejected(freezer.freeze(lease, () -> false), ProcessingFailure.CHECKSUM_MISMATCH, true);
        assertThat(storage.stageCalls).isZero();
        assertThat(sources.selections).isEmpty();
    }

    private static void assertRejected(SourceFreezer.Result result, ProcessingFailure failure, boolean permanent) {
        assertThat(result).isInstanceOf(SourceFreezer.Result.Rejected.class);
        var outcome = ((SourceFreezer.Result.Rejected) result).outcome();
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

    private static final class Sources implements JobSources {
        Target target;
        boolean refuseSelection;
        final java.util.List<String> selections = new java.util.ArrayList<>();

        Sources(Target target) { this.target = target; }

        @Override public Optional<Target> target(JobLease lease) { return Optional.ofNullable(target); }

        @Override
        public boolean select(JobLease lease, String sourceKey) {
            if (refuseSelection) { return false; }
            selections.add(sourceKey);
            return true;
        }
    }

    private static final class Storage implements SourceStorage {
        final Path scratch;
        byte[] staging;
        boolean failure;
        boolean corruptReadback;
        int stageCalls;
        final Map<String, byte[]> frozen = new HashMap<>();

        Storage(Path scratch) { this.scratch = scratch; }

        @Override
        public Optional<StagedCopy> stage(String stagingKey, long declaredBytes, BooleanSupplier cancelled)
                throws InterruptedException {
            stageCalls++;
            if (failure) { throw new Unavailable(); }
            if (staging == null) { return Optional.empty(); }
            // Like the real adapter: never read beyond declared + 1 bytes.
            byte[] read = java.util.Arrays.copyOf(staging, (int) Math.min(staging.length, declaredBytes + 1));
            try {
                Path file = Files.createTempFile(scratch, "media-", ".part");
                Files.write(file, read);
                return Optional.of(new StagedCopy(file, read.length, sha(read)));
            } catch (IOException exception) { throw new Unavailable(); }
        }

        @Override public String frozenKey(JobLease lease) { return "sources/x/attempt-" + lease.attempt() + "/source.mp4"; }

        @Override
        public void store(String frozenKey, StagedCopy copy) {
            try { frozen.put(frozenKey, Files.readAllBytes(copy.file())); }
            catch (IOException exception) { throw new Unavailable(); }
        }

        @Override
        public Optional<Digest> digest(String frozenKey, long expectedBytes, BooleanSupplier cancelled) {
            byte[] stored = frozen.get(frozenKey);
            if (stored == null) { return Optional.empty(); }
            return Optional.of(corruptReadback ? new Digest(stored.length, "0".repeat(64))
                    : new Digest(stored.length, sha(stored)));
        }
    }
}
