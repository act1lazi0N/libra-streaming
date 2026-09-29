package com.libra.streaming.core.events.infrastructure;

import com.libra.streaming.core.events.application.EventPort;
import com.libra.streaming.core.events.domain.CoreEventTopic;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DomainEvents implements EventPort {
    private final OutboxWriter writer;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DomainEvents(OutboxWriter writer, ObjectMapper mapper, Clock clock) {
        this.writer = writer;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void append(CoreEventTopic topic, String type, UUID id, long version, UUID correlationId, Object payload) {
        writer.append(topic, new EventEnvelope(UUID.randomUUID(), type, 1, id, version,
                clock.instant(), correlationId, mapper.valueToTree(payload)));
    }
}
