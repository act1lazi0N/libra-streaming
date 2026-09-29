package com.libra.streaming.media.upload.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** The exact identity and content fingerprint of one Core-authorized upload. */
public record UploadDescriptor(UUID uploadId, UUID requestId, UUID contentId, UUID bindingId,
        UUID assetId, long assetVersion, long byteLength, String sha256, Instant expiresAt) {
    public boolean valid() {
        return uploadId != null && requestId != null && contentId != null && bindingId != null
                && assetId != null && assetVersion >= 1 && byteLength >= 1 && byteLength <= 268435456
                && sha256 != null && sha256.matches("[0-9a-f]{64}") && expiresAt != null;
    }

    public boolean withinReservationWindow(Instant now) {
        return expiresAt.isAfter(now) && !expiresAt.isAfter(now.plus(Duration.ofHours(1)));
    }

    public String fingerprint() {
        try {
            String canonical = requestId + "\n" + contentId + "\n" + bindingId + "\n" + assetId + "\n"
                    + assetVersion + "\n" + byteLength + "\n" + sha256 + "\n" + expiresAt;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
