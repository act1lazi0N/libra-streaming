package com.libra.streaming.recommendation.profiles.application;

import com.libra.streaming.recommendation.ingestion.domain.ProfileChange;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Latest version wins per profile; {@code ProfileDeleted} is terminal and erases personalization (RA01.1). */
public class ProfileProjection {
    private final ProfileStore store;
    private final List<ProfileErasure> erasures;

    public ProfileProjection(ProfileStore store, List<ProfileErasure> erasures) {
        this.store = store;
        this.erasures = List.copyOf(erasures);
    }

    public ProfileStore.Write apply(ProfileChange change) {
        var envelope = change.envelope();
        return apply(change.profileId(), change.accountId(), envelope.aggregateVersion(),
                change.type() == ProfileChange.Type.DELETED, envelope.occurredAt());
    }

    /** A bootstrap snapshot item (live profile or tombstone) follows the same rules as a live event (RA02.4). */
    public ProfileStore.Write apply(UUID profileId, UUID accountId, long version, boolean deleted, Instant changedAt) {
        if (!deleted) { return store.live(profileId, accountId, version, changedAt); }
        var write = store.tombstone(profileId, accountId, version, changedAt);
        if (write == ProfileStore.Write.APPLIED) { erasures.forEach(erasure -> erasure.erase(profileId)); }
        return write;
    }
}
