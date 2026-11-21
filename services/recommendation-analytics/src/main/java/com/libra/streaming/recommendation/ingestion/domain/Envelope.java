package com.libra.streaming.recommendation.ingestion.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The V1 envelope fields every Core event carries (core-event-envelope.v1). */
public record Envelope(UUID eventId, String eventType, UUID aggregateId, long aggregateVersion, Instant occurredAt,
        UUID correlationId) {
    public Envelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (aggregateVersion < 1) { throw EventRejection.schema("AGGREGATE_VERSION"); }
    }

    /** RA01.1 rule for every topic: the aggregate is the payload's own identity. */
    void requireAggregate(UUID payloadIdentity) {
        if (!aggregateId.equals(payloadIdentity)) { throw EventRejection.semantic("AGGREGATE_NOT_PAYLOAD_IDENTITY"); }
    }
}
