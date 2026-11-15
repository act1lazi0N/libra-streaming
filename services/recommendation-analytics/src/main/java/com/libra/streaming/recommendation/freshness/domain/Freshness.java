package com.libra.streaming.recommendation.freshness.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** The {@code updatedAt} rule: oldest confirmed checkpoint, never ahead of the local clock minus the skew margin. */
public final class Freshness {
    /** Core rejects a future {@code updatedAt} with no tolerance, so the reported value stays this far behind. */
    public static final Duration SKEW_MARGIN = Duration.ofSeconds(5);

    private Freshness() {}

    /**
     * @param checkpoints the confirmed checkpoint of every input the answer depends on; empty when none is confirmed
     * @return empty when any input has no confirmed checkpoint, so the answer is not ready
     */
    public static Optional<Instant> updatedAt(Collection<Optional<Instant>> checkpoints, Instant now) {
        if (checkpoints.isEmpty()) { throw new IllegalArgumentException("An answer depends on at least one input"); }
        Instant oldest = now.minus(SKEW_MARGIN);
        for (Optional<Instant> checkpoint : checkpoints) {
            if (checkpoint.isEmpty()) { return Optional.empty(); }
            if (checkpoint.get().isBefore(oldest)) { oldest = checkpoint.get(); }
        }
        return Optional.of(oldest);
    }
}
