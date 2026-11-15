package com.libra.streaming.recommendation.freshness.domain;

/** Core topics whose confirmed processing position bounds the freshness of an answer. */
public enum InputTopic {
    CATALOG("core.catalog.v1"),
    PROFILES("core.profiles.v1"),
    PLAYBACK("core.playback.v1");

    private final String wireName;

    InputTopic(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static InputTopic fromWireName(String wireName) {
        for (InputTopic topic : values()) {
            if (topic.wireName.equals(wireName)) { return topic; }
        }
        throw new IllegalArgumentException("Unknown input topic");
    }
}
