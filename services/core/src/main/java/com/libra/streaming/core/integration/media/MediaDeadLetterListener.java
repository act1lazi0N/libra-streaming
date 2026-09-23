package com.libra.streaming.core.integration.media;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class MediaDeadLetterListener {
    private final MediaDeadLetterStore store;
    public MediaDeadLetterListener(MediaDeadLetterStore store) { this.store = store; }
    @KafkaListener(id = "core-media-dlt", topics = "media.assets.v1.DLT", groupId = "core-media-dlt-v1",
            containerFactory = "mediaDeadLetterFactory", autoStartup = "${libra.integration.dlt-listener-enabled:true}")
    public void receive(ConsumerRecord<String, String> record) {
        try { store.capture(record); }
        catch (RuntimeException exception) { throw new IllegalStateException("Durable DLT capture failed"); }
    }
}
