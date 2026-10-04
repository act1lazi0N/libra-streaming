package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.JobSources;
import com.libra.streaming.media.processing.application.SourceFreezer;
import com.libra.streaming.media.processing.application.SourceStorage;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.*;

/**
 * M18 matrix on real PostgreSQL and SeaweedFS. A crash is simulated in-process by abandoning the attempt at a
 * checkpoint (nothing is released or recorded) and letting its lease run out, which is all a killed worker
 * leaves behind; scratch files left by a real kill are covered by {@code ScratchSpaceTest}.
 */
class SourceSnapshotRecoveryIntegrationTest extends SourceStorageFixture {
    enum Checkpoint { AFTER_DOWNLOAD, AFTER_STORE, AFTER_READBACK, AFTER_COMMIT }

    /** Stands in for process death: an Error, so no catch block in the stage can treat it as an outcome. */
    static final class Crash extends Error {
        Crash() { super("simulated crash", null, false, false); }
    }

    @ParameterizedTest
    @EnumSource(Checkpoint.class)
    void aCrashAtAnyCheckpointConvergesToOneVerifiedSelection(Checkpoint checkpoint) throws Exception {
        byte[] content = bytes(4096, 11);
        var upload = queued(content);
        stage(upload, content);
        var crashed = claim();
        long scratch = scratchFiles();

        assertThatThrownBy(() -> crashing(checkpoint).freeze(crashed, () -> false)).isInstanceOf(Crash.class);
        expire(crashed);

        boolean committed = checkpoint == Checkpoint.AFTER_COMMIT;
        boolean stored = checkpoint != Checkpoint.AFTER_DOWNLOAD;
        assertThat(selectedKey(upload)).isEqualTo(committed ? attemptKey(upload, 1) : null);
        assertThat(frozenObjects(upload)).containsExactlyElementsOf(stored ? java.util.List.of(attemptKey(upload, 1))
                : java.util.List.of());
        // After a commit, staging is irrelevant: remove it to prove the successor never reads it.
        if (committed) { deleteStaging(upload); }

        var successor = claim();
        assertThat(successor.attempt()).isEqualTo(2);
        var result = freezer.freeze(successor, () -> false);

        String selected = committed ? attemptKey(upload, 1) : attemptKey(upload, 2);
        assertThat(result).isEqualTo(new SourceFreezer.Result.Frozen(selected));
        assertThat(selectedKey(upload)).isEqualTo(selected);
        assertThat(sha(frozen(selected))).isEqualTo(sha(content));
        assertThat(sources.select(crashed, attemptKey(upload, 1))).isFalse();
        if (stored && !committed) {
            // The orphan is complete and correct, but nothing refers to it; cleanup is a later milestone.
            assertThat(frozenObjects(upload)).containsExactlyInAnyOrder(attemptKey(upload, 1), selected);
            assertThat(sha(frozen(attemptKey(upload, 1)))).isEqualTo(sha(content));
        } else {
            assertThat(frozenObjects(upload)).containsExactly(selected);
        }
        assertThat(jdbc.queryForObject("SELECT stage FROM media_jobs WHERE id = ?", String.class, successor.jobId()))
                .isEqualTo("SOURCE_SELECTED");
        assertThat(scratchFiles()).isEqualTo(scratch);
    }

    @Test
    void anUncommittedCrashReentersFreezingAndStillRejectsStagingThatChangedMeanwhile() throws Exception {
        byte[] content = bytes(4096, 12);
        var upload = queued(content);
        stage(upload, content);
        var crashed = claim();
        assertThatThrownBy(() -> crashing(Checkpoint.AFTER_STORE).freeze(crashed, () -> false))
                .isInstanceOf(Crash.class);
        expire(crashed);
        stage(upload, bytes(4096, 13)); // same length, different bytes

        var result = freezer.freeze(claim(), () -> false);

        assertThat(result).isInstanceOf(SourceFreezer.Result.Rejected.class);
        var outcome = ((SourceFreezer.Result.Rejected) result).outcome();
        assertThat(outcome.failure()).isEqualTo(ProcessingFailure.CHECKSUM_MISMATCH);
        assertThat(outcome.permanent()).isTrue();
        // The verified orphan is never adopted as a shortcut; selection stays empty.
        assertThat(selectedKey(upload)).isNull();
        assertThat(frozenObjects(upload)).containsExactly(attemptKey(upload, 1));
    }

    @Test
    void anOldAttemptStillUploadingWhenItsJobIsReclaimedCannotReplaceTheSuccessorsSource() throws Exception {
        byte[] content = bytes(4096, 14);
        var upload = queued(content);
        stage(upload, content);
        var old = claim();
        var uploading = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var slowStore = new Delegating() {
            @Override public void store(String key, StagedCopy copy) {
                uploading.countDown();
                try { if (!resume.await(20, TimeUnit.SECONDS)) { throw new AssertionError("not resumed"); } }
                catch (InterruptedException exception) { throw new AssertionError(exception); }
                sourceStorage.store(key, copy);
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var late = pool.submit(() -> new SourceFreezer(sources, slowStore).freeze(old, () -> false));
            assertThat(uploading.await(10, TimeUnit.SECONDS)).isTrue();

            expire(old);
            var successor = claim();
            var frozen = (SourceFreezer.Result.Frozen) freezer.freeze(successor, () -> false);
            assertThat(frozen.key()).isEqualTo(attemptKey(upload, 2));

            resume.countDown();
            assertThat(late.get(20, TimeUnit.SECONDS)).isInstanceOf(SourceFreezer.Result.LeaseLost.class);
            assertThat(leases.renew(old, Duration.ofSeconds(30))).isFalse();
            assertThat(leases.renew(successor, Duration.ofSeconds(30))).isTrue();
        }
        assertThat(selectedKey(upload)).isEqualTo(attemptKey(upload, 2));
        assertThat(frozenObjects(upload)).containsExactlyInAnyOrder(attemptKey(upload, 1), attemptKey(upload, 2));
        assertThat(sha(frozen(attemptKey(upload, 2)))).isEqualTo(sha(content));
    }

    @Test
    void stagingOverwrittenDuringTheDownloadNeverYieldsASnapshotOfDifferentBytes() throws Exception {
        byte[] content = bytes(900_000, 15);
        byte[] replacement = bytes(900_000, 16);
        var upload = queued(content);
        stage(upload, content);
        var polls = new AtomicInteger();
        BooleanSupplier overwriteMidStream = () -> {
            if (polls.incrementAndGet() == 3) { stage(upload, replacement); }
            return false;
        };

        var result = freezer.freeze(claim(), overwriteMidStream);

        assertThat(polls.get()).isGreaterThanOrEqualTo(3);
        // Whether storage served the old body, a torn body or failed the read is the store's business. The
        // invariant is ours: either the snapshot is exactly the declared bytes, or nothing is selected.
        if (result instanceof SourceFreezer.Result.Frozen frozen) {
            assertThat(sha(frozen(frozen.key()))).isEqualTo(sha(content));
            assertThat(selectedKey(upload)).isEqualTo(frozen.key());
        } else {
            assertThat(result).isInstanceOf(SourceFreezer.Result.Rejected.class);
            assertThat(selectedKey(upload)).isNull();
            assertThat(frozenObjects(upload)).isEmpty();
        }
        System.out.println("M18 mid-download overwrite outcome: " + result);
    }

    @Test
    void stagingOverwrittenAfterTheDownloadButBeforeTheStoreCannotLeakIntoTheSnapshot() throws Exception {
        byte[] content = bytes(4096, 17);
        var upload = queued(content);
        stage(upload, content);
        var overwriting = new Delegating() {
            @Override public Optional<StagedCopy> stage(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException {
                var copy = sourceStorage.stage(key, bytes, cancelled);
                SourceSnapshotRecoveryIntegrationTest.this.stage(upload, bytes(4096, 18));
                return copy;
            }
        };

        var frozen = (SourceFreezer.Result.Frozen) new SourceFreezer(sources, overwriting).freeze(claim(), () -> false);

        assertThat(sha(frozen(frozen.key()))).isEqualTo(sha(content));
    }

    @Test
    void everyRetryReadsTheSameCommittedBytesWhateverHappensToStaging() throws Exception {
        byte[] content = bytes(4096, 19);
        var upload = queued(content);
        stage(upload, content);
        var first = claim();
        var frozen = (SourceFreezer.Result.Frozen) freezer.freeze(first, () -> false);
        var lease = first;
        for (int attempt = 2; attempt <= 3; attempt++) {
            if (attempt == 2) { stage(upload, bytes(4096, 20)); } else { deleteStaging(upload); }
            assertThat(leases.release(lease)).isTrue();
            lease = claim();
            assertThat(lease.attempt()).isEqualTo(attempt);

            assertThat(freezer.freeze(lease, () -> false)).isEqualTo(frozen);
            var digest = sourceStorage.digest(frozen.key(), content.length, () -> false).orElseThrow();
            assertThat(digest.sha256()).isEqualTo(sha(content));
            assertThat(selectedKey(upload)).isEqualTo(frozen.key());
        }
        assertThat(frozenObjects(upload)).containsExactly(frozen.key());
    }

    @Test
    void aLostOrAlteredCommittedSnapshotFailsVisiblyInsteadOfBeingRebuiltFromStaging() throws Exception {
        for (boolean lost : new boolean[] {true, false}) {
            byte[] content = bytes(4096, lost ? 21 : 22);
            var upload = queued(content);
            stage(upload, content);
            var first = claim();
            var frozen = (SourceFreezer.Result.Frozen) freezer.freeze(first, () -> false);
            if (lost) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(frozen.key()).build());
            } else {
                client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(frozen.key()).contentType("video/mp4")
                        .build(), RequestBody.fromBytes(bytes(4096, 23)));
            }
            assertThat(leases.release(first)).isTrue();

            var result = freezer.freeze(claim(), () -> false);

            var outcome = ((SourceFreezer.Result.Rejected) result).outcome();
            assertThat(outcome.failure()).isEqualTo(lost ? ProcessingFailure.SOURCE_MISSING
                    : ProcessingFailure.CHECKSUM_MISMATCH);
            assertThat(outcome.permanent()).isTrue();
            // Staging still holds the right bytes, yet it is not consulted and the selection is not moved.
            assertThat(selectedKey(upload)).isEqualTo(frozen.key());
            assertThat(frozenObjects(upload)).containsExactlyElementsOf(lost ? java.util.List.of()
                    : java.util.List.of(frozen.key()));
        }
    }

    private SourceFreezer crashing(Checkpoint checkpoint) {
        var storage = new Delegating() {
            @Override public Optional<StagedCopy> stage(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException {
                var copy = sourceStorage.stage(key, bytes, cancelled);
                if (checkpoint == Checkpoint.AFTER_DOWNLOAD) { copy.ifPresent(StagedCopy::close); throw new Crash(); }
                return copy;
            }
            @Override public void store(String key, StagedCopy copy) {
                sourceStorage.store(key, copy);
                if (checkpoint == Checkpoint.AFTER_STORE) { throw new Crash(); }
            }
            @Override public Optional<Digest> digest(String key, long bytes, BooleanSupplier cancelled)
                    throws InterruptedException {
                var digest = sourceStorage.digest(key, bytes, cancelled);
                if (checkpoint == Checkpoint.AFTER_READBACK) { throw new Crash(); }
                return digest;
            }
        };
        JobSources committing = new JobSources() {
            @Override public Optional<Target> target(JobLease lease) { return sources.target(lease); }
            @Override public boolean select(JobLease lease, String key) {
                boolean selected = sources.select(lease, key);
                if (checkpoint == Checkpoint.AFTER_COMMIT) { throw new Crash(); }
                return selected;
            }
        };
        return new SourceFreezer(committing, storage);
    }

    /** Forwards to the real adapter; each case overrides only the step it disturbs. */
    private class Delegating implements SourceStorage {
        @Override public Optional<StagedCopy> stage(String key, long bytes, BooleanSupplier cancelled)
                throws InterruptedException { return sourceStorage.stage(key, bytes, cancelled); }
        @Override public String frozenKey(JobLease lease) { return sourceStorage.frozenKey(lease); }
        @Override public void store(String key, StagedCopy copy) { sourceStorage.store(key, copy); }
        @Override public Optional<Digest> digest(String key, long bytes, BooleanSupplier cancelled)
                throws InterruptedException { return sourceStorage.digest(key, bytes, cancelled); }
    }
}
