package com.libra.streaming.recommendation.playback.application;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import com.libra.streaming.recommendation.ingestion.domain.PlaybackProgress;
import com.libra.streaming.recommendation.playback.domain.StoredFact;
import com.libra.streaming.recommendation.playback.domain.SessionTotals;
import java.util.Optional;
import java.util.UUID;

/** Accepted playback facts and their session totals. Joins the caller's transaction. */
public interface PlaybackStore {
    /**
     * Stores the fact and adds it to its session totals. It does not depend on catalog or profile data having
     * arrived; the link to a deleted profile is dropped.
     *
     * @return the profile link kept (null for a deleted profile) and how the fact changed the session's qualifying
     *         instant, read atomically with the update
     * @throws EventRejection EVENT_CONFLICT for a second event at the same {@code (sessionId, sequence)} or a session
     *         whose account, profile or content differs from its earlier facts
     */
    StoredFact append(PlaybackProgress fact);

    Optional<SessionTotals> session(UUID sessionId);
}
