package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.catalog.MediaProjectionService.Change;
import com.libra.streaming.core.catalog.CatalogModels.MediaState;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MediaEventDecoder {
    private final ObjectMapper mapper;
    private final Validator validator;
    public MediaEventDecoder(ObjectMapper mapper, Validator validator) { this.mapper = mapper; this.validator = validator; }
    public EventEnvelope decode(String key, String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 262144) {
            throw new IllegalArgumentException("Invalid media event size");
        }
        var event = mapper.readValue(body, EventEnvelope.class);
        if (event == null) { throw new IllegalArgumentException("Invalid media event"); }
        validate(key, event);
        return event;
    }
    public Change validate(String key, EventEnvelope event) {
        if (!event.eventType().equals("MediaAssetStateChanged") || !event.aggregateId().toString().equals(key)) {
            throw new IllegalArgumentException("Invalid media event envelope");
        }
        Change change = mapper.treeToValue(event.payload(), Change.class);
        if (change == null || !validator.validate(change).isEmpty() || !event.aggregateId().equals(change.assetId())
                || (change.state() == MediaState.READY && change.durationSeconds() == null)) {
            throw new IllegalArgumentException("Invalid media state payload");
        }
        return change;
    }
}
