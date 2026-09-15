package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.catalog.MediaProjectionService;
import com.libra.streaming.core.integration.outbox.EventEnvelope;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MediaAssetListener {
    private final MediaProjectionService projections;
    private final ObjectMapper mapper;

    public MediaAssetListener(MediaProjectionService projections, ObjectMapper mapper) {
        this.projections = projections;
        this.mapper = mapper;
    }

    @KafkaListener(id = "core-media-assets", topics = "media.assets.v1", groupId = "core-media-projection-v1",
            containerFactory = "mediaListenerFactory", autoStartup = "${libra.catalog.media-listener-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        if (record.value() == null || record.value().getBytes(StandardCharsets.UTF_8).length > 262144) {
            throw new IllegalArgumentException("Invalid media event size");
        }
        projections.accept(record.key(), mapper.readValue(record.value(), EventEnvelope.class));
    }
}
