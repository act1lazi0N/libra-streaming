package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.catalog.MediaProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class MediaAssetListener {
    private final MediaProjectionService projections;
    private final MediaEventDecoder decoder;

    public MediaAssetListener(MediaProjectionService projections, MediaEventDecoder decoder) {
        this.projections = projections;
        this.decoder = decoder;
    }

    @KafkaListener(id = "core-media-assets", topics = "media.assets.v1", groupId = "core-media-projection-v1",
            containerFactory = "mediaListenerFactory", autoStartup = "${libra.catalog.media-listener-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        try { projections.accept(record.key(), decoder.decode(record.key(), record.value())); }
        catch (RuntimeException exception) {
            // Kafka error handling may log failures. Never attach parser input or SQL exception text.
            throw new IllegalStateException("Media event processing failed");
        }
    }
}
