package com.libra.streaming.media.events.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One leased outbox event, ready for the broker. {@code leaseToken} fences the delivery outcome: only the holder of
 * the current lease may mark the event sent or return it. {@code key} is the asset ID and {@code value} the complete
 * {@code MediaAssetStateChanged} envelope text.
 */
public record OutboxDelivery(UUID eventId, UUID leaseToken, int attempt, String topic, String key, String value) {
    public OutboxDelivery {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(leaseToken);
        if (attempt < 1 || topic == null || topic.isBlank() || key == null || key.isBlank() || value == null) {
            throw new IllegalArgumentException("Invalid outbox delivery");
        }
    }
}
