package com.libra.streaming.core.events.application;

import com.libra.streaming.core.events.domain.CoreEventTopic;
import java.util.UUID;

/** Appends an event inside the caller's database transaction. */
public interface EventPort {
    void append(CoreEventTopic topic, String type, UUID id, long version, UUID correlationId, Object payload);
}
