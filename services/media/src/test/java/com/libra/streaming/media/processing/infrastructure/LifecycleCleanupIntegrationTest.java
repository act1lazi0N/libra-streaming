package com.libra.streaming.media.processing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import com.libra.streaming.media.lifecycle.application.LifecycleSweeper;
import com.libra.streaming.media.lifecycle.application.ObjectCleanup;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import com.libra.streaming.media.upload.application.UploadFailure;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * M31 against real PostgreSQL and SeaweedFS: abandoned uploads expire through the contract transition, and a pass
 * deletes exactly the objects a settled upload no longer selects, leaving live uploads, current workers, selected
 * sources and outputs, uploads still inside their grace and unrelated keys untouched.
 */
class LifecycleCleanupIntegrationTest extends LifecycleFixture {
    static final Duration GRACE = Duration.ofHours(1);

    LifecycleSweeper sweeper;

    @BeforeEach
    void sweeper() {
        var cleanup = new ObjectCleanup(cleanupStorage, plans, metrics, 1000);
        sweeper = new LifecycleSweeper(expiry, plans, cleanup, metrics,
                new LifecycleSweeper.Settings(Duration.ofSeconds(1), 20, GRACE, Duration.ofSeconds(1)));
    }

    @Test
    void abandonedUploadExpiresOnceWithItsFailedEventAndRefusesCompletion() {
        var abandoned = open(Instant.now().plusSeconds(1800));
        var live = open(Instant.now().plusSeconds(1800));
        var submitted = queued(bytes(64, 1));
        backdate(abandoned.uploadId(), Duration.ofHours(2));
        backdate(submitted.uploadId(), Duration.ofHours(2));

        sweeper.pass();
        sweeper.pass();

        var status = uploads.read(abandoned.uploadId());
        assertThat(status.uploadState()).isEqualTo("EXPIRED");
        assertThat(status.assetState()).isEqualTo("FAILED");
        assertThat(status.failureCode()).isEqualTo("UPLOAD_EXPIRED");
        assertThat(status.jobId()).isNull();
        assertThat(status.processing()).isNull();
        assertThat(status.unsentEvents()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT payload->>'state' FROM media_outbox_events WHERE asset_id = ? ORDER BY aggregate_version
                """, String.class, abandoned.assetId())).containsExactly("FAILED");
        assertThat(uploads.read(live.uploadId()).uploadState()).isEqualTo("OPEN");
        assertThat(uploads.read(submitted.uploadId()).uploadState()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_outbox_events", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> uploads.queue(abandoned.uploadId(), abandoned.assetId(), 1))
                .isInstanceOfSatisfying(UploadFailure.class, f -> assertThat(f.code()).isEqualTo("UPLOAD_EXPIRED"));
        assertThatThrownBy(() -> uploads.grantSource(abandoned.uploadId()))
                .isInstanceOfSatisfying(UploadFailure.class, f -> assertThat(f.code()).isEqualTo("UPLOAD_EXPIRED"));
        assertThat(registry.get("libra.media.uploads").tag("state", "expired").gauge().value()).isEqualTo(1);
        assertThat(registry.get("libra.media.uploads").tag("state", "abandoned").gauge().value()).isZero();
    }

    @Test
    void settledUploadLosesExactlyItsUnselectedObjectsAndNothingElse() {
        // READY after a failed first attempt: attempt 1 left a source and part of an output; attempt 2 is selected.
        var ready = queued(bytes(64, 2));
        stage(ready, bytes(64, 2));
        var first = claim();
        String oldSource = attemptKey(ready, 1);
        put(BUCKET, oldSource);
        put(HLS, "hls/" + ready.uploadId() + "/attempt-1/segment-00000.ts");
        put(HLS, "hls/" + ready.uploadId() + "/attempt-1/media.m3u8");
        assertThat(leases.retry(first, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isTrue();
        var second = claim();
        String source = attemptKey(ready, 2);
        String output = "hls/" + ready.uploadId() + "/attempt-2";
        put(BUCKET, source);
        put(HLS, output + "/segment-00000.ts");
        put(HLS, output + "/master.m3u8");
        commitReady(second, source, output);
        // A key in the same upload directory that no attempt reserved.
        put(HLS, "hls/" + ready.uploadId() + "/notes.txt");
        settle(ready.uploadId());

        // Live work around it: an open upload, a worker holding a lease, a terminal upload still inside its grace.
        var openUpload = open(Instant.now().plusSeconds(1800));
        put(BUCKET, "staging/" + openUpload.uploadId() + "/source.mp4");
        var working = queued(bytes(64, 3));
        var current = claimFor(working);
        put(BUCKET, attemptKey(working, 1));
        put(HLS, "hls/" + working.uploadId() + "/attempt-1/segment-00000.ts");
        var recent = queued(bytes(64, 4));
        stage(recent, bytes(64, 4));
        var recentLease = claimFor(recent);
        put(BUCKET, attemptKey(recent, 1));
        assertThat(leases.fail(recentLease, ProcessingFailure.CORRUPT_INPUT)).isTrue();

        sweeper.pass();

        assertThat(gone(BUCKET, stagingKey(ready))).isTrue();
        assertThat(gone(BUCKET, oldSource)).isTrue();
        assertThat(objects(HLS, "hls/" + ready.uploadId() + "/")).containsExactlyInAnyOrder(
                output + "/segment-00000.ts", output + "/master.m3u8", "hls/" + ready.uploadId() + "/notes.txt");
        assertThat(gone(BUCKET, source)).isFalse();
        assertThat(cleanedAt(ready.uploadId())).as("a pass that deleted something is not final").isNull();
        assertThat(gone(BUCKET, "staging/" + openUpload.uploadId() + "/source.mp4")).isFalse();
        assertThat(gone(BUCKET, attemptKey(working, 1))).isFalse();
        assertThat(objects(HLS, "hls/" + working.uploadId() + "/")).hasSize(1);
        assertThat(gone(BUCKET, stagingKey(recent))).isFalse();
        assertThat(gone(BUCKET, attemptKey(recent, 1))).isFalse();
        assertThat(leases.renew(current, Duration.ofSeconds(30))).isTrue();

        // A late write to the staging key (an admitted PUT finishing) is found by the next pass, not left behind.
        put(BUCKET, stagingKey(ready));
        sweeper.pass();
        assertThat(gone(BUCKET, stagingKey(ready))).isTrue();
        assertThat(cleanedAt(ready.uploadId())).isNull();
        sweeper.pass();
        assertThat(cleanedAt(ready.uploadId())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT state FROM media_assets WHERE asset_id = ?", String.class,
                ready.assetId())).isEqualTo("READY");
        assertThat(frozen(source)).hasSize(16);
        assertThat(registry.get("libra.media.cleanup.deleted").counter().count()).isGreaterThanOrEqualTo(5);

        // Once recorded, the upload is never listed again: a later stray object is outside cleanup's knowledge.
        assertThat(plans.due(GRACE, 20)).extracting(plan -> plan.uploadId()).doesNotContain(ready.uploadId());
    }

    @Test
    void failedAssetKeepsItsSelectedSourceAndExpiredUploadLosesItsStaging() {
        var failed = queued(bytes(64, 5));
        stage(failed, bytes(64, 5));
        var lease = claim();
        String source = attemptKey(failed, 1);
        put(BUCKET, source);
        assertThat(sources.select(lease, source)).isTrue();
        assertThat(stages.beginTranscoding(lease)).isTrue();
        put(HLS, "hls/" + failed.uploadId() + "/attempt-1/segment-00000.ts");
        assertThat(leases.fail(lease, ProcessingFailure.CORRUPT_INPUT)).isTrue();
        settle(failed.uploadId());
        var expired = open(Instant.now().plusSeconds(1800));
        put(BUCKET, "staging/" + expired.uploadId() + "/source.mp4");
        // Its deadline passed thirty minutes ago: expired, but still inside the grace.
        backdate(expired.uploadId(), Duration.ofHours(1));

        sweeper.pass(); // expires, and deletes the failed upload's leftovers
        sweeper.pass(); // the expired upload is still inside its grace from the deadline: kept
        assertThat(gone(BUCKET, "staging/" + expired.uploadId() + "/source.mp4")).isFalse();
        assertThat(uploads.read(expired.uploadId()).uploadState()).isEqualTo("EXPIRED");
        assertThat(gone(BUCKET, source)).isFalse();
        assertThat(objects(HLS, "hls/" + failed.uploadId() + "/")).isEmpty();
        assertThat(cleanedAt(failed.uploadId())).isNotNull();

        var status = uploads.read(failed.uploadId());
        assertThat(status.processing()).isEqualTo(new MediaPersistenceService.Processing("FAILED_PERMANENT",
                "TRANSCODING", "CORRUPT_INPUT", null));
        assertThat(status.failureCode()).isEqualTo("CORRUPT_INPUT");

        // The grace counts from the upload deadline, not from when the sweeper happened to expire it.
        jdbc.update("UPDATE media_uploads SET updated_at = updated_at - INTERVAL '3 hours' WHERE id = ?",
                expired.uploadId());
        assertThat(plans.due(GRACE, 20)).isEmpty();
        assertThat(plans.due(Duration.ofMinutes(5), 20)).extracting(plan -> plan.uploadId())
                .containsExactly(expired.uploadId());
    }

    @Test
    void expiredUploadStagingIsDeletedAfterTheGraceFromItsDeadline() {
        var expired = open(Instant.now().plusSeconds(1800));
        String staging = "staging/" + expired.uploadId() + "/source.mp4";
        put(BUCKET, staging);
        backdate(expired.uploadId(), Duration.ofHours(1).plusMinutes(31));
        sweeper.pass();
        assertThat(gone(BUCKET, staging)).isTrue();
        sweeper.pass();
        assertThat(cleanedAt(expired.uploadId())).isNotNull();
        assertThat(registry.get("libra.media.cleanup.pending").gauge().value()).isZero();
    }

    @Test
    void unfinishedJobsAreNeverDueHoweverOldTheirDeadline() {
        // Each upload's deadline is long past; only its job decides. A retry waits, a worker holds a lease, a lease
        // ran out without a successor yet, and a job turned terminal a moment ago.
        var retrying = queued(bytes(64, 8));
        var leased = queued(bytes(64, 9));
        var abandoned = queued(bytes(64, 10));
        var justFailed = queued(bytes(64, 11));
        var all = List.of(retrying, leased, abandoned, justFailed);
        for (var upload : all) {
            put(BUCKET, stagingKey(upload));
            put(BUCKET, attemptKey(upload, 1));
            put(HLS, "hls/" + upload.uploadId() + "/attempt-1/segment-00000.ts");
            backdate(upload.uploadId(), Duration.ofHours(3));
        }
        var first = claimFor(retrying);
        assertThat(leases.retry(first, ProcessingFailure.PROCESSING_FAILED, Duration.ofMinutes(10))).isTrue();
        var working = claimFor(leased);
        assertThat(sources.select(working, attemptKey(leased, 1))).isTrue();
        assertThat(stages.beginTranscoding(working)).isTrue();
        var lapsed = claimFor(abandoned);
        assertThat(leases.fail(claimFor(justFailed), ProcessingFailure.CORRUPT_INPUT)).isTrue();
        expire(lapsed);

        assertThat(plans.due(GRACE, 20)).isEmpty();
        assertThat(plans.due(Duration.ofMinutes(5), 20)).as("the grace runs from the terminal transition").isEmpty();
        assertThat(plans.due(Duration.ZERO, 20)).extracting(plan -> plan.uploadId())
                .containsExactly(justFailed.uploadId());
        jdbc.update("UPDATE media_uploads SET cleanup_attempted_at = NULL");
        sweeper.pass();
        for (var upload : all) {
            assertThat(gone(BUCKET, stagingKey(upload))).as("staging of %s", upload.uploadId()).isFalse();
            assertThat(gone(BUCKET, attemptKey(upload, 1))).isFalse();
            assertThat(objects(HLS, "hls/" + upload.uploadId() + "/")).hasSize(1);
            assertThat(cleanedAt(upload.uploadId())).isNull();
        }
        assertThat(leases.renew(working, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void anUploadThatKeepsFailingDoesNotHoldTheQueue() {
        var poisoned = open(Instant.now().plusSeconds(1800));
        var healthy = open(Instant.now().plusSeconds(1800));
        backdate(poisoned.uploadId(), Duration.ofHours(4));
        backdate(healthy.uploadId(), Duration.ofHours(3));
        assertThat(expiry.expire(10)).isEqualTo(2);
        // A staging key outside today's staging area (as after a prefix change) is refused by the storage adapter.
        jdbc.update("UPDATE media_uploads SET staging_key = ? WHERE id = ?",
                "elsewhere/" + poisoned.uploadId() + "/source.mp4", poisoned.uploadId());
        String staging = "staging/" + healthy.uploadId() + "/source.mp4";
        put(BUCKET, staging);
        double failures = registry.get("libra.media.lifecycle.failures").tag("failure", "CLEANUP_FAILED").counter()
                .count();
        var one = new LifecycleSweeper(expiry, plans, new ObjectCleanup(cleanupStorage, plans, metrics, 1000),
                metrics, new LifecycleSweeper.Settings(Duration.ofSeconds(1), 1, GRACE, Duration.ofSeconds(1)));
        one.pass();
        assertThat(gone(BUCKET, staging)).isFalse();
        one.pass();
        assertThat(gone(BUCKET, staging)).as("the next pass takes the next upload").isTrue();
        one.pass();
        one.pass();
        assertThat(cleanedAt(healthy.uploadId())).isNotNull();
        assertThat(cleanedAt(poisoned.uploadId())).isNull();
        assertThat(registry.get("libra.media.lifecycle.failures").tag("failure", "CLEANUP_FAILED").counter()
                .count()).isEqualTo(failures + 2);
        one.close();
    }

    @Test
    void retryStateIsVisibleWhileTheRetryWaits() {
        var upload = queued(bytes(64, 6));
        var lease = claim();
        assertThat(stagesAfterSelection(lease, upload)).isTrue();
        assertThat(leases.retry(lease, ProcessingFailure.PROCESSING_FAILED, Duration.ofMinutes(10))).isTrue();
        var waiting = uploads.read(upload.uploadId());
        assertThat(waiting.assetState()).isEqualTo("PROCESSING");
        assertThat(waiting.failureCode()).as("only a terminal failure is the asset's failure").isNull();
        assertThat(waiting.processing().stage()).isEqualTo("QUEUED");
        assertThat(waiting.processing().failureStage()).isEqualTo("TRANSCODING");
        assertThat(waiting.processing().failureCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(waiting.processing().retryAt()).isAfter(Instant.now().plusSeconds(500));
        assertThat(waiting.unsentEvents()).isEqualTo(1);
        assertThat(registry.get("libra.media.jobs").tag("stage", "retrying").gauge().value()).isEqualTo(1);
        assertThat(registry.get("libra.media.jobs.unfinished").tag("attempts", "1").gauge().value()).isEqualTo(1);
        assertThat(plans.due(Duration.ZERO, 20)).as("an unfinished job is never settled").isEmpty();

        // The next claim clears the recorded failure; a third failed attempt exhausts the job in its own stage.
        jdbc.update("UPDATE media_jobs SET next_attempt_at = clock_timestamp() WHERE id = ?", lease.jobId());
        var second = claim();
        assertThat(uploads.read(upload.uploadId()).processing())
                .isEqualTo(new MediaPersistenceService.Processing("CLAIMED", null, null, null));
        assertThat(leases.retry(second, ProcessingFailure.PROCESSING_FAILED, Duration.ofMillis(1))).isTrue();
        var third = claim();
        expire(third);
        assertThat(leases.claim(Duration.ofSeconds(30))).isEmpty();
        var exhausted = uploads.read(upload.uploadId());
        assertThat(exhausted.processing()).isEqualTo(new MediaPersistenceService.Processing("EXHAUSTED", "CLAIMED",
                "RETRY_EXHAUSTED", null));
        assertThat(registry.get("libra.media.jobs.terminal").tag("failure", "RETRY_EXHAUSTED").gauge().value())
                .isEqualTo(1);
    }

    @Test
    void databaseRefusesCleanupMarksAndTransitionsThatWouldReopenSelection() {
        var openUpload = open(Instant.now().plusSeconds(1800));
        assertThatThrownBy(() -> plans.markCleaned(openUpload.uploadId())).isInstanceOf(DataAccessException.class);
        var running = queued(bytes(64, 7));
        var lease = claim();
        assertThatThrownBy(() -> plans.markCleaned(running.uploadId())).isInstanceOf(DataAccessException.class);
        assertThat(leases.fail(lease, ProcessingFailure.CORRUPT_INPUT)).isTrue();
        for (String change : List.of("stage = 'QUEUED'", "attempt_count = 2", "failure_code = 'PROCESSING_FAILED'",
                "failure_stage = 'FINALIZING'", "upload_id = '" + openUpload.uploadId() + "'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE media_jobs SET " + change + " WHERE id = ?", lease.jobId()))
                    .as(change).isInstanceOf(DataAccessException.class);
        }
        assertThat(plans.markCleaned(running.uploadId())).isTrue();
        assertThat(plans.markCleaned(running.uploadId())).isFalse();
        assertThatThrownBy(() -> jdbc.update("UPDATE media_uploads SET objects_cleaned_at = NULL WHERE id = ?",
                running.uploadId())).isInstanceOf(DataAccessException.class);
        // Expiry is the contract transition only: the asset must fail with it, and it is never undone.
        assertThatThrownBy(() -> jdbc.update("UPDATE media_uploads SET state = 'EXPIRED' WHERE id = ?",
                openUpload.uploadId())).isInstanceOf(DataAccessException.class);
        backdate(openUpload.uploadId(), Duration.ofHours(2));
        assertThat(expiry.expire(10)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE media_uploads SET state = 'OPEN' WHERE id = ?",
                openUpload.uploadId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void storageRefusesKeysOutsideTheNamedAttempt() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> cleanupStorage.deleteStaging("sources/" + id + "/attempt-1/source.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanupStorage.deleteStaging("staging/" + id + "/../x/source.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanupStorage.deleteSource("sources/" + id + "/attempt-4/source.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanupStorage.deleteSource("staging/" + id + "/attempt-1/source.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanupStorage.deleteOutput("hls/" + id, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanupStorage.deleteOutput("sources/" + id + "/attempt-1", 10))
                .isInstanceOf(IllegalArgumentException.class);
        // attempt-1 never matches a sibling such as attempt-1x, and a bounded batch reports what remains.
        put(HLS, "hls/" + id + "/attempt-1x/keep.ts");
        for (int i = 0; i < 3; i++) { put(HLS, "hls/" + id + "/attempt-1/segment-" + i + ".ts"); }
        assertThat(cleanupStorage.deleteOutput("hls/" + id + "/attempt-1", 2))
                .isEqualTo(new CleanupStorage.Batch(2, false));
        assertThat(cleanupStorage.deleteOutput("hls/" + id + "/attempt-1", 2))
                .isEqualTo(new CleanupStorage.Batch(1, true));
        assertThat(objects(HLS, "hls/" + id + "/")).containsExactly("hls/" + id + "/attempt-1x/keep.ts");
    }
}
