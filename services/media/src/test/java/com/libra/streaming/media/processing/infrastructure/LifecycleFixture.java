package com.libra.streaming.media.processing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.libra.streaming.media.lifecycle.application.CleanupPlans;
import com.libra.streaming.media.lifecycle.application.CleanupStorage;
import com.libra.streaming.media.lifecycle.application.UploadExpiry;
import com.libra.streaming.media.lifecycle.infrastructure.LifecycleMetrics;
import com.libra.streaming.media.processing.application.JobStages;
import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.upload.infrastructure.MediaPersistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** The lifecycle ports and the upload, lease and object helpers shared by the M31 and M32 cleanup suites. */
abstract class LifecycleFixture extends SourceStorageFixture {
    static final String HLS = "libra-hls";

    @Autowired UploadExpiry expiry;
    @Autowired CleanupPlans plans;
    @Autowired CleanupStorage cleanupStorage;
    @Autowired LifecycleMetrics metrics;
    @Autowired JobStages stages;
    @Autowired OutputSelections outputs;
    @Autowired MeterRegistry registry;

    boolean stagesAfterSelection(JobLease lease, MediaPersistenceService.Ensure upload) {
        String key = attemptKey(upload, lease.attempt());
        put(BUCKET, key);
        return sources.select(lease, key) && stages.beginTranscoding(lease);
    }

    void commitReady(JobLease lease, String source, String output) {
        assertThat(sources.select(lease, source)).isTrue();
        assertThat(stages.beginTranscoding(lease)).isTrue();
        assertThat(outputs.beginFinalizing(lease)).isTrue();
        assertThat(outputs.select(lease, new OutputSelections.Selection(source, output, output + "/master.m3u8",
                10))).isTrue();
    }

    MediaPersistenceService.Ensure open(Instant expiresAt) {
        var upload = new MediaPersistenceService.Ensure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 64, "a".repeat(64), expiresAt);
        uploads.ensure(upload);
        return upload;
    }

    /** Claims the next job and checks it is this upload's (each test queues its uploads in order). */
    JobLease claimFor(MediaPersistenceService.Ensure upload) {
        var lease = claim();
        assertThat(lease.uploadId()).isEqualTo(upload.uploadId());
        return lease;
    }

    /** Moves an upload's deadline (and creation) into the past, as if the time had gone by. */
    void backdate(UUID uploadId, Duration by) {
        jdbc.update("""
                UPDATE media_uploads SET created_at = created_at - make_interval(secs => ?),
                    expires_at = expires_at - make_interval(secs => ?) WHERE id = ?
                """, (double) by.toSeconds(), (double) by.toSeconds(), uploadId);
    }

    /** A settled upload whose deadline and terminal transition both lie more than the grace in the past. */
    void settle(UUID uploadId) {
        backdate(uploadId, Duration.ofHours(3));
        jdbc.update("UPDATE media_jobs SET updated_at = updated_at - INTERVAL '3 hours' WHERE upload_id = ?",
                uploadId);
    }

    Instant cleanedAt(UUID uploadId) {
        var value = jdbc.queryForObject("SELECT objects_cleaned_at FROM media_uploads WHERE id = ?",
                java.sql.Timestamp.class, uploadId);
        return value == null ? null : value.toInstant();
    }

    void put(String bucket, String key) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(bytes(16, key.hashCode())));
    }

    boolean gone(String bucket, String key) {
        try {
            client.headObject(b -> b.bucket(bucket).key(key));
            return false;
        } catch (NoSuchKeyException missing) {
            return true;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception other) {
            if (other.statusCode() == 404) { return true; }
            throw other;
        }
    }

    List<String> objects(String bucket, String prefix) {
        return client.listObjectsV2(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(object -> object.key()).toList();
    }
}
