package com.libra.streaming.recommendation.profiles.application;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.profiles.domain.ProfileState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Versioned profile lifecycle with terminal tombstones. Joins the caller's transaction. */
public interface ProfileStore {
    enum Write {
        APPLIED,
        /** The stored version is the same or newer. */
        STALE,
        /** The profile is deleted; nothing revives it. */
        TOMBSTONED
    }

    /**
     * Records a live profile at {@code version} unless a newer version or a tombstone is stored.
     *
     * @throws EventRejection EVENT_CONFLICT when the profile is stored under another account
     */
    Write live(UUID profileId, UUID accountId, long version, Instant changedAt);

    /**
     * Records the terminal tombstone. Holds the profile's exclusive lock until commit, so no playback fact for the
     * profile can commit with a profile link in between (see the playback store's shared lock).
     *
     * @return APPLIED when this call deleted the profile; TOMBSTONED when it was already deleted
     * @throws EventRejection EVENT_CONFLICT when the profile is stored under another account
     */
    Write tombstone(UUID profileId, UUID accountId, long version, Instant changedAt);

    Optional<ProfileState> find(UUID profileId);
}
