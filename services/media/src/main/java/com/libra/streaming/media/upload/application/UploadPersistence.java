package com.libra.streaming.media.upload.application;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
import java.time.Instant;
import java.util.UUID;

/** Media-owned persistence boundary; the implementation keeps its own transaction policy. */
public interface UploadPersistence {
    UploadSnapshot ensure(UploadDescriptor command);

    UploadSnapshot read(UUID uploadId);

    UploadSource grantSource(UUID uploadId);

    record UploadSnapshot(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long byteLength, String sha256, String uploadState, String assetState,
            UUID jobId, int attemptCount, Instant expiresAt) {
        public UploadDescriptor command() {
            return new UploadDescriptor(uploadId, requestId, contentId, bindingId, assetId,
                    assetVersion, byteLength, sha256, expiresAt);
        }
    }

    record UploadSource(UploadSnapshot snapshot, String stagingKey) {}
}
