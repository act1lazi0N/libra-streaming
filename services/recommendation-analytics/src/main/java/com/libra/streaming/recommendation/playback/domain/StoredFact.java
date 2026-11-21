package com.libra.streaming.recommendation.playback.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * What storing one playback fact decided, read in the storing transaction.
 *
 * @param profileLink the profile the fact stays linked to; null when the profile was already deleted (tombstone)
 * @param qualification how the fact changed its session's qualifying instant
 */
public record StoredFact(UUID profileLink, Qualification qualification) {
    public StoredFact {
        Objects.requireNonNull(qualification, "qualification");
    }
}
