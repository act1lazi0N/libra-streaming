package com.libra.streaming.core.integration.media;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class MediaControlModels {
    private MediaControlModels() {}

    /** Construct from the committed Core intent, never forward browser JSON or credentials. */
    public record EnsureUpload(@NotNull UUID requestId, @NotNull UUID contentId, @NotNull UUID bindingId,
            @NotNull UUID assetId, @Min(1) long assetVersion, @Min(1) @Max(268435456) long byteLength,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256, @NotNull Instant expiresAt) {}

    public enum UploadState { OPEN, SUBMITTED, EXPIRED }
    public enum AssetState { UPLOADING, QUEUED, PROCESSING, READY, FAILED }
    public enum FailureCode {
        UPLOAD_EXPIRED, SOURCE_MISSING, SIZE_MISMATCH, CHECKSUM_MISMATCH, UNSUPPORTED_MEDIA,
        CORRUPT_INPUT, PROCESSING_FAILED, RETRY_EXHAUSTED
    }
    public record Status(@NotNull UUID uploadId, @NotNull UUID contentId, @NotNull UUID bindingId,
            @NotNull UUID assetId, @Min(1) long assetVersion, @NotNull UploadState uploadState,
            @NotNull AssetState assetState, UUID jobId, @Min(0) @Max(3) int attemptCount,
            FailureCode failureCode, @NotNull Instant expiresAt) {
        boolean consistent() {
            if (uploadState == null || assetState == null) { return false; }
            return switch (uploadState) {
                case OPEN -> assetState == AssetState.UPLOADING && jobId == null && attemptCount == 0 && failureCode == null;
                case EXPIRED -> assetState == AssetState.FAILED && jobId == null && attemptCount == 0
                        && failureCode == FailureCode.UPLOAD_EXPIRED;
                case SUBMITTED -> assetState != AssetState.UPLOADING && jobId != null
                        && (assetState != AssetState.FAILED || failureCode != null)
                        && (assetState != AssetState.READY || failureCode == null);
            };
        }
    }
    public enum Failure { DISABLED, UNAVAILABLE, INVALID_RESPONSE, NOT_FOUND, CONFLICT, EXPIRED, ACCESS_DENIED }
    public record Result(Status value, Failure failure) {
        static Result failed(Failure failure) { return new Result(null, failure); }
    }
    public record UploadUrl(@NotNull UUID uploadId, @NotNull String method, @NotNull String url,
            @NotNull Instant expiresAt, @NotNull Map<String, String> requiredHeaders) {
        @Override public String toString() { return "UploadUrl[uploadId=" + uploadId + ", url=REDACTED]"; }
    }
    public record GrantResult(UploadUrl value, Failure failure) {
        static GrantResult failed(Failure failure) { return new GrantResult(null, failure); }
    }
}
