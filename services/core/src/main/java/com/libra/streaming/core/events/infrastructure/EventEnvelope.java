package com.libra.streaming.core.events.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, UUID aggregateId,
        long aggregateVersion, Instant occurredAt, UUID correlationId, JsonNode payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        if (eventType == null || !eventType.matches("[A-Za-z][A-Za-z0-9.]{0,99}")) {
            throw new IllegalArgumentException("Invalid event type");
        }
        if (schemaVersion != 1 || aggregateVersion < 1 || !payload.isObject()) {
            throw new IllegalArgumentException("Invalid event envelope");
        }
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
