package com.libra.streaming.media.upload.application;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
import java.time.Instant;
import java.util.UUID;

/** Media-owned persistence boundary; the implementation keeps its own transaction policy. */
public interface UploadPersistence {
    UploadSnapshot ensure(UploadDescriptor command);

    UploadSnapshot read(UUID uploadId);

    UploadSource grantSource(UUID uploadId);

    UploadSnapshot queue(UUID uploadId, UUID assetId, long assetVersion);

    record UploadSnapshot(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long byteLength, String sha256, String uploadState, String assetState,
            UUID jobId, int attemptCount, String failureCode, Instant expiresAt, Processing processing,
            long unsentEvents) {
        public UploadDescriptor command() {
            return new UploadDescriptor(uploadId, requestId, contentId, bindingId, assetId,
                    assetVersion, byteLength, sha256, expiresAt);
        }
    }

    /**
     * The job behind a submitted upload, null before submission: its stage, the stage and code of its last recorded
     * failure (a pending retry or the terminal one), and when a pending retry is due.
     */
    record Processing(String stage, String failureStage, String failureCode, Instant retryAt) {}

    record UploadSource(UploadSnapshot snapshot, String stagingKey) {}
}
