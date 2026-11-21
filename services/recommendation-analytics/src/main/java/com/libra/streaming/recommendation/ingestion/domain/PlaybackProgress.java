package com.libra.streaming.recommendation.ingestion.domain;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.util.Objects;
import java.util.UUID;

/** {@code core.playback.v1}: one accepted heartbeat of a playback session, an additive fact. */
public record PlaybackProgress(Envelope envelope, UUID accountId, UUID profileId, UUID sessionId, UUID contentId,
        UUID assetId, long assetVersion, long sequence, long positionMs, long acceptedWatchedMs, long totalWatchedMs,
        boolean qualified, State state) implements CoreEvent {
    /** A session qualifies as a view once its accepted cumulative time reaches this many milliseconds. */
    public static final long QUALIFYING_MS = 30_000L;

    public enum State { PLAYING, PAUSED, BUFFERING, SEEKING, ENDED }

    public PlaybackProgress {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(state, "state");
        envelope.requireAggregate(sessionId);
        if (envelope.aggregateVersion() != sequence) { throw EventRejection.semantic("VERSION_NOT_SEQUENCE"); }
        if (acceptedWatchedMs > totalWatchedMs) { throw EventRejection.semantic("INCREMENT_EXCEEDS_TOTAL"); }
        if (qualified != (totalWatchedMs >= QUALIFYING_MS)) { throw EventRejection.semantic("QUALIFIED_MISMATCH"); }
    }

    @Override
    public InputTopic topic() { return InputTopic.PLAYBACK; }
}
