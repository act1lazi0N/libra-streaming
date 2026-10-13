package com.libra.streaming.media.delivery.infrastructure;

import com.libra.streaming.media.delivery.application.DeliverableAssets;
import com.libra.streaming.media.delivery.application.StreamDelivery;
import com.libra.streaming.media.delivery.application.StreamObjects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StreamDeliveryConfiguration {
    @Bean
    StreamDelivery streamDelivery(DeliverableAssets assets, StreamObjects objects) {
        return new StreamDelivery(assets, objects);
    }
}
