package com.libra.streaming.recommendation.ingestion.domain;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import java.util.Objects;
import java.util.UUID;

/** {@code core.profiles.v1}: a lifecycle step of one profile; {@link Type#DELETED} is terminal. */
public record ProfileChange(Envelope envelope, Type type, UUID accountId, UUID profileId) implements CoreEvent {
    public enum Type {
        CREATED("ProfileCreated"), UPDATED("ProfileUpdated"), DELETED("ProfileDeleted");

        private final String wireName;

        Type(String wireName) { this.wireName = wireName; }

        public String wireName() { return wireName; }

        public static Type fromWireName(String wireName) {
            for (Type type : values()) { if (type.wireName.equals(wireName)) { return type; } }
            throw EventRejection.schema("EVENT_TYPE");
        }
    }

    public ProfileChange {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(profileId, "profileId");
        envelope.requireAggregate(profileId);
    }

    @Override
    public InputTopic topic() { return InputTopic.PROFILES; }
}
