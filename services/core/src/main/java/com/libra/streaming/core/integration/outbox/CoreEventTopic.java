package com.libra.streaming.core.integration.outbox;

public enum CoreEventTopic {
    CATALOG("core.catalog.v1"),
    PLAYBACK("core.playback.v1"),
    PROFILES("core.profiles.v1");

    private final String value;

    CoreEventTopic(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
