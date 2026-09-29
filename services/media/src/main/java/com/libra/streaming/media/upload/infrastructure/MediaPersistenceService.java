package com.libra.streaming.media.upload.infrastructure;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
import com.libra.streaming.media.upload.application.UploadFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Media-owned upload and asset records; HTTP callers verify Core's current binding first. */
@Service
public class MediaPersistenceService {
    private final MediaUploadStore store;
    private final Clock clock;

    MediaPersistenceService(MediaUploadStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public Snapshot ensure(Ensure input) {
        UploadDescriptor descriptor = descriptor(input);
        String fingerprint = descriptor.fingerprint();
        Snapshot existing = store.find(input.uploadId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) { throw new UploadFailure("IDEMPOTENCY_CONFLICT"); }
            return existing;
        }
        Instant now = clock.instant();
        if (!descriptor.withinReservationWindow(now)) {
            throw new UploadFailure("UPLOAD_EXPIRED");
        }
        try {
            store.insertAsset(input, now);
            store.insertUpload(input, fingerprint, now);
        } catch (DataIntegrityViolationException exception) {
            throw new UploadFailure("UPLOAD_STATE_CONFLICT");
        }
        Snapshot recorded = store.find(input.uploadId());
        if (recorded == null) { throw new UploadFailure("UPLOAD_STATE_CONFLICT"); }
        if (!recorded.fingerprint().equals(fingerprint)) {
            throw new UploadFailure("IDEMPOTENCY_CONFLICT");
        }
        return recorded;
    }

    @Transactional(readOnly = true)
    public Snapshot read(UUID uploadId) {
        if (uploadId == null) { throw new UploadFailure("INVALID_REQUEST"); }
        Snapshot result = store.find(uploadId);
        if (result == null) { throw new UploadFailure("NOT_FOUND"); }
        return result;
    }

    @Transactional(readOnly = true)
    public GrantSource grantSource(UUID uploadId) {
        Snapshot current = read(uploadId);
        if (!current.uploadState().equals("OPEN") || !current.assetState().equals("UPLOADING")) {
            throw new UploadFailure("UPLOAD_STATE_CONFLICT");
        }
        if (!current.expiresAt().isAfter(clock.instant())) {
            throw new UploadFailure("UPLOAD_EXPIRED");
        }
        return new GrantSource(current, store.stagingKey(uploadId));
    }

    @Transactional
    public Snapshot queue(UUID uploadId, UUID assetId, long assetVersion) {
        store.queue(uploadId, assetId, assetVersion, UUID.randomUUID(), clock.instant());
        return read(uploadId);
    }

    private static UploadDescriptor descriptor(Ensure input) {
        if (input == null) { throw new UploadFailure("INVALID_REQUEST"); }
        var descriptor = new UploadDescriptor(input.uploadId(), input.requestId(), input.contentId(),
                input.bindingId(), input.assetId(), input.assetVersion(), input.byteLength(),
                input.sha256(), input.expiresAt());
        if (!descriptor.valid()) { throw new UploadFailure("INVALID_REQUEST"); }
        return descriptor;
    }

    public record Ensure(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long byteLength, String sha256, Instant expiresAt) {}

    public record Snapshot(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long byteLength, String sha256, String fingerprint,
            String uploadState, String assetState, UUID jobId, int attemptCount, Instant expiresAt) {}
    public record GrantSource(Snapshot snapshot, String stagingKey) {}
}
