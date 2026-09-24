package com.libra.streaming.media.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local database primitives only; no authenticated control endpoint or storage check exists yet. */
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
        validate(input);
        String fingerprint = fingerprint(input);
        Snapshot existing = store.find(input.uploadId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) { throw new MediaPersistenceException("IDEMPOTENCY_CONFLICT"); }
            return existing;
        }
        Instant now = clock.instant();
        if (!input.expiresAt().isAfter(now) || input.expiresAt().isAfter(now.plus(Duration.ofHours(1)))) {
            throw new MediaPersistenceException("UPLOAD_EXPIRED");
        }
        try {
            store.insertAsset(input, now);
            store.insertUpload(input, fingerprint, now);
        } catch (DataIntegrityViolationException exception) {
            throw new MediaPersistenceException("UPLOAD_STATE_CONFLICT");
        }
        Snapshot recorded = store.find(input.uploadId());
        if (recorded == null) { throw new MediaPersistenceException("UPLOAD_STATE_CONFLICT"); }
        if (!recorded.fingerprint().equals(fingerprint)) {
            throw new MediaPersistenceException("IDEMPOTENCY_CONFLICT");
        }
        return recorded;
    }

    @Transactional(readOnly = true)
    public Snapshot read(UUID uploadId) {
        if (uploadId == null) { throw new MediaPersistenceException("INVALID_REQUEST"); }
        Snapshot result = store.find(uploadId);
        if (result == null) { throw new MediaPersistenceException("NOT_FOUND"); }
        return result;
    }

    private static void validate(Ensure input) {
        if (input == null || input.uploadId() == null || input.requestId() == null || input.contentId() == null
                || input.bindingId() == null || input.assetId() == null || input.assetVersion() < 1
                || input.byteLength() < 1 || input.byteLength() > 268435456
                || input.sha256() == null || !input.sha256().matches("[0-9a-f]{64}")
                || input.expiresAt() == null) { throw new MediaPersistenceException("INVALID_REQUEST"); }
    }

    private static String fingerprint(Ensure input) {
        try {
            String canonical = input.requestId() + "\n" + input.contentId() + "\n" + input.bindingId() + "\n"
                    + input.assetId() + "\n" + input.assetVersion() + "\n" + input.byteLength() + "\n"
                    + input.sha256() + "\n" + input.expiresAt();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Ensure(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long byteLength, String sha256, Instant expiresAt) {}

    public record Snapshot(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId, UUID assetId,
            long assetVersion, long byteLength, String sha256, String fingerprint,
            String uploadState, String assetState, UUID jobId, Instant expiresAt) {}
}
