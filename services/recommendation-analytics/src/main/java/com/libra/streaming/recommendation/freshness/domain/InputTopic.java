package com.libra.streaming.recommendation.freshness.domain;

/** Core topics whose confirmed processing position bounds the freshness of an answer. */
public enum InputTopic {
    CATALOG("core.catalog.v1", Groups.CATALOG),
    PROFILES("core.profiles.v1", Groups.PROFILES),
    PLAYBACK("core.playback.v1", Groups.PLAYBACK);

    /**
     * The consumer group that applies each topic. Constants, so the listeners can name them in annotations and the
     * checkpoint reads the progress of exactly the group that writes the projection.
     */
    public static final class Groups {
        public static final String CATALOG = "analytics-catalog-v1";
        public static final String PROFILES = "analytics-profiles-v1";
        public static final String PLAYBACK = "analytics-playback-v1";

        private Groups() {}
    }

    private final String wireName;
    private final String consumerGroup;

    InputTopic(String wireName, String consumerGroup) {
        this.wireName = wireName;
        this.consumerGroup = consumerGroup;
    }

    public String wireName() { return wireName; }

    public String consumerGroup() { return consumerGroup; }

    public static InputTopic fromWireName(String wireName) {
        for (InputTopic topic : values()) {
            if (topic.wireName.equals(wireName)) { return topic; }
        }
        throw new IllegalArgumentException("Unknown input topic");
    }
}
