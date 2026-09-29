package com.libra.streaming.core.events.domain;

/** Stable event topic names shared by the Core use cases and their outbox adapter. */
public enum CoreEventTopic {
    CATALOG("core.catalog.v1"),
    PLAYBACK("core.playback.v1"),
    PROFILES("core.profiles.v1");

    private final String value;

    CoreEventTopic(String value) { this.value = value; }

    public String value() { return value; }
}
