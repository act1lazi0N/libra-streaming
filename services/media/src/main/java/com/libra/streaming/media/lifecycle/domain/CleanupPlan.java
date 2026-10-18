package com.libra.streaming.media.lifecycle.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * What one settled upload may still have in storage, read from durable state: its staging key, how many attempts its
 * job claimed (each reserved its own source key and output prefix), and the source and output it selected, which are
 * kept. A settled upload is expired (never submitted) or has a terminal job, so nothing can be selected any more.
 */
public record CleanupPlan(UUID uploadId, String stagingKey, int attempts, String selectedSourceKey,
        String selectedOutputPrefix) {
    public CleanupPlan {
        Objects.requireNonNull(uploadId);
        if (stagingKey == null || stagingKey.isBlank()) { throw new IllegalArgumentException("Invalid staging key"); }
        if (attempts < 0 || attempts > 3) { throw new IllegalArgumentException("Invalid attempt count"); }
    }
}
