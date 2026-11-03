package com.libra.streaming.media.delivery.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a verified Core playback ticket authorizes: one session's delivery of one exact asset generation, selected by
 * Core for one content binding, until the ticket expires. It is never derived from anything the browser chose except the
 * session path, which the ticket must already match.
 */
public record PlaybackGrant(UUID sessionId, UUID contentId, UUID bindingId, UUID assetId, long assetVersion,
        Instant expiresAt) {
    public PlaybackGrant {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(contentId);
        Objects.requireNonNull(bindingId);
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(expiresAt);
        if (assetVersion < 1) { throw new IllegalArgumentException("Asset version"); }
    }
}
