package com.libra.streaming.media.upload.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface UploadGrantSigner {
    Grant sign(UploadPersistence.UploadSource source);

    record Grant(UUID uploadId, String method, String url, Instant expiresAt,
            Map<String, String> requiredHeaders) {
        @Override public String toString() { return "Grant[uploadId=" + uploadId + ", url=REDACTED]"; }
    }
}
